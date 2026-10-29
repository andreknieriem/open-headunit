#!/usr/bin/env python3
"""Receives an RTP H.264 push and grades it the way a strict receiver sees it.

The picture is good from the first IDR that arrives whole, with SPS and PPS known, and
broken from any lost datagram until the next whole IDR. That is how a decoder that
freezes on a reference error behaves, which a forgiving one such as FFmpeg hides.
Prints one GRADE line and writes the same as JSON; --annexb keeps the stream.
"""
import argparse
import json
import socket
import sys
import time


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--bind", default="0.0.0.0")
    ap.add_argument("--idle", type=float, default=4.0)
    ap.add_argument("--first-wait", type=float, default=60.0)
    ap.add_argument("--json")
    ap.add_argument("--annexb")
    ap.add_argument("--label", default="run")
    a = ap.parse_args()

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
    s.bind((a.bind, a.port))
    s.settimeout(a.first_wait)
    out = open(a.annexb, "wb") if a.annexb else None
    print(f"GRADER listening={a.port}", flush=True)

    t0 = last_t = None
    expected = None
    packets = lost = bad_header = 0
    have_sps = have_pps = False
    good = False
    first_good = None
    broken_since = None
    broken = []
    idr_times = []
    au_ts = None
    au_damaged = False
    au_types = set()
    au_nals = []
    fu = None
    fu_type = 0

    def close_au(t):
        nonlocal good, first_good, broken_since, have_sps, have_pps, au_damaged, au_types, au_nals
        if au_ts is None:
            return
        if not au_damaged:
            if 7 in au_types:
                have_sps = True
            if 8 in au_types:
                have_pps = True
            if 5 in au_types:
                idr_times.append(t)
                if have_sps and have_pps and not good:
                    good = True
                    if first_good is None:
                        first_good = t
                    if broken_since is not None:
                        broken.append(t - broken_since)
                        broken_since = None
        if out and not au_damaged:
            for n in au_nals:
                out.write(b"\x00\x00\x00\x01" + n)
        au_damaged = False
        au_types = set()
        au_nals = []

    while True:
        try:
            data = s.recv(65536)
        except socket.timeout:
            break
        now = time.monotonic()
        if t0 is None:
            t0 = now
            s.settimeout(a.idle)
        t = now - t0
        last_t = t
        if len(data) < 13 or (data[0] >> 6) != 2:
            bad_header += 1
            continue
        packets += 1
        marker = data[1] >> 7
        seq = (data[2] << 8) | data[3]
        ts = int.from_bytes(data[4:8], "big")
        payload = data[12:]
        gap = expected is not None and seq != expected
        if gap:
            lost += (seq - expected) & 0xFFFF
            if good:
                good = False
                broken_since = t
            fu = None
        expected = (seq + 1) & 0xFFFF
        if ts != au_ts:
            close_au(t)
            au_ts = ts
        if gap:
            au_damaged = True
        nal_type = payload[0] & 0x1F
        if nal_type == 28:
            fh = payload[1]
            typ = fh & 0x1F
            if fh & 0x80:
                fu = bytearray([(payload[0] & 0xE0) | typ]) + payload[2:]
                fu_type = typ
            elif fu is not None:
                fu += payload[2:]
            else:
                au_damaged = True
            if fh & 0x40 and fu is not None:
                au_types.add(fu_type)
                au_nals.append(bytes(fu))
                fu = None
        elif 1 <= nal_type <= 23:
            au_types.add(nal_type)
            au_nals.append(bytes(payload))
        if marker:
            close_au(t)
            au_ts = None

    close_au(last_t or 0.0)
    unrecovered = 0
    if broken_since is not None:
        broken.append((last_t or 0.0) - broken_since)
        unrecovered = 1
    span = last_t or 0.0
    gaps = [round(b - a_, 2) for a_, b in zip(idr_times, idr_times[1:])]
    res = {
        "label": a.label,
        "packets": packets,
        "lost": lost,
        "bad_header": bad_header,
        "span_s": round(span, 2),
        "idrs": len(idr_times),
        "idr_gap_max_s": max(gaps) if gaps else None,
        "first_picture_s": None if first_good is None else round(first_good, 2),
        "broken_n": len(broken),
        "broken_max_s": round(max(broken), 2) if broken else 0.0,
        "broken_total_s": round(sum(broken), 2),
        "unrecovered": unrecovered,
    }
    print("GRADE " + " ".join(f"{k}={v}" for k, v in res.items()), flush=True)
    if a.json:
        with open(a.json, "w") as f:
            json.dump(res, f)
    if out:
        out.close()


if __name__ == "__main__":
    sys.exit(main())
