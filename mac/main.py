#!/usr/bin/env python3
"""
ClipSync Mac Daemon — Entry Point

A background daemon that:
  1. Monitors the macOS clipboard for text changes (via pyobjc / NSPasteboard)
  2. Runs a TCP server on an OS-assigned port
  3. Advertises itself via mDNS (Bonjour) for automatic discovery
  4. Broadcasts every new clipboard text to all connected clients

No UI.  No encryption.  Just simple, fast, local clipboard sync over Wi-Fi.

Usage:
    python main.py          # run in foreground
    launchctl load …        # run as a LaunchAgent (see install.sh)

Signals:
    SIGTERM / SIGINT → graceful shutdown (unregister mDNS, close server)

Logging:
    ~/Library/Logs/ClipSync/clipsync.log  (rotated at 5 MB × 3 backups)
"""

import asyncio
import logging
import os
import signal
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

from clipboard_monitor import ClipboardMonitor
from server import ClipSyncServer

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

LOG_DIR = Path.home() / "Library" / "Logs" / "ClipSync"
LOG_FILE = LOG_DIR / "clipsync.log"
LOG_MAX_BYTES = 5 * 1024 * 1024   # 5 MB per log file
LOG_BACKUP_COUNT = 3               # keep 3 rotated backups
LOG_FORMAT = "%(asctime)s [%(levelname)s] %(name)s: %(message)s"
LOG_DATE_FORMAT = "%Y-%m-%d %H:%M:%S"


# ---------------------------------------------------------------------------
# Logging setup
# ---------------------------------------------------------------------------

def setup_logging() -> None:
    """
    Configure root logger to write to a rotating file in ~/Library/Logs/ClipSync/
    and also to stderr (useful when running interactively or for launchd capture).
    """
    LOG_DIR.mkdir(parents=True, exist_ok=True)

    root = logging.getLogger()
    root.setLevel(logging.DEBUG)

    # Rotating file handler
    file_handler = RotatingFileHandler(
        LOG_FILE,
        maxBytes=LOG_MAX_BYTES,
        backupCount=LOG_BACKUP_COUNT,
        encoding="utf-8",
    )
    file_handler.setLevel(logging.DEBUG)
    file_handler.setFormatter(logging.Formatter(LOG_FORMAT, datefmt=LOG_DATE_FORMAT))
    root.addHandler(file_handler)

    # Stderr handler (for interactive use and launchd stdout/stderr capture)
    stderr_handler = logging.StreamHandler(sys.stderr)
    stderr_handler.setLevel(logging.INFO)
    stderr_handler.setFormatter(logging.Formatter(LOG_FORMAT, datefmt=LOG_DATE_FORMAT))
    root.addHandler(stderr_handler)


# ---------------------------------------------------------------------------
# Main async entry point
# ---------------------------------------------------------------------------

async def run() -> None:
    """
    Main coroutine that orchestrates all components:

    1. Start the TCP server (OS-assigned port)
    2. Register mDNS advertisement (so clients can discover us)
    3. Start the clipboard monitor thread
    4. Loop: dequeue clipboard changes → broadcast to all clients
    5. On shutdown signal: clean up everything gracefully
    """
    logger = logging.getLogger("clipsync")
    logger.info("ClipSync daemon starting (pid=%d)", os.getpid())

    loop = asyncio.get_running_loop()
    shutdown_event = asyncio.Event()

    # ---- Signal handlers ----
    # On SIGTERM/SIGINT, set the shutdown event so the main loop exits cleanly.
    def _request_shutdown(sig: int) -> None:
        sig_name = signal.Signals(sig).name
        logger.info("Received %s — initiating graceful shutdown", sig_name)
        shutdown_event.set()

    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, _request_shutdown, sig)

    # ---- Clipboard change queue ----
    # The ClipboardMonitor (thread) pushes new text here;
    # the main loop reads from it and broadcasts to TCP clients.
    clipboard_queue: asyncio.Queue[str] = asyncio.Queue()

    # ---- Start TCP server ----
    server = ClipSyncServer()
    port = await server.start()
    logger.info("TCP server ready on port %d", port)

    # ---- Start clipboard monitor ----
    monitor = ClipboardMonitor(loop, clipboard_queue)
    monitor.start()

    # ---- Main broadcast loop ----
    logger.info("ClipSync daemon is running — waiting for clipboard changes")

    try:
        while not shutdown_event.is_set():
            try:
                # Wait for a clipboard change, but check the shutdown event
                # periodically so we don't hang forever.
                text = await asyncio.wait_for(
                    clipboard_queue.get(), timeout=1.0
                )
            except asyncio.TimeoutError:
                # No clipboard change — just loop and recheck shutdown_event
                continue

            # Broadcast the new clipboard text to all connected clients
            await server.broadcast_clipboard(text)

    except asyncio.CancelledError:
        logger.info("Main loop cancelled")

    finally:
        # ---- Graceful shutdown ----
        logger.info("Cleaning up…")
        monitor.stop()
        await server.stop()
        logger.info("ClipSync daemon stopped")


# ---------------------------------------------------------------------------
# Script entry point
# ---------------------------------------------------------------------------

def main() -> None:
    """Set up logging and run the async event loop."""
    setup_logging()
    logger = logging.getLogger("clipsync")

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        # Already handled by signal handler, but just in case
        logger.info("Interrupted — exiting")
    except Exception:
        logger.exception("Fatal error in ClipSync daemon")
        sys.exit(1)


if __name__ == "__main__":
    main()
