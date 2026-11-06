#!/usr/bin/env python3
"""Read-only live source diagnostics; never needs a user account or cookies."""
import json
import urllib.request
import urllib.error
from html.parser import HTMLParser
from pathlib import Path


class Images(HTMLParser):
    def __init__(self):
        super().__init__()
        self.images = []

    def handle_starttag(self, tag, attrs):
        if tag == 'img':
            self.images.append(dict(attrs).get('src', ''))


def get(url):
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0', 'Referer': 'https://mangtto.com/',
    })
    try:
        with urllib.request.urlopen(req, timeout=35) as r:
            return r.status, r.read(), dict(r.headers)
    except urllib.error.HTTPError as e:
        return e.code, e.read(), dict(e.headers)


def main():
    out = []
    for path in ['/api/manga/chainsaw-man', '/api/manga/chainsaw-man/chapters?skip=0&take=50',
                 '/api/manga/chainsaw-man/2', '/manga/chainsaw-man/2', '/manga/berserk/0.01']:
        row = {'path': path}
        try:
            status, body, headers = get('https://mangtto.com' + path)
            row.update(status=status, bytes=len(body), content_type=headers.get('Content-Type'))
            try:
                data = json.loads(body)
                row['keys'] = list(data) if isinstance(data, dict) else None
                if isinstance(data, dict):
                    row['slug'] = data.get('slug')
                    row['total'] = data.get('total')
                    row['chapters'] = data.get('chapters', [])[:3]
                    row['cdn'] = data.get('cdn')
                    row['uploads'] = [{k: x.get(k) for k in ('fansubId', 'fileLength')} for x in data.get('uploads', [])]
            except (ValueError, TypeError):
                parser = Images()
                parser.feed(body.decode('utf-8', 'replace'))
                images = [u for u in parser.images if '/manga/' in u]
                row['reader_image_count'] = len(images)
                row['first_image'] = images[0] if images else None
                if images:
                    image_status, image_body, image_headers = get(images[0])
                    row['first_image_status'] = image_status
                    row['first_image_bytes'] = len(image_body)
                    row['first_image_content_type'] = image_headers.get('Content-Type')
        except Exception as e:
            row['error'] = str(e)
        out.append(row)
    fixture = Images()
    fixture.feed(Path('docs/mangitto/reader.html').read_text())
    urls = [u for u in fixture.images if '/manga/berserk/0.01/' in u]
    for index in (0, 45, 91):
        row = {'fixture_image_index': index, 'url': urls[index]}
        try:
            status, body, headers = get(urls[index])
            row.update(status=status, bytes=len(body), content_type=headers.get('Content-Type'), magic=body[:12].hex())
        except Exception as e:
            row['error'] = str(e)
        out.append(row)
    target = Path('reports/mangitto/live-diagnosis.json')
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(out, indent=2, ensure_ascii=False) + '\n')
    print(target.read_text())


if __name__ == '__main__':
    main()
