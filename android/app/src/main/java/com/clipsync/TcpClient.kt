package com.clipsync

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * TCP client that connects to the ClipSync Mac daemon and receives clipboard data.
 *
 * Protocol (length-prefixed framing):
 * ┌──────────────────┬─────────────────────────────────────┐
 * │ 8 bytes          │ N bytes                             │
 * │ Big-endian uint64│ UTF-8 text body                     │
 * │ (message length) │ (clipboard content)                 │
 * └──────────────────┴─────────────────────────────────────┘
 *
 * 1. Read exactly 8 bytes → parse as big-endian 64-bit integer (message length)
 * 2. Read exactly that many bytes in 64KB chunks → decode as UTF-8 string
 * 3. Deliver the text via callback
 *
 * Bodies larger than [ClipboardHelper.MAX_TEXT_BYTES] can't be put on the Android
 * clipboard, so they are read and discarded (keeping the stream in sync) and
 * reported via [TcpCallback.onTextTooLarge] instead.
 *
 * CRITICAL: TCP is a stream protocol — a single send() on the server may arrive
 * as multiple read() calls on the client. We MUST loop until all bytes are read.
 *
 * Auto-reconnection: If the connection drops, we retry with exponential backoff
 * (1s → 2s → 4s → 8s → 16s → 30s max) to avoid overwhelming the network.
 */
class TcpClient(private val callback: TcpCallback) {
    companion object {
        private const val TAG = "TcpClient"
        /** Connection timeout in milliseconds */
        private const val CONNECT_TIMEOUT_MS = 10_000
        /** Read buffer size — 64KB chunks for efficient I/O */
        private const val CHUNK_SIZE = 65_536
        /** Maximum backoff delay in seconds */
        private const val MAX_BACKOFF_SECONDS = 30
    }

    /** Callback interface for TCP events */
    interface TcpCallback {
        /** Called when a complete text message is received */
        fun onTextReceived(text: String)
        /** Called when a message was too large for the clipboard and was discarded */
        fun onTextTooLarge(byteCount: Long)
        /** Called when the connection status changes */
        fun onStatusChanged(status: String)
    }

    /**
     * State of one connect() call. Each connection loop only ever touches its
     * own Connection, so a loop that is being torn down can't close or replace
     * the socket of the loop that superseded it.
     */
    private class Connection(val host: String, val port: Int) {
        @Volatile var socket: Socket? = null
        var job: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var current: Connection? = null

    /**
     * Connect to the ClipSync server and start the receive loop.
     *
     * Runs entirely on Dispatchers.IO (background thread pool for blocking I/O).
     * Automatically retries on connection failure with exponential backoff.
     * Any existing connection is closed first.
     *
     * @param host The IP address of the Mac daemon (from NSD resolution)
     * @param port The TCP port of the Mac daemon (from NSD resolution)
     */
    @Synchronized
    fun connect(host: String, port: Int) {
        disconnect()
        val conn = Connection(host, port)
        current = conn
        conn.job = scope.launch { runConnection(conn) }
    }

    /** True if we are connected (or retrying a connection) to [host]:[port]. */
    fun isTargeting(host: String, port: Int): Boolean {
        val conn = current ?: return false
        return conn.host == host && conn.port == port
    }

    /**
     * Connection loop for [conn]: connect, read until the connection drops,
     * back off, repeat — until the coroutine is cancelled by disconnect().
     */
    private suspend fun runConnection(conn: Connection) {
        var backoffSeconds = 1 // Start with 1 second delay
        val target = "${conn.host}:${conn.port}"

        while (currentCoroutineContext().isActive) {
            val s = Socket()
            conn.socket = s
            try {
                s.connect(InetSocketAddress(conn.host, conn.port), CONNECT_TIMEOUT_MS)
                // TCP_NODELAY reduces latency by disabling Nagle's algorithm
                s.tcpNoDelay = true
                // disconnect() may have run while connect() was blocking
                currentCoroutineContext().ensureActive()

                Log.i(TAG, "Connected to $target")
                callback.onStatusChanged("Connected to $target")
                backoffSeconds = 1 // Reset backoff on successful connection

                // Start reading messages from the server
                readLoop(BufferedInputStream(s.getInputStream()))

            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) break // Clean shutdown
                Log.w(TAG, "Connection error: ${e.message}")
                callback.onStatusChanged("Disconnected. Reconnecting in ${backoffSeconds}s...")
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                Log.e(TAG, "Unexpected error", e)
                callback.onStatusChanged("Error: ${e.message}")
            } finally {
                closeQuietly(s)
            }

            // Wait before retrying (exponential backoff)
            delay(backoffSeconds * 1000L)
            backoffSeconds = min(backoffSeconds * 2, MAX_BACKOFF_SECONDS)
        }
        Log.d(TAG, "Connection loop for $target ended")
    }

