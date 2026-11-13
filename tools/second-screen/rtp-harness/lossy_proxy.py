#!/usr/bin/env python3
"""Forwards RTP datagrams from one UDP port to another, and loses some on purpose.

Times are seconds from the first datagram. --drop-at loses a burst, --start-at loses
everything before it (a receiver that starts late), --loss loses at random, and
--rcvbuf with --drain-pps copies a slow receiver with a small socket buffer, where
the kernel itself drops what does not fit.
"""
import argparse
import random
import socket
import sys
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, required=True)
    ap.add_argument("--forward", required=True, help="host:port")
    ap.add_argument("--drop-at", type=float, action="append", default=[])
    ap.add_argument("--drop-count", type=int, default=5)
    ap.add_argument("--start-at", type=float, default=0.0)
    ap.add_argument("--loss", type=float, default=0.0)
    ap.add_argument("--rcvbuf", type=int, default=0)
    ap.add_argument("--drain-pps", type=float, default=0.0)
    ap.add_argument("--idle", type=float, default=4.0)
    ap.add_argument("--seed", type=int, default=1)
    a = ap.parse_args()

    host, port = a.forward.rsplit(":", 1)
    dest = (host, int(port))
    rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    if a.rcvbuf:
        rx.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, a.rcvbuf)
    rx.bind(("127.0.0.1", a.listen))
    rx.settimeout(30.0)
    tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    rng = random.Random(a.seed)
    pending = sorted(a.drop_at)
    burst_left = 0
    t0 = None
    seen = forwarded = dropped = 0
    print(f"PROXY listening={a.listen} forward={a.forward} rcvbuf={rx.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)}", flush=True)
    while True:
        try:
            data = rx.recv(65536)
        except socket.timeout:
            break
        now = time.monotonic()
        if t0 is None:
            t0 = now
            rx.settimeout(a.idle)
        t = now - t0
        seen += 1
        if pending and t >= pending[0]:
            pending.pop(0)
            burst_left = a.drop_count
        lose = t < a.start_at or burst_left > 0 or (a.loss and rng.random() < a.loss)
        if burst_left > 0:
            burst_left -= 1
        if lose:
            dropped += 1
        else:
            tx.sendto(data, dest)
            forwarded += 1
        if a.drain_pps:
            time.sleep(1.0 / a.drain_pps)
    print(f"PROXY seen={seen} forwarded={forwarded} dropped={dropped}", flush=True)


if __name__ == "__main__":
    sys.exit(main())
