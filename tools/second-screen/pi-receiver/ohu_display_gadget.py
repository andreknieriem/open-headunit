#!/usr/bin/env python3
"""Reference receiver for the Open Headunit USB display protocol, on a Linux USB gadget.

Run setup-gadget.sh first: it creates the gadget, mounts FunctionFS and then starts this script,
which writes the USB descriptors, binds the gadget to the port, answers GET_INFO with the panel's
size and pipes every H.264 frame into a decoder that draws on the panel.

Needs Python 3 and a decoder command (gstreamer by default). No extra Python packages.
"""

import argparse
import ctypes
import os
import queue
import shlex
import struct
import subprocess
import sys
import threading
import time

import ohud_protocol as p

# linux/usb/functionfs.h
FUNCTIONFS_DESCRIPTORS_MAGIC_V2 = 3
FUNCTIONFS_STRINGS_MAGIC = 2
FUNCTIONFS_HAS_FS_DESC = 1
FUNCTIONFS_HAS_HS_DESC = 2
EVENT = struct.Struct("<BBHHHB3x")  # usb_ctrlrequest, then the event type
EVENT_BIND, EVENT_UNBIND, EVENT_ENABLE, EVENT_DISABLE, EVENT_SETUP = 0, 1, 2, 3, 4

INTERFACE_NAME = "Open Headunit Display"


def default_sink(width, height):
    """Hardware decode straight to the panel, cropping the blank margin when the stream is bigger."""
    stream_w, stream_h = p.stream_size(width, height)
    crop = ""
    if (stream_w, stream_h) != (width, height):
        crop = " ! videocrop right=%d bottom=%d" % (max(0, stream_w - width), max(0, stream_h - height))
    return "gst-launch-1.0 -q fdsrc fd=0 ! h264parse ! v4l2h264dec" + crop + " ! kmssink sync=false"


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def descriptors():
    """One vendor interface (FF/4F/44) with bulk OUT for frames and bulk IN for keyframe requests."""
    def interface():
        return struct.pack("<BBBBBBBBB", 9, 4, 0, 0, 2, p.INTERFACE_CLASS, p.INTERFACE_SUBCLASS,
                           p.INTERFACE_PROTOCOL, 1)

    def endpoint(address, max_packet):
        return struct.pack("<BBBBHB", 7, 5, address, 2, max_packet, 0)

    full_speed = interface() + endpoint(0x01, 64) + endpoint(0x82, 64)
    high_speed = interface() + endpoint(0x01, 512) + endpoint(0x82, 512)
    body = struct.pack("<II", 3, 3) + full_speed + high_speed
    flags = FUNCTIONFS_HAS_FS_DESC | FUNCTIONFS_HAS_HS_DESC
    return struct.pack("<III", FUNCTIONFS_DESCRIPTORS_MAGIC_V2, 12 + len(body), flags) + body


def strings():
    body = struct.pack("<H", 0x0409) + INTERFACE_NAME.encode() + b"\x00"
    return struct.pack("<IIII", FUNCTIONFS_STRINGS_MAGIC, 16 + len(body), 1, 1) + body


_libc = ctypes.CDLL(None, use_errno=True)


def ack_status_stage(fd):
    """A control OUT request with no data is acknowledged by a zero-length read of ep0."""
    _libc.read(fd, None, 0)


class Decoder:
    """The decoder process, restarted for every session so it starts clean."""

    def __init__(self, command):
        self.command = shlex.split(command)
        self.process = None

    def restart(self):
        self.stop()
        log("starting decoder: " + " ".join(self.command))
        self.process = subprocess.Popen(self.command, stdin=subprocess.PIPE)

    def write(self, payload):
        if self.process is None or self.process.poll() is not None:
            return False
        try:
            self.process.stdin.write(payload)
            self.process.stdin.flush()
            return True
        except (BrokenPipeError, OSError):
            return False

    def stop(self):
        if self.process is None:
            return
        try:
            self.process.stdin.close()
            self.process.terminate()
            self.process.wait(timeout=3)
        except Exception:
            self.process.kill()
        self.process = None


