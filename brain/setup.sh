#!/data/data/com.termux/files/usr/bin/bash
# Jarvis brain setup for Termux. Safe to run again: it updates in place.
set -e
REPO="https://github.com/farhan-aflhh/jarvis"
RAW="https://raw.githubusercontent.com/farhan-aflhh/jarvis/main/brain/setup.sh"

echo
echo "== Setting up Jarvis's brain. This takes about 10 minutes. =="
echo

# Android sometimes wipes Termux's download cache; recreate it so apt can work.
mkdir -p /data/data/com.termux/cache/apt/archives/partial

apt-get update -y
apt-get -y -o Dpkg::Options::="--force-confnew" upgrade
apt-get install -y proot-distro curl

# Install Ubuntu. If it's already there, proot-distro says so and we carry on.
proot-distro install ubuntu </dev/null || echo "(Ubuntu is already installed, carrying on.)"

INNER=$(cat <<'EOF'
set -e
export DEBIAN_FRONTEND=noninteractive
export PATH="$HOME/.local/bin:$PATH"
apt-get update -y
apt-get install -y curl git ca-certificates tzdata python3 python3-pip
rm -rf /root/jarvis-src
git clone --depth 1 https://github.com/farhan-aflhh/jarvis /root/jarvis-src
rm -f /root/jarvis/.req
bash /root/jarvis-src/brain/install.sh
if ! claude --version >/dev/null 2>&1; then
  curl -fsSL https://claude.ai/install.sh | bash || true
fi
if ! claude --version >/dev/null 2>&1; then
  echo "Native installer didn't work here, using npm instead..."
  apt-get install -y nodejs npm
  npm install -g @anthropic-ai/claude-code
fi
echo "Claude Code: $(claude --version)"
EOF
)
proot-distro login ubuntu -- bash -c "$INNER" </dev/null

cat > "$PREFIX/bin/jarvis" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
termux-wake-lock 2>/dev/null || true
exec proot-distro login ubuntu -- bash -c '
export PATH="$HOME/.local/bin:$PATH"
echo "Checking for Jarvis updates..."
if git -C /root/jarvis-src fetch -q --depth 1 origin main 2>/dev/null; then
  git -C /root/jarvis-src reset -q --hard FETCH_HEAD
  bash /root/jarvis-src/brain/install.sh || echo "(update step failed, starting the current version)"
else
  echo "(offline, starting the current version)"
fi
cd /root/jarvis && exec python3 -u server.py'
EOF

cat > "$PREFIX/bin/jarvis-login" <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
echo
echo "Log in with your Claude Pro account (the subscription option, not Console/API)."
echo "Long-press the link it shows to open it, approve, then paste the code back here."
echo "When you see the chat prompt, type /exit"
echo
proot-distro login ubuntu -- bash -c 'export PATH="$HOME/.local/bin:$PATH"; unset ANTHROPIC_API_KEY; cd /root/jarvis && claude'
EOF

cat > "$PREFIX/bin/jarvis-setup" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
curl -fsSL $RAW | bash
EOF

chmod +x "$PREFIX/bin/jarvis" "$PREFIX/bin/jarvis-login" "$PREFIX/bin/jarvis-setup"

echo
echo "== Brain installed. =="
echo "Next: type  jarvis-login  (one time), then  jarvis  to start him."
echo
