#!/usr/bin/env python3
"""Read-only diagnostics of Turkmanga's real search and Next.js data."""
import json
import os
import re
from pathlib import Path
from urllib.parse import urljoin
import requests
from bs4 import BeautifulSoup

BASE = 'https://turkmanga.org'
root = Path(os.environ.get('RUNNER_TEMP', '/tmp')) / 'turkmanga-live'
root.mkdir(parents=True, exist_ok=True)
s = requests.Session()
s.headers.update({'User-Agent': 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'})
report = {}


def objects(item):
    if isinstance(item, dict):
        yield item
        for value in item.values(): yield from objects(value)
    elif isinstance(item, list):
        for value in item: yield from objects(value)


def inspect(name, url, params=None):
    try:
        r = s.get(url, params=params, timeout=45)
        (root / (name + '.html')).write_text(r.text, encoding='utf-8')
        doc = BeautifulSoup(r.text, 'html.parser')
        script = doc.select_one('#__NEXT_DATA__')
        next_data = json.loads(script.string or script.get_text()) if script else {}
        found = [o for o in objects(next_data) if isinstance(o.get('slug'), str) and isinstance(o.get('name'), str) and any(k in o for k in ('type', 'finished', 'nsfw', 'description', 'manga_type'))]
        anchors = [{'text': a.get_text(' ', strip=True)[:100], 'href': a.get('href')} for a in doc.select('a[href*="/manga/"]')][:12]
        info = {'url': r.url, 'status': r.status_code, 'content_type': r.headers.get('content-type'), 'length': len(r.content), 'title': doc.title.get_text(strip=True) if doc.title else '', 'next_page': next_data.get('page'), 'next_pageprops_keys': list(next_data.get('props', {}).get('pageProps', {})), 'next_candidates_count': len(found), 'next_candidates': [{'slug': x.get('slug'), 'name': x.get('name')} for x in found[:20]], 'manga_anchors': anchors, 'has_chainsaw_text': 'chainsaw' in r.text.lower(), 'scripts': [urljoin(BASE, x.get('src')) for x in doc.select('script[src]') if '/_next/' in x.get('src', '')]}
        report[name] = info
        return doc, info
    except Exception as e:
        report[name] = {'error': f'{type(e).__name__}: {e}'}
        return None, report[name]

inspect('popular', BASE + '/mangalar', {'sort': 'popular'})
_, search = inspect('search', BASE + '/search', {'input': 'Chainsaw Man'})
inspect('search_lower', BASE + '/search', {'input': 'chainsaw man'})
inspect('detail', BASE + '/manga/chainsaw-man')
# Search JS for public API route hints, not arbitrary user data.
js_hints = []
for url in search.get('scripts', []):
    if '/pages/search' not in url: continue
    try:
        r = s.get(url, timeout=30); content = r.text
        for match in list(re.finditer(r'(?i)search|manga_data|api/', content))[:80]:
            snippet = content[max(0, match.start()-110):min(len(content), match.end()+150)]
            if any(x in snippet.lower() for x in ('api/', 'axios', 'search?', 'input', 'fetch(')):
                js_hints.append(snippet[:280])
        report['search_js'] = {'url': url, 'status': r.status_code, 'length': len(content), 'hints': list(dict.fromkeys(js_hints))[:30]}
    except Exception as e: report['search_js'] = {'error': str(e)}

Path('reports/turkmanga-diagnostic.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
