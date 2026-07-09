"""
ClipSync — TCP Server + mDNS Advertisement Module

Provides an asyncio-based TCP server that streams clipboard text to all
connected clients.  Uses zeroconf to advertise the service on the local
network so that Android (or other) clients can discover it automatically.

Wire protocol (per message):
    [8 bytes]  big-endian uint64 — length of the UTF-8 encoded text
    [N bytes]  UTF-8 text body, sent in 64 KB chunks

This design cleanly handles texts of 20+ million characters because:
  • The 8-byte header supports up to 2^64 bytes
  • Chunked sending avoids building a single enormous buffer
"""

import asyncio
import logging
import socket
import struct
from typing import Optional, Set

from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

logger = logging.getLogger(__name__)

# mDNS service type for ClipSync
MDNS_SERVICE_TYPE = "_clipsync._tcp.local."

# Chunk size for streaming large texts over TCP (64 KB)
_CHUNK_SIZE = 64 * 1024  # 65,536 bytes

# Struct format for the 8-byte big-endian length header
_HEADER_FMT = ">Q"
_HEADER_SIZE = struct.calcsize(_HEADER_FMT)  # 8 bytes


class ClipSyncServer:
    """
    Asyncio TCP server that broadcasts clipboard text to connected clients,
    with mDNS (Bonjour) advertisement for automatic service discovery.

    Lifecycle:
        server = ClipSyncServer()
        await server.start()
        ...
        await server.broadcast_clipboard("Hello, world!")
        ...
        await server.stop()
    """

    def __init__(self) -> None:
        self._server: Optional[asyncio.AbstractServer] = None
        self._clients: Set[asyncio.StreamWriter] = set()
        self._zeroconf: Optional[Zeroconf] = None
        self._service_info: Optional[ServiceInfo] = None
        self._port: int = 0
        # Lock to protect the _clients set during concurrent broadcasts
        self._clients_lock = asyncio.Lock()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    @property
    def port(self) -> int:
        """The TCP port the server is listening on (0 until started)."""
        return self._port

    @property
    def client_count(self) -> int:
        """Number of currently connected clients."""
        return len(self._clients)

    async def start(self) -> int:
        """
        Start the TCP server on an OS-assigned port and register mDNS.

        Returns:
            The TCP port number the server is listening on.
        """
        # Bind to all interfaces, let the OS pick a free port
        self._server = await asyncio.start_server(
            self._handle_client, host="0.0.0.0", port=0
        )

        # Retrieve the assigned port
        sock = self._server.sockets[0]
        self._port = sock.getsockname()[1]
        logger.info("TCP server listening on 0.0.0.0:%d", self._port)

        # Register mDNS so clients can discover us
        await self._register_mdns()

        return self._port

    async def stop(self) -> None:
        """Gracefully shut down the server, clients, and mDNS."""
        logger.info("Shutting down ClipSync server…")

        # 1. Unregister mDNS first so no new clients try to connect
        await self._unregister_mdns()

        # 2. Close all client connections
        async with self._clients_lock:
            for writer in list(self._clients):
                self._close_writer(writer)
            self._clients.clear()

        # 3. Stop the TCP server
        if self._server is not None:
            self._server.close()
            await self._server.wait_closed()
            logger.info("TCP server closed")

    async def broadcast_clipboard(self, text: str) -> None:
        """
        Send clipboard text to every connected client.

        Protocol:
            8-byte big-endian length header + UTF-8 body in 64 KB chunks.

        Disconnected or errored clients are silently removed from the set.

        Args:
            text: The clipboard text to broadcast.
        """
        if not self._clients:
            logger.debug("No clients connected — skipping broadcast")
            return

        text_bytes = text.encode("utf-8")
        header = struct.pack(_HEADER_FMT, len(text_bytes))

        logger.info(
            "Broadcasting %d bytes to %d client(s)",
            len(text_bytes),
            len(self._clients),
        )

        dead_clients: list[asyncio.StreamWriter] = []

        async with self._clients_lock:
            for writer in self._clients:
                try:
                    await self._send_message(writer, header, text_bytes)
                except (ConnectionError, OSError, asyncio.CancelledError) as exc:
                    peer = self._peer_name(writer)
                    logger.warning("Client %s disconnected during broadcast: %s", peer, exc)
                    dead_clients.append(writer)

            # Clean up dead connections
            for writer in dead_clients:
                self._clients.discard(writer)
                self._close_writer(writer)

    # ------------------------------------------------------------------
    # Client handling
    # ------------------------------------------------------------------

    async def _handle_client(
        self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter
    ) -> None:
        """
        Callback invoked for each new TCP connection.

        We add the writer to our client set so it receives future broadcasts.
        The reader is kept open so we can detect when the client disconnects.
        """
        peer = self._peer_name(writer)
        logger.info("New client connected: %s", peer)

        async with self._clients_lock:
            self._clients.add(writer)

        try:
            # Block until the client disconnects (sends EOF or resets)
            await reader.read()
        except (ConnectionError, OSError):
            pass
        finally:
            logger.info("Client disconnected: %s", peer)
            async with self._clients_lock:
                self._clients.discard(writer)
            self._close_writer(writer)

    # ------------------------------------------------------------------
    # Wire protocol helpers
    # ------------------------------------------------------------------

    @staticmethod
    async def _send_message(
        writer: asyncio.StreamWriter,
        header: bytes,
        body: bytes,
    ) -> None:
        """
        Send the length header followed by the body in 64 KB chunks.

        Using chunked writes prevents allocating a single massive buffer
        for very large clipboard texts (20M+ characters).
        """
        # Send the 8-byte length header
        writer.write(header)
        await writer.drain()

        # Send body in chunks
        offset = 0
        total = len(body)
        while offset < total:
            end = min(offset + _CHUNK_SIZE, total)
            writer.write(body[offset:end])
            await writer.drain()
            offset = end

    # ------------------------------------------------------------------
    # mDNS (zeroconf) helpers
    # ------------------------------------------------------------------

    async def _register_mdns(self) -> None:
        """
        Register the ClipSync service via mDNS/Bonjour.

        This allows Android/iOS/desktop clients on the same Wi-Fi to
        discover the daemon automatically without manual IP entry.
        """
        hostname = socket.gethostname()
        # Resolve the machine's local IP for the service record
        local_ip = self._get_local_ip()

        self._service_info = ServiceInfo(
            type_=MDNS_SERVICE_TYPE,
            name=f"ClipSync on {hostname}.{MDNS_SERVICE_TYPE}",
            addresses=[socket.inet_aton(local_ip)],
            port=self._port,
            properties={"version": "1"},
            server=f"{hostname}.local.",
        )

        self._async_zeroconf = AsyncZeroconf()
        await self._async_zeroconf.async_register_service(self._service_info)
        logger.info(
            "mDNS service registered: %s (ip=%s, port=%d)",
            self._service_info.name,
            local_ip,
            self._port,
        )

    async def _unregister_mdns(self) -> None:
        """Cleanly unregister the mDNS service and close zeroconf."""
        if getattr(self, "_async_zeroconf", None) is not None and getattr(self, "_service_info", None) is not None:
            try:
                await self._async_zeroconf.async_unregister_service(self._service_info)
                logger.info("mDNS service unregistered")
            except Exception:
                logger.exception("Error unregistering mDNS service")
            finally:
                await self._async_zeroconf.async_close()
                self._async_zeroconf = None
                self._service_info = None

    # ------------------------------------------------------------------
    # Utilities
    # ------------------------------------------------------------------

    @staticmethod
    def _get_local_ip() -> str:
        """
        Determine the machine's local network IP address.

        Opens a UDP socket (without sending data) to figure out which
        local interface would be used to reach an external address.
        Falls back to 127.0.0.1 if detection fails.
        """
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                # Doesn't actually send data — just triggers route lookup
                s.connect(("10.255.255.255", 1))
                return s.getsockname()[0]
        except OSError:
            logger.warning("Could not detect local IP — falling back to 127.0.0.1")
            return "127.0.0.1"

    @staticmethod
    def _peer_name(writer: asyncio.StreamWriter) -> str:
        """Get a human-readable string for the client's address."""
        try:
            addr = writer.get_extra_info("peername")
            if addr:
                return f"{addr[0]}:{addr[1]}"
        except Exception:
            pass
        return "<unknown>"

    @staticmethod
    def _close_writer(writer: asyncio.StreamWriter) -> None:
        """Safely close a StreamWriter, ignoring errors."""
        try:
            if not writer.is_closing():
                writer.close()
        except Exception:
            pass
