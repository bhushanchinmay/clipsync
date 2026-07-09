# ClipSync

**Seamless, local clipboard sync from Mac to Android over Wi-Fi.**

Copy any text on your Mac — it's instantly available to paste on your Android phone. No internet required, no character limit, no cloud services. Just your local Wi-Fi network.

## Features

- 🔄 **Instant sync** — Copy on Mac, paste on Android within ~1 second
- 📝 **No character limit** — Works with 20+ million characters (unlike KDE Connect's 20K limit)
- 🔌 **No internet needed** — Works over local Wi-Fi, even with no internet connection
- 👻 **Invisible on Mac** — No app, no menu bar icon, no dock icon. Runs silently as a system daemon
- 📱 **Minimal Android app** — Just a persistent notification and clipboard history
- 📋 **Clipboard history** — Browse and re-copy past clipboard entries on Android
- 🔍 **Auto-discovery** — Devices find each other automatically via mDNS (like AirDrop)
- 📡 **Multi-device** — Broadcasts to all connected devices simultaneously

## How It Works

```
Mac (copies text) → Wi-Fi (mDNS + TCP) → Android (sets clipboard)
```

1. A background daemon on your Mac watches the clipboard
2. When you copy text, it's instantly sent over TCP to all connected Android devices
3. The Android app receives the text and sets it as your clipboard content
4. You can now paste it anywhere on your Android

## Setup

### Mac (one-time setup)

```bash
cd mac/
chmod +x install.sh
./install.sh
```

This will:
- Create a Python virtual environment
- Install dependencies (`pyobjc`, `zeroconf`)
- Install a LaunchAgent to auto-start on login
- Start the daemon immediately

**That's it!** The daemon runs invisibly in the background. It starts automatically when you log in.

#### Managing the daemon

```bash
# Check if running
ps aux | grep clipsync

# View logs
tail -f ~/Library/Logs/ClipSync/clipsync.log

# Stop
launchctl unload ~/Library/LaunchAgents/com.clipsync.daemon.plist

# Start
launchctl load ~/Library/LaunchAgents/com.clipsync.daemon.plist

# Uninstall
launchctl unload ~/Library/LaunchAgents/com.clipsync.daemon.plist
rm ~/Library/LaunchAgents/com.clipsync.daemon.plist
```

### Android

1. Build the APK from the `android/` directory:
   ```bash
   cd android/
   ./gradlew assembleDebug
   ```
2. Install the APK on your Android phone:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
3. Open ClipSync and grant notification permission
4. The app will automatically discover your Mac and start syncing

## Requirements

- **Mac**: macOS 10.15+ with Python 3.9+
- **Android**: Android 10+ (API 29+)
- **Network**: Both devices on the same Wi-Fi network
- **Internet**: Not required at all

## Architecture

| Component | Language | Runs As | UI |
|-----------|----------|---------|-----|
| Mac Daemon | Python 3 | `launchd` LaunchAgent | None |
| Android App | Kotlin | Foreground Service | Notification + History |

### Protocol

- **Discovery**: mDNS/DNS-SD (`_clipsync._tcp`)
- **Transfer**: TCP with 8-byte length-prefixed framing
- **Chunks**: 64KB for streaming large texts
- **Encoding**: UTF-8

See [PROTOCOL.md](PROTOCOL.md) for the full wire protocol specification.

## Limitations

- **One-way only**: Mac → Android. Android → Mac is not supported due to Android OS restrictions on reading the clipboard from background services.
- **Text only**: Does not sync images, files, or rich text formatting.
- **Same Wi-Fi**: Both devices must be on the same local network.
- **Android sideload**: The app must be installed via APK, not from the Play Store.

## License

MIT
