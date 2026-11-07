#!/usr/bin/env python3
"""Local synthetic site for exercising a disposable emulator, not a live-site test."""
import json
import os
import ssl
import struct
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit

def png_chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


# A valid small RGB PNG; no actual manga artwork is included.
PNG = (b'\x89PNG\r\n\x1a\n' + png_chunk(b'IHDR', struct.pack('>IIBBBBB', 8, 8, 8, 2, 0, 0, 0))
       + png_chunk(b'IDAT', zlib.compress((b'\x00' + b'\x55\x88\xaa' * 8) * 8)) + png_chunk(b'IEND', b''))
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
            # Both variants are synthetic. The empty case probes error propagation;
            # the full case probes actual page loading using test-only image bytes.
            if os.environ.get('FIXTURE_READER') == 'single-empty':
                images = ''
            else:
                images = ''.join(f'<img src="{BASE}/pages/{i:04}.png">' for i in range(1, 4))
            body = f'<html><body><div id="chapter-content" class="image-based">{images}</div></body></html>'.encode()
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
