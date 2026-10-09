"""
Jarvis brain. Runs on your phone inside Termux (Ubuntu) and thinks with your
Claude Pro plan through Claude Code. The Jarvis app talks to it on 127.0.0.1.
"""
import asyncio
import base64
import datetime as dt
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from zoneinfo import ZoneInfo

import edge_tts
import icalendar
import recurring_ical_events
import requests

TIMEZONE = "Asia/Kolkata"
TZ = ZoneInfo(TIMEZONE)
PORT = 8765
CLAUDE_MODEL = "sonnet"
# His voice. British male neural voices: en-GB-RyanNeural (warm), en-GB-ThomasNeural (crisper).
VOICE = "en-GB-RyanNeural"
VOICE_RATE = "-4%"
VOICE_PITCH = "-3Hz"

HERE = Path(__file__).resolve().parent
SESSION_FILE = HERE / ".session"
REPORTS = HERE / "reports"
CODE_FILE = HERE / "code.txt"
VOICE_CACHE = HERE / "voice_cache"
# Short lines the app says while he thinks. Pre-recorded at startup so they play instantly.
ACKS = [
    "Right away, sir.", "On it.", "Leave it with me.", "One moment, sir.",
    "Consider it handled.", "Very good, sir.", "Allow me.",
    "I didn't quite catch that, sir.",
]
LOCK = threading.Lock()  # one conversation, one turn at a time


def brain_code() -> str:
    if CODE_FILE.exists():
        return CODE_FILE.read_text().strip()
    code = f"{secrets.randbelow(10**6):06d}"
    CODE_FILE.write_text(code)
    return code


CODE = brain_code()


def find_claude() -> str | None:
    found = shutil.which("claude")
    if found:
        return found
    local = Path.home() / ".local" / "bin" / "claude"
    return str(local) if local.exists() else None


# ------------------------------------------------------------------ VOICE
def synth(text: str) -> bytes | None:
    """Speak text in his voice (free Microsoft neural voice). Short lines are cached."""
    text = text.strip()
    if not text:
        return None
    key = hashlib.sha1(f"{VOICE}|{VOICE_RATE}|{VOICE_PITCH}|{text}".encode()).hexdigest()
    cached = VOICE_CACHE / f"{key}.mp3"
    if cached.exists():
        return cached.read_bytes()

    async def run() -> bytes:
        out = bytearray()
        comm = edge_tts.Communicate(text, VOICE, rate=VOICE_RATE, pitch=VOICE_PITCH)
        async for chunk in comm.stream():
            if chunk["type"] == "audio":
                out += chunk["data"]
        return bytes(out)

    try:
        audio = asyncio.run(asyncio.wait_for(run(), timeout=40))
    except Exception as e:
        print(f"(voice unavailable, the phone's own voice will be used: {e})", flush=True)
        return None
    if audio and len(text) <= 120:
        VOICE_CACHE.mkdir(exist_ok=True)
        cached.write_bytes(audio)
    return audio or None


def synth_b64(text: str) -> str | None:
    audio = synth(text)
    return base64.b64encode(audio).decode() if audio else None


def warm_voice_cache() -> None:
    for line in ACKS:
        synth(line)


# ------------------------------------------------------------------ CALENDAR
_cal_cache: dict = {}


def _as_dt(value) -> dt.datetime:
    if isinstance(value, dt.datetime):
        return value.astimezone(TZ) if value.tzinfo else value.replace(tzinfo=TZ)
    return dt.datetime.combine(value, dt.time.min, tzinfo=TZ)


def calendar_context(url: str, days: int = 7) -> str:
    if not url:
        return "Calendar not connected."
    now = dt.datetime.now(TZ)
    hit = _cal_cache.get(url)
    if hit and (now - hit[0]).seconds < 300:
        return hit[1]
    try:
        cal = icalendar.Calendar.from_ical(requests.get(url, timeout=15).content)
        events = recurring_ical_events.of(cal).between(now - dt.timedelta(hours=1), now + dt.timedelta(days=days))
        lines = []
        for ev in sorted(events, key=lambda e: _as_dt(e["DTSTART"].dt)):
            start_raw = ev["DTSTART"].dt
            start = _as_dt(start_raw)
            title = str(ev.get("SUMMARY", "Untitled"))
            where = f" @ {ev['LOCATION']}" if ev.get("LOCATION") else ""
            if isinstance(start_raw, dt.datetime):
                end = _as_dt(ev["DTEND"].dt) if ev.get("DTEND") else start
                lines.append(f"{start:%a %d %b %H:%M}-{end:%H:%M}  {title}{where}")
            else:
                lines.append(f"{start:%a %d %b} (all day)  {title}{where}")
        text = "\n".join(lines) or "Nothing scheduled."
    except Exception as e:
        text = f"Calendar failed to load: {e}"
    _cal_cache[url] = (now, text)
    return text


