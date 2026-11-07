#!/usr/bin/env python3
"""Capture raw HTML evidence for the candidate sources found on 2026-11-07.

v3: scans the Next.js chunks of EVERY page, actively probes plausible catalog
API endpoints, fetches sitemap-series.xml and retries 403 sites with a desktop
Chrome UA. The goal is the catalog/search API contract of the Initlise-Next
platform; dumps land in docs/html-dumps/<site>/ and the summary in reports/.

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

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
DESKTOP_UA = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/131.0.0.0 Safari/537.36"
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
        ("series-list", "/series"),
        ("series", "/seri/regresorun-kullanim-kilavuzu"),
        ("reader", "/seri/regresorun-kullanim-kilavuzu/bolum/1"),
    ],
}

API_PROBES = (
    "/api/series?q=regres",
    "/api/series/search?q=regres",
    "/api/search?q=regres",
    "/api/manga/search?q=regres",
    "/api/series?page=1",
    "/api/series/list",
    "/api/series/all",
    "/api/browse/series",
    "/api/v1/series",
    "/api/home",
    "/api/series/latest",
    "/api/series/popular",
    "/api/settings",
    "/sitemap-series.xml",
)


def make_session(desktop: bool) -> requests.Session:
    session = requests.Session()
    ua = DESKTOP_UA if desktop else MOBILE_UA
    session.headers.update(
        {
            "User-Agent": ua,
            "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
            "Accept": "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8",
        }
    )
    if desktop:
        session.headers.update(
            {
                "sec-ch-ua": '"Chromium";v="131", "Not_A Brand";v="24"',
                "sec-ch-ua-mobile": "?0",
                "sec-ch-ua-platform": '"Linux"',
            }
        )
    return session


def scan_chunks(session: requests.Session, base: str, body: str) -> dict:
    """Download the Next.js chunks referenced by the page and mine API paths."""
    chunks = re.findall(r'src="(/_next/static/chunks/[^"]+)"', body)
    api_hits: set[str] = set()
    fetch_hits: set[str] = set()
    for chunk in chunks[:24]:
        try:
            js = session.get(base + chunk, timeout=TIMEOUT).text
        except requests.RequestException:
            continue
        api_hits.update(re.findall(r'["\'`](/api/[a-zA-Z0-9/_.${}?=&-]+)["\'`]', js)[:60])
        for m in re.findall(r'fetch\(\s*["\'`]([^"\'`]+)["\'`]', js)[:30]:
            fetch_hits.add(m)
        # template literals such as `/api/series/search?q=${query}`
        for m in re.findall(r"["'`](/[a-z0-9/-]*(?:series|search|manga|chapter)[a-z0-9/-]*)[?'\"`]", js)[:30]:
            api_hits.add(m + "  <template?>")
    return {
        "chunksScanned": len(chunks[:24]),
        "apiEndpoints": sorted(api_hits)[:60],
        "fetchTargets": sorted(fetch_hits)[:20],
    }


def probe_apis(session: requests.Session, base: str) -> list[dict]:
    out = []
    for path in API_PROBES:
        try:
            r = session.get(base + path, timeout=TIMEOUT)
        except requests.RequestException as exc:
            out.append({"path": path, "error": type(exc).__name__})
            continue
        body = r.text[:180].replace("\n", " ")
        out.append(
            {
                "path": path,
                "status": r.status_code,
                "type": r.headers.get("content-type", "")[:40],
                "sample": body if r.status_code == 200 else "",
            }
        )
    return out


def capture(site: str, base: str, pages: list[tuple[str, str]], desktop: bool) -> dict:
    session = make_session(desktop)
    folder = DUMP_ROOT / site
    folder.mkdir(parents=True, exist_ok=True)
    result: dict = {"baseUrl": base, "ua": "desktop" if desktop else "mobile", "pages": {}}

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
        entry["lockMarkers"] = [m for m in LOCK_MARKERS if m in body.lower()]
        if response.ok and "html" in response.headers.get("content-type", "html"):
            hrefs = [h for h in HREF_RE.findall(body) if "/bolum" in h]
            entry["chapterHrefCount"] = len(set(hrefs))
            entry["pageArrayCount"] = len(re.findall(r'\\"pages\\":\[', body))
            target = folder / f"{tag}.html"
            target.write_text(body, encoding="utf-8")
            entry["dump"] = str(target.relative_to(ROOT))
            if tag in ("home", "series-list", "reader"):
                entry.update(scan_chunks(session, base, body))
        result["pages"][tag] = entry

    result["apiProbes"] = probe_apis(session, base)
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
        # First pass with the mobile UA; retry once with a desktop UA on 403.
        result = capture(site, base, PAGES[site], desktop=False)
        if all(p.get("status") != 200 for p in result["pages"].values()):
            retry = capture(site, base, PAGES[site], desktop=True)
            retry["note"] = "mobile-UA reddi sonrası masaüstü UA denemesi"
            result = retry
        report["sites"][site] = result

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    brief = {
        site: {
            "pages": {
                tag: {
                    "status": p.get("status"),
                    "pageArrays": p.get("pageArrayCount"),
                    "apis": p.get("apiEndpoints", [])[:12],
                }
                for tag, p in data["pages"].items()
            },
            "apiProbes200": [p["path"] for p in data.get("apiProbes", []) if p.get("status") == 200],
        }
        for site, data in report["sites"].items()
    }
    print(json.dumps(brief, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
