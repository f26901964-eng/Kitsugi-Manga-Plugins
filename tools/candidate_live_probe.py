#!/usr/bin/env python3
"""Live readability sweep across every Turkish source shipped in this repo.

The sweep does not trust the extension code: for each source it starts at the
site root, finds a series page, a chapter page and finally the page images, then
decodes the first images to prove they are real pictures. Only counts, status
codes and public titles are stored, never full image URLs or HTML.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "keiyoushi-extensions-tr" / "src" / "tr"

UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 25

SERIES_PATTERNS = (
    r"/manga/([a-z0-9][a-z0-9-]{1,80})/?$",
    r"/seri/([a-z0-9][a-z0-9-]{1,80})/?$",
    r"/serie/([a-z0-9][a-z0-9-]{1,80})/?$",
    r"/mangas?/([a-z0-9][a-z0-9-]{1,80})/?$",
)
CHAPTER_HINTS = ("/bolum-", "-bolum-", "/chapter-", "-chapter-", "/cilt-", "/episode-")
LOCK_MARKERS = (
    "kilitli bölüm",
    "giriş yapınız",
    "üye olmalısınız",
    "siteye giriş yap",
    "login to read",
    "members only",
)
BAD_IMAGE_HINTS = ("logo", "banner", "avatar", "icon", "favicon", "placeholder", "spinner")


@dataclass
class Source:
    key: str
    name: str
    base_url: str | None
    theme: str


def read_sources() -> list[Source]:
    out = []
    for module in sorted(SRC.iterdir()):
        gradle = module / "build.gradle.kts"
        if not gradle.is_file():
            continue
        text = gradle.read_text(encoding="utf-8", errors="replace")
        name = re.search(r'name = "([^"]+)"', text)
        base = re.search(r'baseUrl = "([^"]+)"', text)
        theme = re.search(r'theme = "([^"]+)"', text)
        out.append(
            Source(
                key=module.name,
                name=name.group(1) if name else module.name,
                base_url=base.group(1) if base else None,
                theme=theme.group(1) if theme else "custom",
            )
        )
    return out


def make_session() -> requests.Session:
    s = requests.Session()
    s.headers.update(
        {
            "User-Agent": UA,
            "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8",
            "Accept": "text/html,application/xhtml+xml,image/avif,image/webp,*/*;q=0.8",
        }
    )
    return s


def get(s: requests.Session, url: str, referer: str | None = None):
    headers = {"Referer": referer} if referer else {}
    return s.get(url, headers=headers, timeout=TIMEOUT)


def decode_image(s: requests.Session, url: str, referer: str) -> dict:
    try:
        r = get(s, url, referer)
    except requests.RequestException as exc:
        return {"ok": False, "error": type(exc).__name__}
    info = {
        "ok": False,
        "status": r.status_code,
        "contentType": r.headers.get("content-type", "")[:30],
        "bytes": len(r.content),
    }
    if r.ok:
        try:
            with Image.open(io.BytesIO(r.content)) as im:
                info["size"] = f"{im.width}x{im.height}"
                info["format"] = im.format
                info["ok"] = min(im.width, im.height) >= 120
        except Exception:
            info["error"] = "not-an-image"
    return info


def same_host(base: str, url: str) -> bool:
    b = urlparse(base).netloc.replace("www.", "")
    u = urlparse(url).netloc.replace("www.", "")
    return b == u or u.endswith("." + b)


def find_series(soup: BeautifulSoup, base: str) -> list[tuple[str, str]]:
    seen, found = set(), []
    for a in soup.select("a[href]"):
        href = a.get("href", "")
        if not href or href.startswith(("#", "javascript:", "mailto:")):
            continue
        full = urljoin(base, href)
        if not same_host(base, full):
            continue
        path = urlparse(full).path
        if any(h in path for h in CHAPTER_HINTS):
            continue
        for pattern in SERIES_PATTERNS:
            m = re.search(pattern, path)
            if m and m.group(1) not in seen:
                seen.add(m.group(1))
                found.append((full, m.group(1)))
    return found


def find_chapters(soup: BeautifulSoup, base: str) -> list[str]:
    out = []
    for a in soup.select("a[href]"):
        href = a.get("href", "")
        if not any(h in href for h in CHAPTER_HINTS):
            continue
        full = urljoin(base, href)
        if same_host(base, full) and full not in out:
            out.append(full)
    return out


def find_page_images(soup: BeautifulSoup, chapter_url: str) -> list[str]:
    urls = []
    candidates = soup.select(
        "div#chapter-content img, div.reading-content img, div.entry-content img, "
        "div#readerarea img, div.chapter-content img, div.reader-area img, "
        "div#readerarea p img, .reading-content .page-break img, div#images img, "
        "section#chapterbody img, div.chapter-images img, p img"
    )
    for img in candidates:
        for attr in ("src", "data-src", "data-lazy-src", "data-original", "data-url"):
            src = (img.get(attr) or "").strip()
            if not src or src.startswith("data:"):
                continue
            low = src.lower()
            if any(b in low for b in BAD_IMAGE_HINTS):
                continue
            urls.append(urljoin(chapter_url, src))
            break
    # de-duplicate, keep order
    seen, ordered = set(), []
    for u in urls:
        if u not in seen:
            seen.add(u)
            ordered.append(u)
    return ordered


def probe(source: Source, nav_only: bool = False) -> dict:
    report: dict = {
        "name": source.name,
        "theme": source.theme,
        "baseUrl": source.base_url,
        "ok": False,
    }
    if not source.base_url:
        report["error"] = "no-base-url"
        return report

    s = make_session()
    try:
        home = get(s, source.base_url)
    except requests.RequestException as exc:
        report["error"] = type(exc).__name__
        return report
    report["home"] = {
        "status": home.status_code,
        "bytes": len(home.content),
        "contentType": home.headers.get("content-type", "")[:30],
    }
    if not home.ok:
        report["error"] = f"home-{home.status_code}"
        return report
    report["ok"] = True
    if nav_only:
        return report

    soup = BeautifulSoup(home.text, "html.parser")
    series = find_series(soup, source.base_url)
    if not series:
        # try the common listing paths, cheapest first
        for path in ("/manga/", "/seri/", "/manga/?order=update", "/mangas/"):
            try:
                listing = get(s, urljoin(source.base_url, path))
            except requests.RequestException:
                continue
            if listing.ok:
                series = find_series(BeautifulSoup(listing.text, "html.parser"), source.base_url)
                if series:
                    report["listingPath"] = path
                    break
    report["seriesFound"] = len(series)
    if not series:
        report["ok"] = False
        report["error"] = "no-series-links"
        return report

    series_url, slug = series[0]
    try:
        detail = get(s, series_url)
    except requests.RequestException as exc:
        report["error"] = type(exc).__name__
        report["ok"] = False
        return report
    dsoup = BeautifulSoup(detail.text, "html.parser")
    heading = dsoup.select_one("h1") or dsoup.select_one("title")
    chapters = find_chapters(dsoup, source.base_url)
    report["series"] = {
        "status": detail.status_code,
        "slug": slug,
        "title": heading.get_text(strip=True)[:70] if heading else None,
        "chapters": len(chapters),
    }
    if not chapters:
        report["ok"] = False
        report["error"] = "no-chapter-links"
        return report

    chapter_url = chapters[0]
    try:
        chapter = get(s, chapter_url)
    except requests.RequestException as exc:
        report["error"] = type(exc).__name__
        report["ok"] = False
        return report
    csoup = BeautifulSoup(chapter.text, "html.parser")
    text = csoup.get_text(" ", strip=True).lower()
    locked = [m for m in LOCK_MARKERS if m in text]
    total_imgs = len(csoup.select("img"))
    images = find_page_images(csoup, chapter_url)
    report["chapter"] = {
        "status": chapter.status_code,
        "bytes": len(chapter.content),
        "imgTags": total_imgs,
        "pageImages": len(images),
        "lockMarkers": locked,
    }
    if images:
        probes = [decode_image(s, u, chapter_url) for u in images[:3]]
        report["chapter"]["decoded"] = sum(1 for p in probes if p.get("ok"))
        report["chapter"]["imageProbe"] = probes
        report["ok"] = report["chapter"]["decoded"] > 0 and not locked
        if locked:
            report["error"] = "login-wall"
        elif not report["chapter"]["decoded"]:
            report["error"] = "images-not-real"
    else:
        report["ok"] = False
        report["error"] = "no-page-images"
    return report


def summarize(report: dict) -> str:
    chapter = report.get("chapter") or {}
    return (
        f"{report.get('name')}: ok={report.get('ok')} home={report.get('home', {}).get('status')} "
        f"series={report.get('seriesFound')} chapters={(report.get('series') or {}).get('chapters')} "
        f"pages={chapter.get('pageImages')} decoded={chapter.get('decoded')} "
        f"locked={bool(chapter.get('lockMarkers'))} err={report.get('error') or '-'}"
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="reports/candidate-live-probe.json")
    parser.add_argument("--only", nargs="*", default=None)
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args()

    sources = read_sources()
    if args.only:
        sources = [s for s in sources if s.key in args.only]

    results: dict[str, dict] = {}

    def run(source: Source) -> tuple[str, dict]:
        try:
            return source.key, probe(source)
        except Exception as exc:  # pragma: no cover - defensive
            return source.key, {"name": source.name, "ok": False, "error": type(exc).__name__}

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        for key, report in pool.map(run, sources):
            results[key] = report
            print(summarize(report), flush=True)

    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "sources": results,
    }
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    working = [k for k, r in results.items() if r.get("ok")]
    blocked = [k for k, r in results.items() if str(r.get("error", "")).startswith("home-4")]
    print(f"\nreadable sources: {len(working)}/{len(results)} -> {', '.join(sorted(working))}")
    print(f"blocked/forbidden: {len(blocked)} -> {', '.join(sorted(blocked))}")
    print(
        "::notice title=candidate sweep::"
        f"readable={len(working)}/{len(results)} blocked={len(blocked)} "
        f"ok={','.join(sorted(working))[:400]}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