# ------------------------------------------------------------------ CLAUDE
def ask_claude(text: str, calls: str | None, ics_url: str, _retry: bool = True) -> str:
    claude = find_claude()
    if not claude:
        return "Claude Code isn't installed in my brain, sir. Run jarvis-setup in Termux again."
    now = dt.datetime.now(TZ).strftime("%A %d %B %Y, %H:%M")
    context = f"Now: {now} ({TIMEZONE})\nCalendar, next 7 days:\n{calendar_context(ics_url)}"
    if calls:
        context += f"\nRecent calls on his phone, newest first:\n{calls}"
    prompt = f"[CONTEXT]\n{context}\n[/CONTEXT]\n\nFarhan says: {text}"

    cmd = [
        claude, "-p",
        "--output-format", "json",
        "--model", CLAUDE_MODEL,
        "--append-system-prompt-file", str(HERE / "persona.md"),
        "--allowedTools", "WebSearch", "WebFetch",
    ]
    sid = SESSION_FILE.read_text().strip() if SESSION_FILE.exists() else ""
    if sid:
        cmd += ["--resume", sid]

    # No API key ever: Claude Code must use the Pro plan login, never paid API credits.
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}
    env["PATH"] = f"{Path.home() / '.local' / 'bin'}:{env.get('PATH', '/usr/bin:/bin')}"
    try:
        r = subprocess.run(cmd, input=prompt, capture_output=True, text=True,
                           encoding="utf-8", cwd=HERE, env=env, timeout=900)
    except subprocess.TimeoutExpired:
        return "That one took too long, sir. Try narrowing the question."

    try:
        data = json.loads(r.stdout)
    except json.JSONDecodeError:
        if sid and _retry:  # stale session: start fresh and try once more
            SESSION_FILE.unlink(missing_ok=True)
            return ask_claude(text, calls, ics_url, _retry=False)
        detail = (r.stderr or r.stdout).strip()[:160]
        if "login" in detail.lower() or "auth" in detail.lower():
            return "I'm not logged in to Claude, sir. Run jarvis-login in Termux."
        return f"I've lost the uplink, sir. {detail}"

    if data.get("session_id"):
        SESSION_FILE.write_text(data["session_id"])
    if data.get("is_error"):
        return f"Headquarters isn't responding, sir. {str(data.get('result', ''))[:160]}"
    return str(data.get("result", "")).strip()


def for_voice(text: str) -> str:
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"[*#_`>|]", "", text)
    return re.sub(r"\s+", " ", text).strip()


# ------------------------------------------------------------------ HTTP
class Handler(BaseHTTPRequestHandler):
    def _send(self, status: int, obj: dict) -> None:
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path == "/ping":
            return self._send(200, {"ok": True})
        self._send(404, {"error": "not found"})

    def do_POST(self):
        given = self.headers.get("X-Jarvis-Code", "").encode()
        if not secrets.compare_digest(given, CODE.encode()):
            return self._send(401, {"error": "wrong brain code"})
        try:
            length = int(self.headers.get("Content-Length", 0))
            data = json.loads(self.rfile.read(length) or b"{}")
        except ValueError:
            return self._send(400, {"error": "bad request"})

        if self.path == "/reset":
            SESSION_FILE.unlink(missing_ok=True)
            return self._send(200, {"ok": True})
        if self.path == "/say":
            return self._send(200, {"audio": synth_b64(str(data.get("text", "")))})
        if self.path != "/ask":
            return self._send(404, {"error": "not found"})

        text = str(data.get("text", "")).strip()
        if not text:
            return self._send(400, {"error": "nothing was said"})
        print(f"> {text}", flush=True)
        with LOCK:
            reply = ask_claude(text, data.get("calls"), str(data.get("ics_url", "")).strip())
        spoken, _, report = reply.partition("===REPORT===")
        report = report.strip()
        if report:
            REPORTS.mkdir(exist_ok=True)
            (REPORTS / f"{dt.datetime.now(TZ):%Y-%m-%d_%H%M%S}.md").write_text(report, encoding="utf-8")
        spoken = for_voice(spoken) or "Done, sir."
        print(f"< {spoken}\n", flush=True)
        self._send(200, {"reply": spoken, "report": report, "audio": synth_b64(spoken)})

    def log_message(self, *args):
        pass


def main() -> None:
    print("\n  JARVIS brain online.")
    print(f"  Brain code: {CODE}   (type this into the Jarvis app's settings once)")
    if not find_claude():
        print("  Warning: Claude Code not found. Run jarvis-setup again.")
    print("  Leave this running. Close it with Ctrl+C.\n", flush=True)
    threading.Thread(target=warm_voice_cache, daemon=True).start()
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
