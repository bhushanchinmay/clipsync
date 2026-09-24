#!/bin/bash
#
# ClipSync Mac daemon installer.
#
#   ./install.sh              Create the venv, install dependencies, install and
#                             start the LaunchAgent (auto-starts at login).
#   ./install.sh --uninstall  Stop the daemon and remove the LaunchAgent.
#
# Safe to re-run: it updates the venv and reloads the LaunchAgent.

set -euo pipefail

LABEL="com.clipsync.daemon"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/venv"
PLIST_SRC="$SCRIPT_DIR/$LABEL.plist"
PLIST_DST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG_DIR="$HOME/Library/Logs/ClipSync"

die() {
    echo "Error: $*" >&2
    exit 1
}

# Escape a string for use inside a plist <string> element
xml_escape() {
    printf '%s' "$1" | sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g'
}

unload_agent() {
    if [[ -f "$PLIST_DST" ]]; then
        launchctl unload "$PLIST_DST" 2>/dev/null || true
    fi
}

[[ "$(uname -s)" == "Darwin" ]] || die "ClipSync's daemon only runs on macOS."

if [[ "${1:-}" == "--uninstall" ]]; then
    unload_agent
    rm -f "$PLIST_DST" "$PLIST_SRC"
    echo "ClipSync LaunchAgent removed. (The venv at $VENV_DIR was left in place.)"
    exit 0
elif [[ $# -gt 0 ]]; then
    die "Unknown argument: $1 (usage: $0 [--uninstall])"
fi

# ---- Python ----
command -v python3 >/dev/null || die "python3 not found. Install Python 3.9+ (e.g. 'xcode-select --install' or python.org)."
python3 -c 'import sys; sys.exit(sys.version_info < (3, 9))' \
    || die "Python 3.9+ is required (found $(python3 --version 2>&1))."

echo "==> Creating virtual environment in $VENV_DIR"
python3 -m venv "$VENV_DIR"

echo "==> Installing dependencies"
"$VENV_DIR/bin/python" -m pip install --quiet --upgrade pip
"$VENV_DIR/bin/python" -m pip install --quiet -r "$SCRIPT_DIR/requirements.txt"

# ---- LaunchAgent ----
echo "==> Installing LaunchAgent $PLIST_DST"
mkdir -p "$LOG_DIR" "$(dirname "$PLIST_DST")"

cat > "$PLIST_SRC" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$(xml_escape "$VENV_DIR/bin/python")</string>
        <string>$(xml_escape "$SCRIPT_DIR/main.py")</string>
    </array>
    <key>WorkingDirectory</key>
    <string>$(xml_escape "$SCRIPT_DIR")</string>
    <!-- Start at login, and restart if the daemon crashes (but not after a clean stop) -->
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <dict>
        <key>SuccessfulExit</key>
        <false/>
    </dict>
    <key>ProcessType</key>
    <string>Background</string>
    <key>StandardOutPath</key>
    <string>$(xml_escape "$LOG_DIR/launchd.log")</string>
    <key>StandardErrorPath</key>
    <string>$(xml_escape "$LOG_DIR/launchd.log")</string>
</dict>
</plist>
EOF

plutil -lint "$PLIST_SRC" >/dev/null || die "Generated plist is invalid: $PLIST_SRC"
cp "$PLIST_SRC" "$PLIST_DST"

echo "==> Starting daemon"
unload_agent
launchctl load "$PLIST_DST"

echo
echo "ClipSync is running and will start automatically at login."
echo "Logs: $LOG_DIR/clipsync.log"
