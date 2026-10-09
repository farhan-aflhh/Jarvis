# Runs inside Ubuntu. Copies the latest brain into place and installs any new Python parts.
set -e
SRC=/root/jarvis-src/brain
mkdir -p /root/jarvis
cp "$SRC/server.py" "$SRC/persona.md" /root/jarvis/
REQ_HASH=$(sha1sum "$SRC/requirements.txt" | cut -d' ' -f1)
if [ "$(cat /root/jarvis/.req 2>/dev/null)" != "$REQ_HASH" ]; then
  echo "Installing new parts for Jarvis..."
  pip3 install --break-system-packages -q --upgrade -r "$SRC/requirements.txt"
  echo "$REQ_HASH" > /root/jarvis/.req
fi
