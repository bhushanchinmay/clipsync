"""
Tests for the ClipSync TCP server (wire framing and broadcast behaviour).

mDNS registration is stubbed out, so these run on any OS with `zeroconf`
installed:

    python -m unittest test_server
"""

import asyncio
import socket
import struct
import unittest
from unittest import mock

import server
from server import ClipSyncServer


async def _read_message(reader: asyncio.StreamReader) -> bytes:
    """Read one length-prefixed message, as the Android client does."""
    (length,) = struct.unpack(">Q", await reader.readexactly(8))
    return await reader.readexactly(length)


class ClipSyncServerTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        patcher = mock.patch.object(ClipSyncServer, "_register_mdns", mock.AsyncMock())
        patcher.start()
        self.addCleanup(patcher.stop)

        self.server = ClipSyncServer()
        self.port = await self.server.start()
        self.addAsyncCleanup(self.server.stop)

    async def _connect(self):
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        self.addCleanup(writer.transport.abort)
        return reader, writer

    async def _wait_for_clients(self, count: int) -> None:
        for _ in range(200):
            if self.server.client_count == count:
                return
            await asyncio.sleep(0.01)
        self.fail(f"expected {count} clients, have {self.server.client_count}")

    async def test_message_framing(self) -> None:
        reader, _ = await self._connect()
        await self._wait_for_clients(1)

        await self.server.broadcast_clipboard("Hello, World!")

        header = await reader.readexactly(8)
        self.assertEqual(header, bytes.fromhex("000000000000000D"))
        self.assertEqual(await reader.readexactly(13), b"Hello, World!")

    async def test_multi_chunk_utf8_message(self) -> None:
        reader, _ = await self._connect()
        await self._wait_for_clients(1)

        # Larger than one 64 KB chunk, with multi-byte characters
        text = "héllo wörld 🚀 " * 20_000
        send = asyncio.create_task(self.server.broadcast_clipboard(text))
        body = await _read_message(reader)
        await send

        self.assertEqual(body.decode("utf-8"), text)

    async def test_consecutive_messages(self) -> None:
        reader, _ = await self._connect()
        await self._wait_for_clients(1)

        await self.server.broadcast_clipboard("first")
        await self.server.broadcast_clipboard("second")

        self.assertEqual(await _read_message(reader), b"first")
        self.assertEqual(await _read_message(reader), b"second")

    async def test_stalled_client_is_dropped_without_blocking_others(self) -> None:
        # A client that never reads, with a tiny receive buffer so the
        # server's send buffer fills up quickly.
        stalled = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        stalled.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
        stalled.connect(("127.0.0.1", self.port))
        self.addCleanup(stalled.close)
        await self._wait_for_clients(1)

        healthy_reader, _ = await self._connect()
        await self._wait_for_clients(2)

        text = "x" * (8 * 1024 * 1024)
        with mock.patch.object(server, "_DRAIN_TIMEOUT_S", 0.5):
            loop = asyncio.get_running_loop()
            started = loop.time()
            send = asyncio.create_task(self.server.broadcast_clipboard(text))
            body = await asyncio.wait_for(_read_message(healthy_reader), timeout=5)
            await asyncio.wait_for(asyncio.shield(send), timeout=5)
            elapsed = loop.time() - started

        self.assertEqual(len(body), len(text))
        self.assertLess(elapsed, 5)
        await self._wait_for_clients(1)

    async def test_disconnected_client_is_removed(self) -> None:
        _, writer = await self._connect()
        await self._wait_for_clients(1)

        writer.close()
        await self._wait_for_clients(0)

        # Broadcasting with no clients is a no-op
        await self.server.broadcast_clipboard("nobody listening")


if __name__ == "__main__":
    unittest.main()
