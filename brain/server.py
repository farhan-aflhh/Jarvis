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
import tempfile
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
# Short lines the app speaks itself (fillers while he thinks, sign-offs, errors).
# Pre-recorded at startup so they play instantly. Keep in step with the app's lists.
ACKS = [
    # fillers
    "Mm, one moment.", "Let me see.", "Right, leave it with me.", "Bear with me, sir.",
    "On it.", "Ah, let me check.", "One moment, sir.", "Allow me.",
    # still working
    "Still on it, sir.", "Nearly there, sir.",
    # sign-offs
    "My pleasure, sir.", "Always, sir.", "Very good, sir.", "Any time, sir.",
    # errors
    "I didn't quite catch that, sir.",
    # older app versions
    "Right away, sir.", "Leave it with me.", "Consider it handled.",
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
def claude_env() -> dict:
    # No API key ever: Claude Code must use the Pro plan login, never paid API credits.
    env = {k: v for k, v in os.environ.items() if k != "ANTHROPIC_API_KEY"}
    env["PATH"] = f"{Path.home() / '.local' / 'bin'}:{env.get('PATH', '/usr/bin:/bin')}"
    return env


def build_prompt(text: str, calls: str | None, ics_url: str) -> str:
    now = dt.datetime.now(TZ).strftime("%A %d %B %Y, %H:%M")
    context = f"Now: {now} ({TIMEZONE})\nCalendar, next 7 days:\n{calendar_context(ics_url)}"
    if calls:
        context += f"\nRecent calls on his phone, newest first:\n{calls}"
    return f"[CONTEXT]\n{context}\n[/CONTEXT]\n\nFarhan says: {text}"


def build_cmd(claude: str, streaming: bool) -> tuple[list[str], str]:
    cmd = [
        claude, "-p",
        "--output-format", "stream-json" if streaming else "json",
        "--model", CLAUDE_MODEL,
        "--append-system-prompt-file", str(HERE / "persona.md"),
        "--allowedTools", "WebSearch", "WebFetch",
    ]
    if streaming:
        cmd += ["--verbose", "--include-partial-messages"]
    sid = SESSION_FILE.read_text().strip() if SESSION_FILE.exists() else ""
    if sid:
        cmd += ["--resume", sid]
    return cmd, sid


def explain_failure(detail: str) -> str:
    low = detail.lower()
    if "login" in low or "auth" in low:
        return "I'm afraid I've been logged out of Claude, sir. Run jarvis-login in Termux."
    if "limit" in low:
        return "I've hit the Claude usage limit for now, sir. It resets in a few hours."
    return f"I've lost the uplink, sir. {detail[:120]}"


def ask_claude(text: str, calls: str | None, ics_url: str, _retry: bool = True) -> str:
    """Whole answer at once. Used by older versions of the app."""
    claude = find_claude()
    if not claude:
        return "Claude Code isn't installed in my brain, sir. Run jarvis-setup in Termux again."
    cmd, sid = build_cmd(claude, streaming=False)
    try:
        r = subprocess.run(cmd, input=build_prompt(text, calls, ics_url), capture_output=True,
                           text=True, encoding="utf-8", cwd=HERE, env=claude_env(), timeout=900)
    except subprocess.TimeoutExpired:
        return "That one took too long, sir. Try narrowing the question."
    try:
        data = json.loads(r.stdout)
    except json.JSONDecodeError:
        if sid and _retry:  # stale session: start fresh and try once more
            SESSION_FILE.unlink(missing_ok=True)
            return ask_claude(text, calls, ics_url, _retry=False)
        return explain_failure((r.stderr or r.stdout).strip())
    if data.get("session_id"):
        SESSION_FILE.write_text(data["session_id"])
    if data.get("is_error"):
        return explain_failure(str(data.get("result", "")))
    return str(data.get("result", "")).strip()


class Sentences:
    """Turns streamed text into whole spoken sentences, stopping at the report marker."""

    MARKER = "===REPORT==="
    END = re.compile(r"[.!?…][\"')\]]*\s")

    def __init__(self, emit):
        self.emit = emit
        self.buf = ""
        self.done = False
        self.spoken: list[str] = []

    def feed(self, text: str) -> None:
        if self.done:
            return
        self.buf += text
        cut = self.buf.find(self.MARKER)
        if cut != -1:
            self.buf = self.buf[:cut]
            self.finish()
            return
        while True:
            m = self.END.search(self.buf)
            if not m:
                return
            sentence = self.buf[:m.end()]
            if "=" in sentence:  # might be the start of the report marker; wait for more
                return
            self.buf = self.buf[m.end():]
            self._say(sentence)

    def finish(self) -> None:
        if self.done:
            return
        rest = self.buf.split("===")[0]
        self.buf = ""
        self.done = True
        self._say(rest)

    def _say(self, text: str) -> None:
        text = for_voice(text)
        if re.search(r"\w", text):
            self.spoken.append(text)
            self.emit(text)


def ask_claude_streaming(text: str, calls: str | None, ics_url: str, emit, alive=lambda: True,
                         _retry: bool = True) -> tuple[str, str]:
    """Speaks the answer sentence by sentence as Claude writes it. Returns (spoken, report)."""
    claude = find_claude()
    if not claude:
        msg = "Claude Code isn't installed in my brain, sir. Run jarvis-setup in Termux again."
        emit(msg)
        return msg, ""
    cmd, sid = build_cmd(claude, streaming=True)
    sentences = Sentences(emit)
    result = None
    saw_delta = False
    with tempfile.TemporaryFile(mode="w+", encoding="utf-8") as err:
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=err,
                                text=True, encoding="utf-8", cwd=HERE, env=claude_env())
        killer = threading.Timer(900, proc.kill)
        killer.start()
        try:
            proc.stdin.write(build_prompt(text, calls, ics_url))
            proc.stdin.close()
            for line in proc.stdout:
                if not alive():  # he interrupted: stop thinking about the old question
                    proc.kill()
                    break
                try:
                    ev = json.loads(line)
                except ValueError:
                    continue
                kind = ev.get("type")
                if kind == "stream_event":
                    e = ev.get("event") or {}
                    if e.get("type") == "content_block_delta" and (e.get("delta") or {}).get("type") == "text_delta":
                        saw_delta = True
                        sentences.feed(e["delta"].get("text", ""))
                    elif e.get("type") == "content_block_stop":
                        sentences.feed("\n")
                elif kind == "assistant" and not saw_delta:
                    # Fallback if this Claude Code doesn't send partial messages.
                    for block in (ev.get("message") or {}).get("content") or []:
                        if block.get("type") == "text":
                            sentences.feed(block.get("text", "") + "\n")
                elif kind == "result":
                    result = ev
            proc.wait()
        finally:
            killer.cancel()
        err.seek(0)
        detail = err.read().strip()

    if not alive():
        return " ".join(sentences.spoken), ""
    if result is None:
        if sid and _retry and not sentences.spoken:  # stale session: start fresh, try once more
            SESSION_FILE.unlink(missing_ok=True)
            return ask_claude_streaming(text, calls, ics_url, emit, alive, _retry=False)
        sentences.finish()
        if not sentences.spoken:
            msg = explain_failure(detail or "No answer came back.")
            emit(msg)
            return msg, ""
        return " ".join(sentences.spoken), ""

    if result.get("session_id"):
        SESSION_FILE.write_text(result["session_id"])
    full = str(result.get("result", "")).strip()
    if result.get("is_error") and not sentences.spoken:
        msg = explain_failure(full or detail)
        emit(msg)
        return msg, ""
    spoken_part, _, report = full.partition(Sentences.MARKER)
    sentences.finish()
    if not sentences.spoken:  # nothing streamed: speak the final answer whole
        sentences.done = False
        sentences.feed(spoken_part + "\n")
        sentences.finish()
    return " ".join(sentences.spoken), report.strip()


