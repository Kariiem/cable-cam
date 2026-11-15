#!/usr/bin/env python3
"""Measure how smoothly the Motion JPEG stream (/cam.mjpg) arrives, without OBS or a decoder.

Usage: probe_mjpeg.py HOST [SECONDS] [PORT]

Reads the multipart stream, and reports the picture rate, the bandwidth, the size of a picture and the
gaps between pictures. Even pictures every 1/fps seconds (small gaps) mean a smooth link.

Example: python3 scripts/probe_mjpeg.py 192.168.1.2 20
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
    s.sendall(b"GET /cam.mjpg HTTP/1.0\r\n\r\n")

    buf = b""
    t0 = time.monotonic()
    arrivals, sizes = [], []
    while time.monotonic() - t0 < secs:
        chunk = s.recv(262144)
        if not chunk:
            break
        buf += chunk
        while True:
            at = buf.find(b"Content-Length: ")
            if at < 0:
                break
            body = buf.find(b"\r\n\r\n", at)
            if body < 0:
                break
            length = int(buf[at + 16:buf.find(b"\r\n", at)])
            if len(buf) < body + 4 + length:
                break
            arrivals.append(time.monotonic() - t0)
            sizes.append(length)
            buf = buf[body + 4 + length:]

    if len(arrivals) < 10:
        sys.exit("too few pictures received")
    gaps = [arrivals[i + 1] - arrivals[i] for i in range(len(arrivals) - 1)]
    span = arrivals[-1] - arrivals[0]
    print(f"pictures: {len(arrivals)} in {span:.1f}s = {len(arrivals) / span:.1f} fps, "
          f"{sum(sizes) * 8 / span / 1e6:.1f} Mbit/s, average {statistics.mean(sizes) / 1024:.0f} KB")
    print(f"gaps: median {statistics.median(gaps) * 1000:.0f} ms, p95 {sorted(gaps)[int(0.95 * len(gaps))] * 1000:.0f} ms, "
          f"max {max(gaps) * 1000:.0f} ms, over 100 ms: {sum(g > 0.1 for g in gaps)}")


if __name__ == "__main__":
    main()
