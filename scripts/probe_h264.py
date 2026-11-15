#!/usr/bin/env python3
"""Measure how smoothly the H.264 stream (/cam.ts) arrives, without OBS or a decoder.

Usage: probe_h264.py HOST [SECONDS] [PORT]

Connects like a player would and, for every frame, compares its arrival time with the frame's own
timestamp (the PTS in the MPEG-TS). The best frame defines "no delay"; everything else is reported as
milliseconds behind it, so bursts and stalls on the link show up as large numbers. It also reports the
gaps between frames and how many of them coincide with keyframes.

Example: python3 scripts/probe_h264.py 192.168.1.2 20
"""
import socket
import statistics
import sys
import time


def main() -> None:
    host = sys.argv[1]
    secs = float(sys.argv[2]) if len(sys.argv) > 2 else 20.0
    port = int(sys.argv[3]) if len(sys.argv) > 3 else 8765

    s = socket.create_connection((host, port))
    s.settimeout(5)
    s.sendall(b"GET /cam.ts HTTP/1.0\r\n\r\n")

    buf = b""
    t0 = time.monotonic()
    frames = []            # (arrival seconds, pts seconds, keyframe)
    sizes = {}             # frame index -> bytes
    current = None
    header_done = False
    while time.monotonic() - t0 < secs:
        chunk = s.recv(65536)
        if not chunk:
            break
        now = time.monotonic()
        buf += chunk
        if not header_done:
            end = buf.find(b"\r\n\r\n")
            if end < 0:
                continue
            buf = buf[end + 4:]
            header_done = True
        while len(buf) >= 188:           # one network read may hold many TS packets: they share an arrival time
            pkt, buf = buf[:188], buf[188:]
            if pkt[0] != 0x47:
                sys.exit("lost TS sync")
            pid = ((pkt[1] & 0x1F) << 8) | pkt[2]
            if pid != 0x100:
                continue
            if pkt[1] & 0x40:            # a new frame (PES) starts
                current = len(frames)
                key = bool(pkt[3] & 0x20 and pkt[5] & 0x40)     # random access indicator
                off = 4 + (1 + pkt[4] if pkt[3] & 0x20 else 0)
                pes = pkt[off:]
                if pes[:3] == b"\x00\x00\x01" and pes[7] & 0x80:
                    b = pes[9:14]
                    pts = (((b[0] >> 1) & 7) << 30) | (b[1] << 22) | ((b[2] >> 1) << 15) | (b[3] << 7) | (b[4] >> 1)
                    frames.append((now - t0, pts / 90000.0, key))
            sizes[current] = sizes.get(current, 0) + 188

    if len(frames) < 10:
        sys.exit("too few frames received")
    offset = [a - p for a, p, _ in frames]
    behind = [(o - min(offset)) * 1000 for o in offset]
    ordered = sorted(behind)
    gaps = [frames[i + 1][0] - frames[i][0] for i in range(len(frames) - 1)]
    big = [i for i, g in enumerate(gaps) if g > 0.1]
    keys = [i for i, f in enumerate(frames) if f[2]]
    print(f"frames: {len(frames)} in {frames[-1][0]:.1f}s")
    print(f"arrival delay vs best frame (ms): median {statistics.median(behind):.0f}, "
          f"p95 {ordered[int(0.95 * len(ordered))]:.0f}, max {max(behind):.0f}")
    print(f"gaps over 100 ms between frames: {len(big)} (largest {max(gaps) * 1000:.0f} ms)")
    if keys:
        kb = statistics.mean(sizes.get(i, 0) for i in keys) / 1024
        db = statistics.mean(sizes.get(i, 0) for i in range(len(frames)) if i not in set(keys)) / 1024
        print(f"keyframes: {len(keys)} (average {kb:.0f} KB; other frames {db:.0f} KB); "
              f"stalls that start right after a keyframe: {sum(frames[i][2] for i in big)} of {len(big)}")


if __name__ == "__main__":
    main()
