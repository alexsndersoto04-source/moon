"""
Zero-dependency asyncio HTTP + WebSocket (RFC 6455) server & client.
Allows Redmi 9C (Director), Redmi 13C (Worker), and any mobile browser
to communicate over local Wi-Fi with zero external packages.
"""

import asyncio
import base64
import hashlib
import os
import struct
from typing import Optional, Tuple

WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"


async def read_ws_frame(reader: asyncio.StreamReader) -> Optional[Tuple[int, bytes]]:
    """Read a single WebSocket frame. Returns (opcode, payload) or None on EOF."""
    try:
        header = await reader.readexactly(2)
    except asyncio.IncompleteReadError:
        return None

    b1, b2 = header[0], header[1]
    opcode = b1 & 0x0F
    masked = (b2 & 0x80) != 0
    length = b2 & 0x7F

    if length == 126:
        ext = await reader.readexactly(2)
        length = struct.unpack("!H", ext)[0]
    elif length == 127:
        ext = await reader.readexactly(8)
        length = struct.unpack("!Q", ext)[0]

    mask_key = await reader.readexactly(4) if masked else b""
    payload = await reader.readexactly(length) if length > 0 else b""

    if masked:
        payload = bytes(payload[i] ^ mask_key[i % 4] for i in range(length))

    return opcode, payload


def build_ws_frame(payload: bytes, opcode: int = 0x1, mask: bool = False) -> bytes:
    """Build a WebSocket frame (text=0x1, close=0x8, pong=0xA)."""
    header = bytearray([0x80 | opcode])
    length = len(payload)
    mask_bit = 0x80 if mask else 0x00

    if length < 126:
        header.append(mask_bit | length)
    elif length < 65536:
        header.append(mask_bit | 126)
        header.extend(struct.pack("!H", length))
    else:
        header.append(mask_bit | 127)
        header.extend(struct.pack("!Q", length))

    if mask:
        mask_key = os.urandom(4)
        header.extend(mask_key)
        masked_payload = bytes(payload[i] ^ mask_key[i % 4] for i in range(length))
        return bytes(header) + masked_payload

    return bytes(header) + payload


async def send_ws_text(writer: asyncio.StreamWriter, text: str, mask: bool = False) -> None:
    frame = build_ws_frame(text.encode("utf-8"), opcode=0x1, mask=mask)
    writer.write(frame)
    await writer.drain()


def compute_ws_accept(key: str) -> str:
    digest = hashlib.sha1((key.strip() + WS_MAGIC).encode("utf-8")).digest()
    return base64.b64encode(digest).decode("utf-8")


async def connect_ws_client(host: str, port: int, path: str = "/ws") -> Tuple[asyncio.StreamReader, asyncio.StreamWriter]:
    """Connect as a WebSocket client (used by Redmi 13C Worker)."""
    reader, writer = await asyncio.open_connection(host, port)
    sec_key = base64.b64encode(os.urandom(16)).decode("utf-8")
    req = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        f"Upgrade: websocket\r\n"
        f"Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {sec_key}\r\n"
        f"Sec-WebSocket-Version: 13\r\n\r\n"
    )
    writer.write(req.encode("utf-8"))
    await writer.drain()

    # Read HTTP 101 response headers
    while True:
        line = await reader.readline()
        if not line or line == b"\r\n":
            break
    return reader, writer
