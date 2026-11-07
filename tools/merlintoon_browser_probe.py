#!/usr/bin/env python3
"""Probe a public reader with browser sessions, without exporting cookies or tokens.

A Cloudflare challenge is reported as such, never as a passing reader test.
Only a successful non-challenge document is saved as a short-lived CI artifact.
"""
from pathlib import Path
from curl_cffi import requests
from playwright.sync_api import sync_playwright, TimeoutError as PlaywrightTimeout

URL = 'https://merlintoon.com/seri/buyucu-coban/bolum-1/'
OUT = Path('/tmp/merlintoon-live-reader.html')


def report(label, status, html):
    lower = html.lower()
    challenge = ('just a moment' in lower or 'cf-chl-' in lower or
                 'attention required! | cloudflare' in lower or 'challenge-platform' in lower)
    reader = 'chapter-content' in lower or 'initmangaencryptedchapter' in lower
    print(f'{label}: HTTP {status}; challenge={challenge}; readerMarker={reader}; bytes={len(html.encode())}', flush=True)
    if status == 200 and not challenge and reader:
        OUT.write_text(html, encoding='utf-8')
        return True
    return False


def main():
    for profile in ('chrome', 'safari', 'firefox'):
        try:
            r = requests.get(URL, impersonate=profile, timeout=35, allow_redirects=True)
            if report('TLS '+profile, r.status_code, r.text):
                return
        except Exception as e:
            print(f'TLS {profile}: {type(e).__name__}: {str(e)[:200]}', flush=True)
    with sync_playwright() as p:
        for browser_type in (p.chromium, p.firefox):
            browser = None
            try:
                browser = browser_type.launch(headless=False)
                context = browser.new_context(locale='tr-TR', timezone_id='Europe/Istanbul', viewport={'width': 1365, 'height': 900})
                page = context.new_page()
                response = page.goto(URL, wait_until='domcontentloaded', timeout=45000)
                page.wait_for_timeout(20000)
                if report('Browser '+browser_type.name, response.status if response else 0, page.content()):
                    return
            except Exception as e:
                print(f'Browser {browser_type.name}: {type(e).__name__}: {str(e)[:200]}', flush=True)
            finally:
                if browser:
                    browser.close()
    raise SystemExit('No real chapter response obtained; Cloudflare/network remains unverified.')

if __name__ == '__main__':
    main()