class Receiver:
    def __init__(self, args):
        self.args = args
        self.info = p.encode_info(args.width, args.height, args.dpi, args.fps, keyframe_requests=True)
        self.decoder = Decoder(args.sink or default_sink(args.width, args.height))
        self.keyframe_requests = queue.Queue(maxsize=1)

    def ask_for_keyframe(self, why):
        try:
            self.keyframe_requests.put_nowait(why)
        except queue.Full:
            pass  # one request already waiting is enough

    def keyframe_writer(self, ep_in):
        # The write blocks until the host polls the IN endpoint, which it does twice a second.
        while True:
            why = self.keyframe_requests.get()
            try:
                os.write(ep_in, p.DEVICE_REQUEST_KEYFRAME)
                log("asked the head unit for a keyframe (" + why + ")")
            except OSError as e:
                log("keyframe request not delivered: " + str(e))

    def frame_reader(self, ep_out):
        parser = p.FrameParser()
        frames = 0
        resyncs = 0
        while True:
            try:
                data = os.read(ep_out, 16384)
            except OSError as e:
                # The endpoint goes away while the host is detached; wait for it to come back.
                log("frame endpoint: " + str(e))
                time.sleep(0.5)
                continue
            for frame in parser.feed(data):
                if not self.decoder.write(frame.payload):
                    self.decoder.restart()
                    self.ask_for_keyframe("the decoder restarted")
                    continue
                frames += 1
                if frames % 300 == 0:
                    log("%d frames shown" % frames)
            if parser.resyncs != resyncs:
                resyncs = parser.resyncs
                self.ask_for_keyframe("the stream lost sync")

    def handle_setup(self, ep0, request_type, request, value, index, length):
        is_in = request_type & 0x80
        if request == p.REQ_GET_INFO and is_in:
            os.write(ep0, self.info[:length])
            log("head unit read the display info (%dx%d at %d dpi)" % (self.args.width, self.args.height, self.args.dpi))
        elif request == p.REQ_START and not is_in:
            ack_status_stage(ep0)
            log("session started")
            self.decoder.restart()
            self.ask_for_keyframe("a session started")
        elif request == p.REQ_STOP and not is_in:
            ack_status_stage(ep0)
            log("session stopped")
            self.decoder.stop()
        else:
            # Answering in the wrong direction is how FunctionFS stalls an unknown request.
            try:
                if is_in:
                    _libc.read(ep0, None, 0)
                else:
                    os.write(ep0, b"")
            except OSError:
                pass

    def run(self):
        ffs = self.args.ffs
        ep0 = os.open(os.path.join(ffs, "ep0"), os.O_RDWR)
        os.write(ep0, descriptors())
        os.write(ep0, strings())
        ep_out = os.open(os.path.join(ffs, "ep1"), os.O_RDONLY)
        ep_in = os.open(os.path.join(ffs, "ep2"), os.O_WRONLY)
        if self.args.udc_path:
            udc = self.args.udc or sorted(os.listdir("/sys/class/udc"))[0]
            with open(self.args.udc_path, "w") as f:
                f.write(udc)
            log("bound to " + udc)
        threading.Thread(target=self.frame_reader, args=(ep_out,), daemon=True).start()
        threading.Thread(target=self.keyframe_writer, args=(ep_in,), daemon=True).start()
        log("waiting for the head unit (%dx%d)" % (self.args.width, self.args.height))
        while True:
            data = os.read(ep0, EVENT.size * 4)
            for at in range(0, len(data) - EVENT.size + 1, EVENT.size):
                request_type, request, value, index, length, kind = EVENT.unpack_from(data, at)
                if kind == EVENT_SETUP:
                    self.handle_setup(ep0, request_type, request, value, index, length)
                elif kind == EVENT_ENABLE:
                    log("connected to the head unit")
                elif kind == EVENT_DISABLE:
                    log("disconnected from the head unit")
                    self.decoder.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--ffs", default="/dev/ffs-ohud", help="where FunctionFS is mounted")
    parser.add_argument("--width", type=int, default=1024, help="the panel's width in pixels")
    parser.add_argument("--height", type=int, default=600, help="the panel's height in pixels")
    parser.add_argument("--dpi", type=int, default=160, help="the density Android Auto lays out at")
    parser.add_argument("--fps", type=int, default=30, choices=(30, 60))
    parser.add_argument("--sink", default="", help="decoder command reading H.264 on stdin "
                        "(default: gstreamer hardware decode to the panel)")
    parser.add_argument("--udc-path", default="", help="the gadget's UDC file, to bind once ready")
    parser.add_argument("--udc", default="", help="which UDC to bind (default: the first)")
    args = parser.parse_args()
    try:
        Receiver(args).run()
    except KeyboardInterrupt:
        sys.exit(0)


if __name__ == "__main__":
    main()
