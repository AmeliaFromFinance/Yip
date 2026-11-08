#!/usr/bin/env bash
# Installs the Yip config watcher on a Debian/Ubuntu/Arch server.
# Usage: sudo ./install.sh [output_path]
set -euo pipefail

OUT="${1:-/var/www/config.sinist3r.lol/yip/playback.json}"
PREFIX=/opt/yip-watcher
USER_NAME=yipwatch
SRC="$(cd "$(dirname "$0")/.." && pwd)"

[[ $EUID -eq 0 ]] || { echo "Run as root (sudo)."; exit 1; }

id "$USER_NAME" &>/dev/null || useradd --system --home-dir "$PREFIX" --shell /usr/sbin/nologin "$USER_NAME"

install -d -o "$USER_NAME" -g "$USER_NAME" -m 755 "$PREFIX" "$PREFIX/state" /var/log/yip-watcher
install -d -o "$USER_NAME" -g "$USER_NAME" -m 700 "$PREFIX/keys"
install -o "$USER_NAME" -g "$USER_NAME" -m 755 "$SRC/yip_watcher.py" "$PREFIX/yip_watcher.py"
install -o "$USER_NAME" -g "$USER_NAME" -m 644 "$SRC/requirements.txt" "$PREFIX/requirements.txt"

# Output directory must be writable by the watcher and readable by Apache.
install -d -o "$USER_NAME" -g "$USER_NAME" -m 755 "$(dirname "$OUT")"

sudo -u "$USER_NAME" python3 -m venv "$PREFIX/.venv"
sudo -u "$USER_NAME" "$PREFIX/.venv/bin/pip" install --quiet --upgrade pip
sudo -u "$USER_NAME" "$PREFIX/.venv/bin/pip" install --quiet -r "$PREFIX/requirements.txt"

# Browser + system libraries. --with-deps needs apt; on Arch install the deps with pacman (see README).
if command -v apt-get &>/dev/null; then
  "$PREFIX/.venv/bin/python" -m playwright install-deps chromium
fi
sudo -u "$USER_NAME" PLAYWRIGHT_BROWSERS_PATH="$PREFIX/browsers" "$PREFIX/.venv/bin/python" -m playwright install chromium

if [[ ! -f "$PREFIX/keys/config_ed25519.pem" ]]; then
  sudo -u "$USER_NAME" "$PREFIX/.venv/bin/python" "$PREFIX/yip_watcher.py" keygen --key "$PREFIX/keys/config_ed25519.pem"
fi

sed "s#__OUT__#$OUT#g" "$SRC/deploy/yip-watcher.cron" > /etc/cron.d/yip-watcher
chmod 644 /etc/cron.d/yip-watcher
install -m 644 "$SRC/deploy/yip-watcher.logrotate" /etc/logrotate.d/yip-watcher

echo
echo "First run (takes about a minute):"
sudo -u "$USER_NAME" PLAYWRIGHT_BROWSERS_PATH="$PREFIX/browsers" "$PREFIX/.venv/bin/python" "$PREFIX/yip_watcher.py" run --out "$OUT" --state "$PREFIX/state/last.json" --key "$PREFIX/keys/config_ed25519.pem"
echo
echo "Done. Cron runs it every 30 minutes; logs in /var/log/yip-watcher/watcher.log"
