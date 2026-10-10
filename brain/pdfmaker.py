"""
Turns Jarvis's markdown reports into properly designed PDF documents.
"""
import datetime as dt
import html
import re
from pathlib import Path

OWNER = "Farhan"

CSS = """
@page {
  size: A4;
  margin: 24mm 20mm 22mm 20mm;
  @top-left {
    content: "JARVIS  \\00B7  PRIVATE DOSSIER";
    font-family: "DejaVu Sans", sans-serif; font-size: 7pt; letter-spacing: 1.6pt; color: #9a7b3f;
  }
  @top-right {
    content: string(docdate);
    font-family: "DejaVu Sans", sans-serif; font-size: 7pt; letter-spacing: 1pt; color: #8a8478;
  }
  @bottom-center {
    content: counter(page) " of " counter(pages);
    font-family: "DejaVu Sans", sans-serif; font-size: 7.5pt; color: #8a8478;
  }
}
@page :first { @top-left { content: none; } @top-right { content: none; } }

html { font-family: "Noto Serif", "DejaVu Serif", Georgia, serif; font-size: 10.5pt; line-height: 1.55; color: #1e1c18; }
body { margin: 0; }

.masthead { border-bottom: 1.2pt solid #b8964f; padding-bottom: 14pt; margin-bottom: 20pt; }
.label { font-family: "DejaVu Sans", sans-serif; font-size: 7.5pt; letter-spacing: 2.2pt; color: #9a7b3f; text-transform: uppercase; }
.masthead h1 { font-size: 25pt; line-height: 1.15; margin: 8pt 0 10pt; font-weight: bold; color: #14130f; string-set: doctitle content(); }
.meta { font-family: "DejaVu Sans", sans-serif; font-size: 8pt; color: #6f695e; string-set: docdate content(); }

h1 { font-size: 18pt; margin: 22pt 0 8pt; color: #14130f; }
h2 {
  font-family: "DejaVu Sans", sans-serif; font-size: 9pt; letter-spacing: 1.8pt; text-transform: uppercase;
  color: #8a6d33; margin: 22pt 0 8pt; padding-bottom: 4pt; border-bottom: 0.5pt solid #e2d6bb;
  page-break-after: avoid;
}
h3 { font-size: 12pt; margin: 16pt 0 4pt; color: #14130f; page-break-after: avoid; }
h4 { font-size: 10.5pt; margin: 12pt 0 3pt; font-style: italic; }
p { margin: 0 0 8pt; text-align: left; orphans: 3; widows: 3; }
strong { color: #14130f; }
ul, ol { margin: 0 0 9pt; padding-left: 16pt; }
li { margin-bottom: 3pt; }
li::marker { color: #9a7b3f; }

table { width: 100%; border-collapse: collapse; margin: 6pt 0 14pt; font-family: "DejaVu Sans", sans-serif; font-size: 8.5pt; page-break-inside: auto; }
thead th { text-align: left; font-weight: bold; color: #14130f; border-bottom: 1pt solid #b8964f; padding: 5pt 6pt; }
td { padding: 5pt 6pt; border-bottom: 0.4pt solid #e7e1d4; vertical-align: top; }
tbody tr:nth-child(even) td { background: #faf7f0; }
tr { page-break-inside: avoid; }

blockquote { margin: 10pt 0 12pt; padding: 8pt 12pt; border-left: 2.5pt solid #b8964f; background: #faf7f0; font-style: italic; color: #3b372f; }
blockquote p:last-child { margin-bottom: 0; }

code { font-family: "DejaVu Sans Mono", monospace; font-size: 8.5pt; background: #f3efe6; padding: 0 2pt; }
pre { background: #f3efe6; padding: 8pt; font-size: 8pt; white-space: pre-wrap; }
pre code { background: none; padding: 0; }

hr { border: none; border-top: 0.5pt solid #e2d6bb; margin: 16pt 0; }
a { color: #6b5221; text-decoration: none; border-bottom: 0.4pt solid #cdb987; }

.sources li { font-size: 8.5pt; color: #4a463e; word-wrap: break-word; }
.url { display: block; font-family: "DejaVu Sans", sans-serif; font-size: 7pt; color: #8a8478; word-break: break-all; }

.signoff { margin-top: 26pt; padding-top: 8pt; border-top: 0.5pt solid #e2d6bb; font-family: "DejaVu Sans", sans-serif; font-size: 7.5pt; letter-spacing: 1pt; color: #8a8478; }
"""


def _title_and_body(md_text: str) -> tuple[str, str]:
    lines = md_text.strip().splitlines()
    for i, line in enumerate(lines):
        m = re.match(r"^#\s+(.+)$", line.strip())
        if m:
            return m.group(1).strip(), "\n".join(lines[:i] + lines[i + 1:]).strip()
        if line.strip():
            break
    return "Report", md_text.strip()


def _style_sources(body_html: str) -> str:
    """Give the Sources section its own quieter style, and show each link's address."""
    def show_url(m: re.Match) -> str:
        href, text = m.group(1), m.group(2)
        if html.unescape(text).strip() == html.unescape(href).strip():
            return f'<a href="{href}">{text}</a>'
        return f'<a href="{href}">{text}</a><span class="url">{href}</span>'

    def sources_block(m: re.Match) -> str:
        block = re.sub(r'<a href="([^"]+)">(.*?)</a>', show_url, m.group(2))
        return m.group(1) + block.replace("<ul>", '<ul class="sources">', 1).replace("<ol>", '<ol class="sources">', 1)

    return re.sub(r"(<h[23][^>]*>\s*(?:Sources|References)\s*</h[23]>)(.*)$", sources_block,
                  body_html, flags=re.S | re.I)


def slugify(text: str) -> str:
    slug = re.sub(r"[^a-zA-Z0-9]+", "-", text).strip("-").lower()
    return slug[:60] or "report"


def make_pdf(md_text: str, out_dir: Path, when: dt.datetime) -> Path:
    import markdown  # imported here so the brain still starts if these are missing
    from weasyprint import HTML

    title, body = _title_and_body(md_text)
    body_html = markdown.markdown(body, extensions=["tables", "fenced_code", "sane_lists"])
    body_html = _style_sources(body_html)
    date_text = when.strftime("%-d %B %Y")
    signoff = "Compiled by Jarvis."
    if re.search(r"<h[23][^>]*>\s*(Sources|References)", body_html, re.I):
        signoff += " Figures drawn from public sources; worth checking before you act on them."
    doc = f"""<!doctype html><html><head><meta charset="utf-8"><title>{html.escape(title)}</title>
<style>{CSS}</style></head><body>
<div class="masthead">
  <div class="label">Prepared for {html.escape(OWNER)}</div>
  <h1>{html.escape(title)}</h1>
  <div class="meta">Prepared by Jarvis &middot; {date_text}</div>
</div>
{body_html}
<div class="signoff">{signoff}</div>
</body></html>"""
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"{when:%Y-%m-%d_%H%M}_{slugify(title)}.pdf"
    HTML(string=doc).write_pdf(path)
    return path
