#!/usr/bin/env python3
"""Restrictive CONNECT proxy for a disposable Android emulator's synthetic site.

Only merlintoon.com:443 is forwarded, to the local synthetic fixture. No live
content or user traffic is proxied. Run only in the isolated test runner.
"""
import select
import socket
import socketserver


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        request = b''
        while b'\r\n\r\n' not in request and len(request) < 16384:
            part = self.request.recv(4096)
            if not part:
                return
            request += part
        line = request.split(b'\r\n', 1)[0].decode('ascii', errors='replace')
        print('SYNTHETIC_PROXY:', line, flush=True)
        if line.upper() != 'CONNECT MERLINTOON.COM:443 HTTP/1.1':
            self.request.sendall(b'HTTP/1.1 502 Synthetic fixture only\r\nContent-Length: 0\r\n\r\n')
            return
        with socket.create_connection(('127.0.0.1', 8443), timeout=5) as upstream:
            self.request.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
            self.request.settimeout(None)
            upstream.settimeout(None)
            sockets = (self.request, upstream)
            while True:
                ready, _, _ = select.select(sockets, [], [], 30)
                if not ready:
                    return
                for source in ready:
                    data = source.recv(65536)
                    if not data:
                        return
                    (upstream if source is self.request else self.request).sendall(data)


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == '__main__':
    Server(('0.0.0.0', 8888), Handler).serve_forever()
