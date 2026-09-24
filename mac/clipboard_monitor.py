"""
ClipSync — Clipboard Monitor Module

Polls the macOS general pasteboard (NSPasteboard) every 500ms for changes.
When new text is detected, it is deduplicated via SHA-256 and pushed into
an asyncio queue so the TCP server can broadcast it to connected clients.

Runs in a dedicated daemon thread to avoid blocking the asyncio event loop.
"""

import asyncio
import hashlib
import logging
import threading
from typing import Optional

from AppKit import NSPasteboard

logger = logging.getLogger(__name__)

# The UTI for plain UTF-8 text on macOS pasteboard
_UTF8_PLAIN_TEXT = "public.utf8-plain-text"

# Polling interval in seconds (500ms as specified)
_POLL_INTERVAL_S = 0.5


class ClipboardMonitor:
    """
    Watches the macOS system clipboard for text changes.

    The monitor runs in a background thread, polling NSPasteboard.generalPasteboard()
    at a fixed interval.  When the pasteboard's changeCount increments *and* the
    content differs from the last seen hash, the new text is enqueued for broadcast.

    Usage:
        monitor = ClipboardMonitor(loop, queue)
        monitor.start()
        ...
        monitor.stop()   # signals the thread to exit
    """

    def __init__(
        self,
        loop: asyncio.AbstractEventLoop,
        queue: asyncio.Queue,
    ) -> None:
        """
        Args:
            loop:  The running asyncio event loop (used for thread-safe enqueue).
            queue: An asyncio.Queue that receives new clipboard text strings.
        """
        self._loop = loop
        self._queue = queue
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        # Track the last changeCount so we only react to real changes
        self._last_change_count: int = -1
        # SHA-256 hex digest of the last enqueued text for deduplication
        self._last_hash: Optional[str] = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Start the clipboard polling thread."""
        if self._thread is not None and self._thread.is_alive():
            logger.warning("ClipboardMonitor is already running")
            return

        self._stop_event.clear()
        self._thread = threading.Thread(
            target=self._poll_loop,
            name="clipboard-monitor",
            daemon=True,
        )
        self._thread.start()
        logger.info("Clipboard monitor started (poll interval=%.1fs)", _POLL_INTERVAL_S)

    def stop(self) -> None:
        """Signal the polling thread to stop and wait for it to finish."""
        self._stop_event.set()
        if self._thread is not None:
            self._thread.join(timeout=2.0)
            logger.info("Clipboard monitor stopped")

    # ------------------------------------------------------------------
    # Internal
    # ------------------------------------------------------------------

    def _poll_loop(self) -> None:
        """
        Main polling loop that runs in the background thread.

        Continuously checks the pasteboard's changeCount.  When it changes,
        we attempt to extract UTF-8 plain text.  If the text's SHA-256 hash
        differs from the last one we sent, we push it onto the asyncio queue.
        """
        pasteboard = NSPasteboard.generalPasteboard()

        # Seed the changeCount so we don't immediately broadcast whatever is
        # on the clipboard at startup.
        self._last_change_count = pasteboard.changeCount()
        logger.debug("Initial changeCount: %d", self._last_change_count)

        while not self._stop_event.is_set():
            try:
                current_count = pasteboard.changeCount()

                if current_count != self._last_change_count:
                    self._last_change_count = current_count
                    self._handle_change(pasteboard)

            except Exception:
                # Don't let an unexpected error kill the monitor thread.
                logger.exception("Error during clipboard poll")

            # Sleep in small increments so we can respond to stop quickly
            self._stop_event.wait(timeout=_POLL_INTERVAL_S)

    def _handle_change(self, pasteboard: NSPasteboard) -> None:
        """
        Process a detected pasteboard change.

        Extracts text from the pasteboard and, if it differs from the last
        broadcast text (by SHA-256), enqueues it for the TCP server.
        """
        # Try to read plain text from the pasteboard
        text = pasteboard.stringForType_(_UTF8_PLAIN_TEXT)

        if text is None:
            # The clipboard contains non-text data (image, file, etc.) — skip
            logger.debug("Clipboard changed but contains no plain text — ignoring")
            return

        # `text` is an NSString; convert to a Python str just in case
        text = str(text)

        if not text:
            logger.debug("Clipboard text is empty — ignoring")
            return

        # Deduplicate: don't re-broadcast identical content
        text_hash = hashlib.sha256(text.encode("utf-8")).hexdigest()
        if text_hash == self._last_hash:
            logger.debug("Clipboard text unchanged (same hash) — skipping")
            return

        self._last_hash = text_hash
        text_len = len(text)
        logger.info(
            "New clipboard text detected (len=%d, sha256=%.12s…)", text_len, text_hash
        )

        # Thread-safe enqueue into the asyncio event loop
        self._loop.call_soon_threadsafe(self._queue.put_nowait, text)
