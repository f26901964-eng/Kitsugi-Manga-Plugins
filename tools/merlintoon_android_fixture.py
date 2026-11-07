#!/usr/bin/env python3
"""Local synthetic site for exercising a disposable emulator, not a live-site test."""
import base64
import json
import os
import ssl
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

PNG = base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/fZkAAAAASUVORK5CYII=')
BASE = 'https://merlintoon.com'

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if path.startswith('/wp-json/initmanga/v1/chapters'):
            body = json.dumps({'items': [{'title': 'Bölüm 1', 'number': 1, 'slug': 'bolum-1', 'created_at': '2026-01-01 00:00:00', 'lock_type': 'none', 'is_purchased': True}], 'total_pages': 1}).encode()
            mime = 'application/json'
        elif path.startswith('/search/'):
            body = f'<article><h2><a href="{BASE}/seri/buyucu-coban/">Buyucu Coban</a></h2></article>'.encode()
            mime = 'text/html'
        elif path == '/seri/buyucu-coban/':
            body = f'<html><head><title>Buyucu Coban</title><meta property="og:image" content="{BASE}/cover.png"></head><body class="postid-123"><h1>Buyucu Coban</h1><div id="manga-description">Test</div></body></html>'.encode()
            mime = 'text/html'
        elif path == '/seri/buyucu-coban/bolum-1/':
            # An intentionally empty reader reproduces the reported empty-list symptom
            # only if an old source returns empty; v1.6.11 must instead throw its own error.
            body = b'<html><body><div id="chapter-content"></div></body></html>'
            mime = 'text/html'
        elif path.endswith('.png'):
            body = PNG
            mime = 'image/png'
        else:
            body = f'<html><body><div class="manga-card"><a href="{BASE}/seri/buyucu-coban/"><img src="{BASE}/cover.png"><h3>Buyucu Coban</h3></a></div></body></html>'.encode()
            mime = 'text/html'
        self.send_response(200)
        self.send_header('Content-Type', mime + ('; charset=utf-8' if mime.startswith('text/') else ''))
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        print('SYNTHETIC_FIXTURE:', fmt % args, flush=True)

if __name__ == '__main__':
    server = ThreadingHTTPServer(('127.0.0.1', 8443), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(os.environ['FIXTURE_CERT'], os.environ['FIXTURE_KEY'])
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    server.serve_forever()