    /**
     * Main receive loop — reads length-prefixed messages from the TCP stream.
     *
     * Each message is:
     * 1. 8-byte big-endian header containing the body length
     * 2. N bytes of UTF-8 encoded text (the clipboard content)
     *
     * This loop runs until the connection is closed, an error occurs,
     * or the coroutine is cancelled.
     */
    private suspend fun readLoop(input: InputStream) {
        while (currentCoroutineContext().isActive) {
            // Step 1: Read the 8-byte length header
            val headerBytes = readExactly(input, 8)

            // Step 2: Parse header as big-endian 64-bit integer
            // ByteBuffer defaults to big-endian byte order, matching our protocol
            val messageLength = ByteBuffer.wrap(headerBytes).getLong()

            // A negative value means the uint64 was >= 2^63 — not a real
            // length. We can't find the next header, so drop the connection.
            if (messageLength < 0) {
                throw IOException("Invalid message length: $messageLength")
            }
            if (messageLength == 0L) continue // Empty message, no body

            if (messageLength > ClipboardHelper.MAX_TEXT_BYTES) {
                // Too large for the Android clipboard: consume the body so the
                // next header is read from the right place.
                Log.w(TAG, "Discarding $messageLength-byte message (too large for clipboard)")
                discardExactly(input, messageLength)
                callback.onTextTooLarge(messageLength)
                continue
            }

            Log.d(TAG, "Reading message of $messageLength bytes")

            // Step 3: Read the message body
            val bodyBytes = readExactly(input, messageLength.toInt())

            // Step 4: Decode UTF-8 and deliver
            val text = bodyBytes.toString(Charsets.UTF_8)
            Log.d(TAG, "Received text: ${text.length} chars")

            callback.onTextReceived(text)
        }
    }

    /**
     * Read exactly [count] bytes from the input stream.
     *
     * CRITICAL: TCP is a stream protocol — data may arrive in arbitrary-sized
     * fragments. A single read() call may return fewer bytes than requested.
     * We MUST loop until we've collected all expected bytes.
     *
     * Example: Server sends 1MB. Client might receive:
     *   read() → 8192 bytes
     *   read() → 16384 bytes
     *   read() → 4096 bytes
     *   ... (many more reads until 1MB is accumulated)
     *
     * We read in 64KB chunks for efficiency — large enough to reduce syscall
     * overhead, small enough to not waste memory.
     *
     * @param input The input stream to read from
     * @param count The exact number of bytes to read
     * @return ByteArray of exactly [count] bytes
     * @throws IOException if the connection is closed before all bytes are read
     */
    private fun readExactly(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            // Read up to 64KB at a time (or remaining bytes, whichever is smaller)
            val chunkSize = min(CHUNK_SIZE, count - offset)
            val bytesRead = input.read(buffer, offset, chunkSize)
            if (bytesRead == -1) {
                throw IOException("Connection closed by server (read $offset of $count bytes)")
            }
            offset += bytesRead
        }
        return buffer
    }

    /**
     * Read and throw away exactly [count] bytes, using a single 64KB buffer
     * so arbitrarily large messages don't need to fit in memory.
     *
     * @throws IOException if the connection is closed before all bytes are read
     */
    private fun discardExactly(input: InputStream, count: Long) {
        val buffer = ByteArray(CHUNK_SIZE)
        var remaining = count
        while (remaining > 0) {
            val bytesRead = input.read(buffer, 0, min(CHUNK_SIZE.toLong(), remaining).toInt())
            if (bytesRead == -1) {
                throw IOException("Connection closed by server (${count - remaining} of $count bytes discarded)")
            }
            remaining -= bytesRead
        }
    }

    /** Safely close a socket, ignoring any errors */
    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
    }

    /**
     * Disconnect from the server and stop the receive loop.
     * Safe to call multiple times.
     */
    @Synchronized
    fun disconnect() {
        val conn = current ?: return
        Log.d(TAG, "Disconnecting from ${conn.host}:${conn.port}...")
        current = null
        conn.job?.cancel()
        // Closing the socket unblocks a pending connect()/read()
        closeQuietly(conn.socket)
    }
}
