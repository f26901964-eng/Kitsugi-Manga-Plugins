"""Run the site's own JavaScript on a public manga page, without saving session secrets."""
import re, shutil, sys
from urllib.parse import urlparse
import requests
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

base = 'https://manga-tr.com/'
ua = 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36'
s = requests.Session()
s.headers['User-Agent'] = ua
r = s.get(base, timeout=30)
if r.status_code == 403 and 'check.ddos-guard.net/check.js' in r.text:
    ck = s.get('https://check.ddos-guard.net/check.js', timeout=30)
    match = re.search(r"'([^']+)'", ck.text)
    if match and match.group(1).startswith('/') and not match.group(1).startswith('//'):
        s.get(base.rstrip('/') + match.group(1), timeout=30)
chrome = shutil.which('google-chrome') or shutil.which('chromium') or shutil.which('chromium-browser')
print('browser available', bool(chrome), flush=True)
if not chrome:
    sys.exit(1)
with sync_playwright() as pw:
    browser = pw.chromium.launch(executable_path=chrome, headless=True, args=['--no-sandbox'])
    context = browser.new_context(user_agent=ua)
    context.add_cookies([{'name': c.name, 'value': c.value, 'domain': c.domain, 'path': c.path or '/'} for c in s.cookies])
    page = context.new_page()
    def on_response(res):
        request = res.request
        p = urlparse(res.url)
        if p.hostname in ('manga-tr.com','www.manga-tr.com') and (request.resource_type in ('xhr','fetch') or '/cek/' in p.path):
            try:
                body = res.text()
                print('XHR', request.method, p.path, 'status', res.status, 'bytes', len(body), 'episode cards', body.count('bento-ep-card'), 'has groups', 'fansub' in body.lower(), flush=True)
                if 'bento-ep-card' in body:
                    print('FOUND_CHAPTER_CARDS', flush=True)
            except Exception:
                print('XHR', request.method, p.path, 'status', res.status, flush=True)
    page.on('response', on_response)
    try:
        res = page.goto(base + 'manga-chainsaw-man.html', wait_until='domcontentloaded', timeout=45000)
        print('browser detail status', res.status if res else None, flush=True)
        page.wait_for_timeout(15000)
        print('rendered episode cards', page.locator('article.bento-ep-card').count(), 'results inner text length', len(page.locator('#results').inner_text()) if page.locator('#results').count() else -1, flush=True)
    except PlaywrightTimeout:
        print('browser timed out', flush=True)
        sys.exit(1)
    finally:
        browser.close()
