package com.clipsync

import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
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
        /** Maximum message size (100 MB) to prevent OOM from corrupt headers */
        private const val MAX_MESSAGE_SIZE = 100_000_000L
        /** Read buffer size — 64KB chunks for efficient I/O */
        private const val CHUNK_SIZE = 65_536
        /** Maximum backoff delay in seconds */
        private const val MAX_BACKOFF_SECONDS = 30
    }

    /** Callback interface for TCP events */
    interface TcpCallback {
        /** Called when a complete text message is received */
        fun onTextReceived(text: String)
        /** Called when the connection status changes */
        fun onStatusChanged(status: String)
    }

    private var socket: Socket? = null
    private var job: Job? = null
    private val isRunning = AtomicBoolean(false)

    /**
     * Connect to the ClipSync server and start the receive loop.
     *
     * Runs entirely on Dispatchers.IO (background thread pool for blocking I/O).
     * Automatically retries on connection failure with exponential backoff.
     *
     * @param host The IP address of the Mac daemon (from NSD resolution)
     * @param port The TCP port of the Mac daemon (from NSD resolution)
     */
    fun connect(host: String, port: Int) {
        // Don't start a second connection if one is already running
        if (isRunning.get()) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        isRunning.set(true)
        var backoffSeconds = 1 // Start with 1 second delay

        // Launch the connection loop in a background coroutine
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning.get() && isActive) {
                try {
                    // Create a new socket and connect with timeout
                    val s = Socket()
                    socket = s
                    s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                    // TCP_NODELAY reduces latency by disabling Nagle's algorithm
                    s.tcpNoDelay = true

                    Log.i(TAG, "Connected to $host:$port")
                    callback.onStatusChanged("Connected to $host:$port")
                    backoffSeconds = 1 // Reset backoff on successful connection

                    // Start reading messages from the server
                    val input = BufferedInputStream(s.getInputStream())
                    readLoop(input)

                } catch (e: IOException) {
                    if (!isRunning.get()) break // Clean shutdown

                    Log.w(TAG, "Connection error: ${e.message}")
                    callback.onStatusChanged("Disconnected. Reconnecting in ${backoffSeconds}s...")

                    // Close the failed socket
                    closeSocket()

                    // Wait before retrying (exponential backoff)
                    delay(backoffSeconds * 1000L)
                    backoffSeconds = min(backoffSeconds * 2, MAX_BACKOFF_SECONDS)

                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    Log.e(TAG, "Unexpected error", e)
                    callback.onStatusChanged("Error: ${e.message}")
                    closeSocket()
                    delay(backoffSeconds * 1000L)
                    backoffSeconds = min(backoffSeconds * 2, MAX_BACKOFF_SECONDS)
                }
            }
            Log.d(TAG, "Connection loop ended")
        }
    }

    /**
     * Main receive loop — reads length-prefixed messages from the TCP stream.
     *
     * Each message is:
     * 1. 8-byte big-endian header containing the body length
     * 2. N bytes of UTF-8 encoded text (the clipboard content)
     *
     * This loop runs until the connection is closed or an error occurs.
     */
    private fun readLoop(input: InputStream) {
        while (isRunning.get()) {
            // Step 1: Read the 8-byte length header
            val headerBytes = readExactly(input, 8)

            // Step 2: Parse header as big-endian 64-bit integer
            // ByteBuffer defaults to big-endian byte order, matching our protocol
            val messageLength = ByteBuffer.wrap(headerBytes).getLong()

            // Sanity check: reject invalid message sizes
            if (messageLength <= 0 || messageLength > MAX_MESSAGE_SIZE) {
                Log.w(TAG, "Invalid message length: $messageLength, skipping")
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

    /** Safely close the current socket, ignoring any errors */
    private fun closeSocket() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore close errors
        }
        socket = null
    }

    /**
     * Disconnect from the server and stop the receive loop.
     * Safe to call multiple times.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting...")
        isRunning.set(false)
        closeSocket()
        job?.cancel()
        job = null
    }
}