def for_voice(text: str) -> str:
    text = re.sub(r"https?://\S+", "", text)
    text = re.sub(r"[*#_`>|]", "", text)
    return re.sub(r"\s+", " ", text).strip()


def save_report(report: str) -> None:
    if report:
        REPORTS.mkdir(exist_ok=True)
        (REPORTS / f"{dt.datetime.now(TZ):%Y-%m-%d_%H%M%S}.md").write_text(report, encoding="utf-8")


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
        if self.path not in ("/ask", "/ask_stream"):
            return self._send(404, {"error": "not found"})

        text = str(data.get("text", "")).strip()
        if not text:
            return self._send(400, {"error": "nothing was said"})
        calls = data.get("calls")
        ics_url = str(data.get("ics_url", "")).strip()
        print(f"> {text}", flush=True)

        if self.path == "/ask":  # older app versions: whole answer at once
            with LOCK:
                reply = ask_claude(text, calls, ics_url)
            spoken, _, report = reply.partition(Sentences.MARKER)
            save_report(report.strip())
            spoken = for_voice(spoken) or "Done, sir."
            print(f"< {spoken}\n", flush=True)
            return self._send(200, {"reply": spoken, "report": report.strip(), "audio": synth_b64(spoken)})

        # Streaming: one JSON line per sentence, in his voice, as soon as it's ready.
        self.send_response(200)
        self.send_header("Content-Type", "application/x-ndjson")
        self.end_headers()
        listening = [True]

        def write(obj: dict) -> None:
            if not listening[0]:
                return
            try:
                self.wfile.write((json.dumps(obj) + "\n").encode())
                self.wfile.flush()
            except OSError:  # he interrupted; let Claude finish so the memory stays intact
                listening[0] = False

        def emit(sentence: str) -> None:
            print(f"< {sentence}", flush=True)
            if listening[0]:
                write({"say": sentence, "audio": synth_b64(sentence)})

        with LOCK:
            spoken, report = ask_claude_streaming(text, calls, ics_url, emit, alive=lambda: listening[0])
        save_report(report)
        print(flush=True)
        write({"done": True, "reply": spoken, "report": report})

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
