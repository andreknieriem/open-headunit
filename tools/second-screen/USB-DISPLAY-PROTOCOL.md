# Open Headunit USB display protocol, version 1

A small, open protocol for putting Android Auto's second stream (an instrument cluster or auxiliary
display) on a screen you build yourself. The head unit only forwards H.264; the display decodes it.
Nothing here needs root or a kernel driver on the head unit: it is plain Android USB Host API.

The reference implementation of the device side is `pi-receiver/` (a Raspberry Pi as a USB gadget).
The host side is `secondscreen/usbdisplay/` in the app. Both test the same byte vectors
(`UsbDisplayProtocolTest.kt`, `pi-receiver/test_ohud_protocol.py`).

## Roles

- **Host**: the head unit running Open Headunit, in USB host mode.
- **Device**: your display. Anything that can be a USB device and decode H.264 baseline: a
  Raspberry Pi Zero 2 W, a Pi 4 or 5 on its USB-C port, another single-board computer with an OTG
  port, or a microcontroller paired with a decoder chip.

## Identification

The device exposes one interface with:

| Field | Value |
|---|---|
| `bInterfaceClass` | `0xFF` (vendor specific) |
| `bInterfaceSubClass` | `0x4F` (`'O'`) |
| `bInterfaceProtocol` | `0x44` (`'D'`) |
| `iInterface` | should name `Open Headunit Display` |

The host finds the display **by this triple, never by VID/PID**, so any ids work. The interface
has one **bulk OUT** endpoint (frames) and optionally one **bulk or interrupt IN** endpoint
(keyframe requests). Other interfaces on the same device are ignored.

## Control requests

All are vendor requests to the interface: `wIndex` is the interface number, `wValue` is 0.

| Request | `bmRequestType` | `bRequest` | Data |
|---|---|---|---|
| `GET_INFO` | `0xC1` (IN) | `0x01` | device answers up to 32 bytes, below |
| `START` | `0x41` (OUT) | `0x02` | none. A session begins: reset the decoder, expect SPS/PPS then a keyframe |
| `STOP` | `0x41` (OUT) | `0x03` | none. The session ended: show an idle screen if you like |

`GET_INFO` answer, little-endian, at least 14 bytes (pad to 32 with zeros):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `OHUD` |
| 4 | 1 | protocol version, `1` |
| 5 | 1 | codecs, bit 0 = H.264 (required) |
| 6 | 2 | panel width in pixels |
| 8 | 2 | panel height in pixels |
| 10 | 2 | density in dpi that Android Auto should lay out at (160 for about 7 inches at 1024x600) |
| 12 | 1 | max frame rate, 30 or 60 |
| 13 | 1 | flags, bit 0 = the device sends keyframe requests on its IN endpoint |

The host asks for a stream that fits the panel. Android Auto only encodes a few sizes (800x480,
1280x720, 1920x1080 and their portrait forms), so the stream can be bigger than the panel: **the
picture is at the top-left of each frame, exactly the panel's size, and the rest is blank margin.**
Crop it rather than scaling the whole frame.

The host reads `GET_INFO` when the user taps "Allow access and read the USB display" in settings and
again before each connection, and remembers the last answer. The size is sent to the phone once per
Android Auto session, so a panel change applies at the next connection.

## Frames (bulk OUT)

Each H.264 access unit is sent as a 16-byte header followed by the unit in Annex-B form (start
codes included):

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | magic `OHUF` |
| 4 | 4 | payload length in bytes |
| 8 | 4 | timestamp, milliseconds on the host's session clock (wraps) |
| 12 | 2 | flags: bit 0 = contains a keyframe (IDR), bit 1 = contains SPS/PPS |
| 14 | 2 | reserved, 0 |

Transport rules:

- The header goes in its own transfer. The payload follows in transfers of at most 16384 bytes.
  When the payload length is a multiple of the endpoint's max packet size, the host ends it with a
  zero-length packet, so a device read of 16384 bytes always returns at the end of a frame.
- The device must treat the endpoint as a byte stream: reassemble by the length field, and accept a
  read that holds part of a frame or several frames.
- On a bad magic, discard bytes up to the next `OHUF` and ask for a keyframe.
- After `START`, the first unit is SPS/PPS (flag bit 1), sent from the host's cache, then the next
  keyframe. A slow device is never waited for: the host keeps a bounded queue (90 units or 4 MiB)
  and, when it overflows, empties it and resumes at the next keyframe.

## Keyframe requests (IN, optional)

Any transfer on the IN endpoint whose first byte is `0x01` asks for a keyframe. Send one after
`START`, after a decoder restart, and after a resync. The host polls every 500 ms and turns it into
a video-focus cycle on the second channel only, at most once every 5 seconds; the driver's main
picture is never touched. Without this endpoint the display waits for the phone's next keyframe.

## Versioning

A future version raises the byte at offset 4 and only appends fields. A host that sees a version it
does not know uses the fields it does. Everything above is version 1.
