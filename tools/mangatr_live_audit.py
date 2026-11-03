"""Public, read-only live checks. Never print cookies, tokens or response bodies."""
from urllib.parse import urljoin
import re
import requests
from bs4 import BeautifulSoup

BASE = 'https://manga-tr.com/'
s = requests.Session()
s.headers.update({'User-Agent': 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36'})

def get(path):
    r = s.get(urljoin(BASE, path), timeout=30)
    print('GET', path.split('?')[0], 'status', r.status_code, 'content-type', r.headers.get('content-type', ''), 'length', len(r.content), flush=True)
    return r

try:
    for name, path in [('home', 'index.html'), ('search', 'arama.html?icerik=Chainsaw+Man&page=1'), ('popular', 'manga-list-sayfala.html?sort=views&sort_type=DESC&page=1&listType=pagination'), ('detail', 'manga-chainsaw-man.html')]:
        r = get(path)
        soup = BeautifulSoup(r.text, 'html.parser')
        print(name, 'title', bool(soup.select_one('h1.bento-hero-title')), 'search cards', len(soup.select('div.arama-card')), 'media cards', len(soup.select('.media-card')), 'episode cards', len(soup.select('article.bento-ep-card')), 'chapter_key', bool(re.search(r"listKey:\s*'([^']+)'", r.text)), 'challenge markers', [x for x in ('check.ddos-guard.net/check.js', 'cf-turnstile', '__waf_challenge') if x in r.text], flush=True)
        if name == 'detail':
            print('detail script file paths:', [x.get('src','').split('?')[0] for x in soup.select('script[src]') if 'manga' in x.get('src','') or 'chapter' in x.get('src','')], flush=True)
            print('detail results manga field present:', bool(soup.select_one('#results[data-manga]')), flush=True)
    r = s.get(urljoin(BASE, 'cek/ajax_chapters_endpoint.php'), params={'ajax': 'chapters', 'manga': 'chainsaw-man', 'offset': '0'}, headers={'X-Requested-With':'XMLHttpRequest', 'Referer':urljoin(BASE,'manga-chainsaw-man.html')}, timeout=30)
    print('reader selector status', r.status_code, 'type', r.headers.get('content-type',''), 'length', len(r.content), flush=True)
    try:
        obj = r.json()
        print('reader selector keys', sorted(obj) if isinstance(obj,dict) else 'not an object', 'rows', len(obj.get('rows',[])) if isinstance(obj,dict) and isinstance(obj.get('rows'),list) else 'unknown', 'row keys', sorted(obj['rows'][0]) if isinstance(obj,dict) and isinstance(obj.get('rows'),list) and obj['rows'] and isinstance(obj['rows'][0],dict) else [], flush=True)
    except ValueError:
        print('reader selector did not return JSON', flush=True)
except requests.RequestException as e:
    print('network failure:', type(e).__name__, str(e).split(' (Caused by ')[0], flush=True)
    raise SystemExit(1)
