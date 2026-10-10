# Runs inside Ubuntu. Copies the latest brain into place and installs any new parts.
set -e
SRC=/root/jarvis-src/brain
mkdir -p /root/jarvis
cp "$SRC/server.py" "$SRC/persona.md" "$SRC/pdfmaker.py" /root/jarvis/

# PDF engine (system package, so it brings the libraries it needs)
if ! python3 -c "import weasyprint" >/dev/null 2>&1; then
  echo "Installing the PDF engine (one time, a few minutes)..."
  export DEBIAN_FRONTEND=noninteractive
  { apt-get update -qq && apt-get install -y -qq python3-weasyprint fonts-dejavu-core >/dev/null; } \
    || echo "(PDF engine didn't install; Jarvis will still work, just without PDFs)"
fi

REQ_HASH=$(sha1sum "$SRC/requirements.txt" | cut -d' ' -f1)
if [ "$(cat /root/jarvis/.req 2>/dev/null)" != "$REQ_HASH" ]; then
  echo "Installing new parts for Jarvis..."
  pip3 install --break-system-packages -q --upgrade -r "$SRC/requirements.txt"
  echo "$REQ_HASH" > /root/jarvis/.req
fi
