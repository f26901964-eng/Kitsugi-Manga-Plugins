#!/usr/bin/env python3
"""Small public, unauthenticated MerlinToon availability probe for GitHub Actions."""
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import re
import sys

BASE = 'https://merlintoon.com'
HEADERS = {'User-Agent': 'Mozilla/5.0 (compatible; KitsugiSourceProbe/1.0)', 'Referer': BASE + '/'}


def get(path, image=False):
    req = Request(BASE + path if path.startswith('/') else path, headers=HEADERS)
    try:
        with urlopen(req, timeout=30) as response:
            data = response.read(512 if image else 2_000_000)
            return response.status, response.headers.get('Content-Type', ''), data
    except HTTPError as exc:
        return exc.code, exc.headers.get('Content-Type', ''), b''
    except (URLError, OSError) as exc:
        print('request error:', type(exc).__name__, str(exc))
        return 0, '', b''


def main():
    paths = ['/seri/', '/search/tembel/', '/seri/tembel-soylunun-degisimi/',
             '/wp-json/initmanga/v1/chapters?manga_id=31516&per_page=50&paged=1',
             '/seri/tembel-soylunun-degisimi/bolum-1/']
    results = []
    for path in paths:
        status, content_type, data = get(path)
        print(f'{path}: HTTP {status}, {content_type}, {len(data)} bytes')
        results.append(status == 200 and len(data) > 0)
        if path.endswith('/bolum-1/') and status == 200:
            text = data.decode('utf-8', 'replace')
            match = re.search(r'<div[^>]+id=["\']chapter-content["\'][\s\S]*?(?:data-original-src|src)=["\'](https?://[^"\']+)', text)
            if match:
                status, typ, raw = get(match.group(1), True)
                print(f'chapter image: HTTP {status}, {typ}, {len(raw)} bytes')
                results.append(status == 200 and typ.startswith('image/') and not typ.startswith('image/svg'))
            else:
                print('chapter image: not found in HTML')
                results.append(False)
    return 0 if all(results) else 1


if __name__ == '__main__':
    sys.exit(main())
