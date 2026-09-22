"""The Open Headunit USB display protocol, version 1: the pure half of the reference receiver.

See ../USB-DISPLAY-PROTOCOL.md. The byte vectors in test_ohud_protocol.py are the same ones the
app's UsbDisplayProtocolTest checks, so the two ends cannot drift apart unnoticed.
"""

import struct

INTERFACE_CLASS = 0xFF
INTERFACE_SUBCLASS = 0x4F  # 'O'
INTERFACE_PROTOCOL = 0x44  # 'D'

REQ_GET_INFO = 0x01
REQ_START = 0x02
REQ_STOP = 0x03

VERSION = 1
CODEC_H264 = 0x01
INFO_FLAG_KEYFRAME_REQUESTS = 0x01
INFO_LENGTH = 32

HEADER = struct.Struct("<4sIIHH")  # magic, payload length, timestamp ms, flags, reserved
FRAME_MAGIC = b"OHUF"
INFO_MAGIC = b"OHUD"
FRAME_FLAG_KEYFRAME = 0x01
FRAME_FLAG_CONFIG = 0x02

DEVICE_REQUEST_KEYFRAME = b"\x01"

# Anything larger is a corrupt header, not a frame: 1080p keyframes stay well under this.
MAX_PAYLOAD = 8 * 1024 * 1024


# The sizes Android Auto encodes, in the order the app prefers them (AuxDisplayProfilePolicy).
STREAM_SIZES = [(800, 480), (1280, 720), (1920, 1080), (720, 1280), (1080, 1920)]


def stream_size(width, height):
    """The stream the head unit asks the phone for: the smallest standard size holding the panel.

    The picture is the panel's size at the stream's top-left; the rest is blank margin.
    """
    holding = [s for s in STREAM_SIZES if s[0] >= width and s[1] >= height]
    if holding:
        return min(holding, key=lambda s: s[0] * s[1])
    return max(STREAM_SIZES, key=lambda s: s[0] * s[1])


def encode_info(width, height, dpi, max_fps=30, keyframe_requests=True):
    """The GET_INFO answer: 14 meaningful bytes, padded to 32."""
    flags = INFO_FLAG_KEYFRAME_REQUESTS if keyframe_requests else 0
    body = INFO_MAGIC + struct.pack("<BBHHHBB", VERSION, CODEC_H264, width, height, dpi, max_fps, flags)
    return body.ljust(INFO_LENGTH, b"\x00")


class Frame:
    __slots__ = ("payload", "timestamp_ms", "keyframe", "config")

    def __init__(self, payload, timestamp_ms, flags):
        self.payload = payload
        self.timestamp_ms = timestamp_ms
        self.keyframe = bool(flags & FRAME_FLAG_KEYFRAME)
        self.config = bool(flags & FRAME_FLAG_CONFIG)


class FrameParser:
    """Turns bulk OUT reads, split or merged however USB delivered them, back into frames.

    Bytes that do not start with the magic are skipped up to the next one, and `resyncs` counts how
    often that happened, since a resync means a picture was lost and a keyframe is worth asking for.
    """

    def __init__(self):
        self._buf = bytearray()
        self.resyncs = 0

    def feed(self, data):
        self._buf += data
        frames = []
        while True:
            if len(self._buf) < HEADER.size:
                return frames
            if self._buf[:4] != FRAME_MAGIC:
                self._skip_to_magic()
                continue
            _, length, timestamp, flags, _ = HEADER.unpack_from(self._buf)
            if length > MAX_PAYLOAD:
                del self._buf[:4]
                self._skip_to_magic()
                continue
            end = HEADER.size + length
            if len(self._buf) < end:
                return frames
            frames.append(Frame(bytes(self._buf[HEADER.size:end]), timestamp, flags))
            del self._buf[:end]

    def _skip_to_magic(self):
        self.resyncs += 1
        at = self._buf.find(FRAME_MAGIC, 1)
        if at < 0:
            # Keep a possible partial magic at the tail.
            del self._buf[:max(0, len(self._buf) - 3)]
        else:
            del self._buf[:at]
