#!/usr/bin/env python3
"""UyirMesh internet relay server (Python 3, standard library only).

Phones that have internet upload the packets in their store-and-forward buffer (POST /up) and download the
packets other phones uploaded (GET /down?since=N). Packets are stored exactly as they travel on the mesh: SOS
messages stay encrypted for the rescue base and replies stay signed, so this server cannot read or change them.
It only sees packet ids, types and phone ids.

Run:   python3 server/gateway_server.py --port 8787
Then, in the app: Settings > Internet relay > address http://<this computer's address>:8787
"""
import argparse
import os
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_PACKET = 32_000
MAX_KEEP = 20_000
TYPES = {1: "SOS", 2: "SOS_UPDATE", 3: "SOS_MEDIA", 4: "SOS_CANCEL", 5: "ACK", 6: "ALERT", 7: "CHAT", 8: "SAFE",
         9: "REPORT", 10: "VOLUNTEER", 11: "HEARTBEAT", 12: "TASK", 13: "TASK_STATUS", 14: "TEAM_CHAT",
         15: "FIND_REQUEST", 16: "FIND_RESULT", 17: "MISSING", 18: "PROGRESS", 19: "TEAM_VOICE", 20: "HAZARD",
         21: "POLL", 22: "POLL_ANSWER", 23: "BASE_SYNC", 24: "REVOKE", 25: "SENSOR", 26: "CHILD_FOUND"}

lock = threading.Lock()
packets = []        # list of (index, bytes)
seen = set()        # packet ids (hex) already stored
next_index = 1
stats = {"uploads": 0, "downloads": 0}


def unpack(body):
    out, i = [], 0
    while i + 4 <= len(body):
        (n,) = struct.unpack(">I", body[i:i + 4])
        i += 4
        if n <= 0 or n > MAX_PACKET or i + n > len(body):
            break
        out.append(body[i:i + n])
        i += n
    return out


def valid(p):
    # 'U' 'M' version 1, then type, flags and the 16-byte id
    return len(p) >= 40 and p[0] == 0x55 and p[1] == 0x4D and p[2] == 1


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _send(self, code, body=b"", ctype="text/plain; charset=utf-8", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        global next_index
        if urlparse(self.path).path != "/up":
            return self._send(404, b"not found")
        n = int(self.headers.get("Content-Length", "0"))
        if n <= 0 or n > 5_000_000:
            return self._send(400, b"bad size")
        added = 0
        with lock:
            for p in unpack(self.rfile.read(n)):
                if not valid(p):
                    continue
                pid = p[5:21].hex()
                if pid in seen:
                    continue
                seen.add(pid)
                packets.append((next_index, p))
                next_index += 1
                added += 1
            del packets[:max(0, len(packets) - MAX_KEEP)]
            stats["uploads"] += 1
        self._send(200, ("stored %d\n" % added).encode())

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/down":
            q = parse_qs(url.query)
            since = int(q.get("since", ["0"])[0] or 0)
            most = min(int(q.get("max", ["60"])[0] or 60), 200)
            with lock:
                if since >= next_index:
                    since = 0  # the server was restarted: start again from the oldest packet
                batch = [(i, p) for (i, p) in packets if i > since][:most]
                nxt = batch[-1][0] if batch else since
                stats["downloads"] += 1
            body = b"".join(struct.pack(">I", len(p)) + p for (_, p) in batch)
            return self._send(200, body, "application/octet-stream", {"X-Next": str(nxt)})
        if url.path == "/":
            with lock:
                counts = {}
                for _, p in packets:
                    t = TYPES.get(p[3], str(p[3]))
                    counts[t] = counts.get(t, 0) + 1
                lines = ["UyirMesh internet relay", "packets stored: %d" % len(packets),
                         "uploads: %d, downloads: %d" % (stats["uploads"], stats["downloads"]), ""]
                lines += ["%-12s %d" % (k, v) for k, v in sorted(counts.items())]
            return self._send(200, ("\n".join(lines) + "\n").encode())
        self._send(404, b"not found")


def main():
    ap = argparse.ArgumentParser(description="UyirMesh internet relay server")
    ap.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8787")))
    ap.add_argument("--host", default="0.0.0.0")
    a = ap.parse_args()
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print("UyirMesh relay listening on %s:%d" % (a.host, a.port), flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
