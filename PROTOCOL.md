# ClipSync Wire Protocol

This document describes the communication protocol between the Mac daemon (sender) and Android app (receiver).

## Overview

ClipSync uses two protocols:
1. **mDNS/DNS-SD** for automatic service discovery on the local network
2. **TCP** with a custom length-prefixed framing protocol for text transfer

No internet connection is required. Both devices must be on the same Wi-Fi network (or any network where multicast traffic is allowed).

## Service Discovery (mDNS)

The Mac daemon advertises itself using mDNS with the following parameters:

| Field | Value |
|-------|-------|
| Service Type | `_clipsync._tcp.local.` |
| Service Name | `ClipSync on <hostname>` |
| Port | Dynamic (assigned by OS) |
| TXT Records | `version=1` |

The Android app discovers this service using Android's `NsdManager` API, which is compatible with Bonjour/mDNS.

## Text Transfer Protocol (TCP)

Once the Android app discovers the Mac daemon and resolves its IP + port, it establishes a persistent TCP connection.

### Message Format

Each clipboard update is sent as a single message with this format:

```
┌─────────────────────┬──────────────────────────────┐
│  Header (8 bytes)   │  Body (N bytes)              │
│  uint64 big-endian  │  UTF-8 encoded text          │
│  = N (body length)  │  sent in 64KB chunks         │
└─────────────────────┴──────────────────────────────┘
```

- **Header**: Exactly 8 bytes. A `uint64` in big-endian byte order representing the length of the body in bytes.
- **Body**: Exactly N bytes of UTF-8 encoded text. Sent and received in 64KB (65,536 byte) chunks.

### Example

Sending the text `"Hello, World!"` (13 bytes in UTF-8):

```
Header: 00 00 00 00 00 00 00 0D  (13 as uint64 big-endian)
Body:   48 65 6C 6C 6F 2C 20 57 6F 72 6C 64 21  ("Hello, World!" in UTF-8)
```

### Important Implementation Notes

1. **Partial reads**: TCP is a stream protocol. A single `read()` call may return fewer bytes than requested. Implementations MUST loop until all expected bytes are received.

2. **Chunked sending**: For large texts, the body is sent in 64KB chunks. The receiver assembles these into the complete text.

3. **No framing between chunks**: Chunks are NOT individually framed. The header specifies the total body length, and the body bytes follow contiguously.

4. **Maximum size**: There is no protocol-level limit on text size. The 8-byte header supports up to 2^64 - 1 bytes (~18 exabytes). The Android client reads and discards bodies larger than 400,000 bytes (they can't fit on the Android clipboard) so the stream stays in sync, and treats a length of 2^63 or more as a protocol error and reconnects.

5. **Encoding**: All text MUST be encoded as UTF-8.

6. **Empty messages**: A length of 0 is valid and has no body. The Mac daemon never sends one (empty clipboards are ignored).

## Connection Lifecycle

```
1. Mac daemon starts TCP server on random port
2. Mac daemon advertises service via mDNS
3. Android discovers service, resolves IP + port
4. Android connects via TCP
5. Connection stays open (persistent)
6. On each clipboard change, Mac sends a message (header + body)
7. If connection drops, Android reconnects with exponential backoff
8. On shutdown, Mac unregisters mDNS service and closes all connections
```

## Deduplication

The Mac daemon tracks the SHA-256 hash of the last sent text. If the clipboard content hasn't changed (same hash), no message is sent. This prevents duplicate sends from polling.
