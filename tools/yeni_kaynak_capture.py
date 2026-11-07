#!/usr/bin/env python3
"""Capture raw HTML evidence for the candidate sources found on 2026-11-07.

fetch_page-style markdown conversions hide CSS classes and embedded payloads, so the
sandbox cannot verify selectors. GitHub runners can fetch the sites directly; this
script saves the exact pages an extension would parse into docs/html-dumps/<site>/
plus a small JSON summary under reports/.

Usage: python tools/yeni_kaynak_capture.py [--only ailurascans,oblivionscans]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
from datetime import datetime, timezone

import requests

ROOT = pathlib.Path(__file__).resolve().parents[1]
DUMP_ROOT = ROOT / "docs" / "html-dumps"
REPORT = ROOT / "reports" / "yeni-kaynak-capture.json"

UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 30

LOCK_MARKERS = (
    "giriş yapmanız gerekiyor",
    "giriş yapmalısınız",
    "kilitli bölüm",
    "login to read",
    "members only",
)

IMAGE_RE = re.compile(r"https?://[^\"'\\\s<>]+?\.(?:webp|jpe?g|png)(?:\?[^\"'\\\s<>]*)?", re.I)
HREF_RE = re.compile(r'href="([^"]+)"')

# site -> list of (tag, path)
PAGES = {
    "ailurascans": [
        ("home", "/"),
        ("series-list", "/series"),
        ("series", "/seri/hooky"),
        ("reader", "/seri/hooky/bolum/1"),
    ],
    "oblivionscans": [
        ("home", "/"),
        ("series-list", "/series"),
        ("series", "/seri/ts-cezasi"),
        ("reader", "/seri/ts-cezasi/bolum/1"),
    ],
    "lunariscans": [
        ("home", "/"),
        ("series-list", "/series"),
        ("series", "/seri/bu-reenkarnasyonu-bozmaliyim"),
    ],
    "duckscans": [
        ("home", "/"),
        ("series", "/seri/regresorun-kullanim-kilavuzu"),
        ("reader", "/seri/regresorun-kullanim-kilavuzu/bolum/1"),
    ],
}


def capture(site: str, base: str, pages: list[tuple[str, str]]) -> dict:
    session = requests.Session()
    session.headers.update(
        {
            "User-Agent": UA,
            "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
            "Accept": "text/html,application/xhtml+xml,image/avif,image/webp,*/*;q=0.8",
        }
    )
    folder = DUMP_ROOT / site
    folder.mkdir(parents=True, exist_ok=True)
    result: dict = {"baseUrl": base, "pages": {}}

    for tag, path in pages:
        entry: dict = {"path": path}
        try:
            response = session.get(base + path, timeout=TIMEOUT, allow_redirects=True)
        except requests.RequestException as exc:
            entry["error"] = f"{type(exc).__name__}: {exc}"
            result["pages"][tag] = entry
            continue
        body = response.text
        entry["status"] = response.status_code
        entry["bytes"] = len(body)
        entry["finalUrl"] = response.url
        entry["lockMarkers"] = [m for m in LOCK_MARKERS if m in body.lower()]
        if response.ok and "html" in response.headers.get("content-type", "html"):
            image_urls = [u for u in IMAGE_RE.findall(body) if "seri-kapak" not in u and "seri-banner" not in u and "tepkiler" not in u and "logo" not in u.lower()]
            # drop UIkit/next static assets and icons; keep plausible page images
            page_like = [u for u in image_urls if re.search(r"(chapter|bolum|page|sayfa|upload|manga|series|content)", u, re.I)]
            entry["imageUrlSamples"] = sorted(set(page_like))[:8]
            entry["imageCandidateCount"] = len(set(page_like))
            hrefs = [h for h in HREF_RE.findall(body) if "/bolum" in h]
            entry["chapterHrefSamples"] = sorted(set(hrefs))[:8]
            entry["chapterHrefCount"] = len(set(hrefs))
            # pagination links on listing pages
            entry["paginationSamples"] = sorted({h for h in HREF_RE.findall(body) if re.search(r"(page|sayfa)=?\d*", h) and ("page" in h or "sayfa" in h)})[:6]
            # chapter anchor context (to read the date/status markup next to it)
            m = re.search(r'.{250}href="' + re.escape(path.rstrip("/")) + r'/bolum/\d+".{250}', body, re.S)
            if m:
                entry["chapterContextSample"] = m.group(0)[-460:]
            target = folder / f"{tag}.html"
            target.write_text(body, encoding="utf-8")
            entry["dump"] = str(target.relative_to(ROOT))
            # Scan the client JS chunks for the API that serves reader images.
            if tag == "reader":
                chunks = re.findall(r'src="(/_next/static/chunks/[^"]+)"', body)[:14]
                api_hits: set[str] = set()
                for chunk in chunks:
                    try:
                        js = session.get(base + chunk, timeout=TIMEOUT).text
                    except requests.RequestException:
                        continue
                    for pattern in (
                        r'"/api/[a-zA-Z0-9/_.${}-]+"',
                        r'`/api/[^`]+`',
                        r'"[a-zA-Z0-9/_-]*chapter[a-zA-Z0-9/_-]*"',
                        r'fetch\("([^"]+)"',
                        r'"/trpc/[a-zA-Z0-9._,-]+"',
                    ):
                        for hit in re.findall(pattern, js)[:40]:
                            api_hits.add(hit if isinstance(hit, str) else hit[0])
                entry["apiEndpointSamples"] = sorted(api_hits)[:40]
        result["pages"][tag] = entry
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--only", default="")
    args = parser.parse_args()
    only = {s.strip() for s in args.only.split(",") if s.strip()}

    bases = {
        "ailurascans": "https://ailurascans.com",
        "oblivionscans": "https://oblivionscans.com",
        "lunariscans": "https://lunariscans.com",
        "duckscans": "https://duckscans.com",
    }
    report = {
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "sites": {},
    }
    for site, base in bases.items():
        if only and site not in only:
            continue
        report["sites"][site] = capture(site, base, PAGES[site])

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
