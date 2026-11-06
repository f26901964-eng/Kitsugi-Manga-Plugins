#!/usr/bin/env python3
"""Live contract probe for candidate Turkish manga sources.

For every candidate the probe walks the same path the extension walks
(listing -> series -> chapter -> page images) and records only sanitized facts:
counts, HTTP status/content type, decoded image metadata and whether the
chapter is locked behind a login wall. Full image URLs and HTML are never
written to the report.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from urllib.parse import urljoin, urlparse

import requests
from bs4 import BeautifulSoup
from PIL import Image

UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 40

LOCK_MARKERS = (
    "kilitli bölüm",
    "giriş yapınız",
    "üye olmalısınız",
    "siteye giriş",
    "members only",
)

MADARA_PAGE_IMAGES = "div#chapter-content img, div.reading-content img, div.entry-content img"
THEMESIA_PAGE_IMAGES = "div#readerarea img, div.entry-content img, div#chapter-content img"
INIT_MANGA_PAGE_IMAGES = (
    "div#chapter-content img, div.chapter-content img, div.reader-area img, "
    "div.entry-content img, div#readerarea img"
)


@dataclass
class Candidate:
    key: str
    name: str
    base_url: str
    theme: str
    listing_path: str
    series_pattern: str
    chapter_pattern: str
    page_selector: str
    api: bool = False


CANDIDATES = (
    Candidate(
        key="mangadenizi",
        name="MangaDenizi",
        base_url="https://mangadenizi.net",
        theme="json-api",
        listing_path="/api/v1/web/manga?sort=popular&page=1",
        series_pattern="",
        chapter_pattern="",
        page_selector="",
        api=True,
    ),
    Candidate(
        key="hayalistic",
        name="Hayalistic",
        base_url="https://hayalistic.online",
        theme="madara",
        listing_path="/manga/?m_orderby=trending",
        series_pattern=r"/manga/([^/\"']+)/",
        chapter_pattern=r"/manga/[^/\"']+/bolum-[^/\"']+/",
        page_selector=MADARA_PAGE_IMAGES,
    ),
    Candidate(
        key="shijiescans",
        name="Shijie Scans",
        base_url="https://shijiescans.com",
        theme="mangathemesia",
        listing_path="/seri/",
        series_pattern=r"/seri/([^/\"']+)/",
        chapter_pattern=r"/[^/\"']+-bolum-[^/\"']+/",
        page_selector=THEMESIA_PAGE_IMAGES,
    ),
    Candidate(
        key="mangaruhu",
        name="MangaRuhu",
        base_url="https://mangaruhu.com",
        theme="initmanga",
        listing_path="/manga/",
        series_pattern=r"/manga/([^/\"']+)/",
        chapter_pattern=r"/manga/[^/\"']+/bolum-[^/\"']+/",
        page_selector=INIT_MANGA_PAGE_IMAGES,
    ),
)


def session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8"})
    return s


def image_facts(s: requests.Session, url: str, referer: str, with_referer: bool):
    headers = {}
    if with_referer:
        headers["Referer"] = referer
    try:
        r = s.get(url, headers=headers, timeout=TIMEOUT)
    except requests.RequestException as exc:  # pragma: no cover - network dependent
        return {"ok": False, "error": type(exc).__name__}
    out = {
        "ok": r.status_code == 200,
        "status": r.status_code,
        "contentType": r.headers.get("content-type", "")[:40],
        "bytes": len(r.content),
    }
    if r.ok:
        try:
            with Image.open(io.BytesIO(r.content)) as im:
                out["size"] = f"{im.width}x{im.height}"
                out["format"] = im.format
                out["ok"] = True
        except Exception:
            out["ok"] = False
            out["size"] = None
    return out


def summarize_images(s: requests.Session, urls, referer):
    picked = urls[:3]
    results = []
    for index, url in enumerate(picked, start=1):
        host = urlparse(url).netloc
        plain = image_facts(s, url, referer, with_referer=False)
        plain["host"] = host
        plain["name"] = f"page{index}"
        results.append(plain)
    referer_result = None
    if picked:
        referer_result = image_facts(s, picked[0], referer, with_referer=True)
        referer_result["name"] = "page1+referer"
    return results, referer_result


def probe_html_candidate(s: requests.Session, cand: Candidate) -> dict:
    report: dict = {"name": cand.name, "theme": cand.theme, "baseUrl": cand.base_url}
    try:
        listing = s.get(urljoin(cand.base_url, cand.listing_path), timeout=TIMEOUT)
        report["listing"] = {
            "status": listing.status_code,
            "bytes": len(listing.content),
            "contentType": listing.headers.get("content-type", "")[:40],
        }
        if not listing.ok:
            report["ok"] = False
            return report
        soup = BeautifulSoup(listing.text, "html.parser")
        series = []
        for a in soup.select("a[href]"):
            href = a.get("href", "")
            match = re.search(cand.series_pattern, href)
            if match:
                full = urljoin(cand.base_url, href)
                slug = match.group(1)
                if slug not in [x[1] for x in series]:
                    series.append((full, slug))
        report["listing"]["seriesFound"] = len(series)
        if not series:
            report["ok"] = False
            return report

        series_url, slug = series[0]
        detail = s.get(series_url, timeout=TIMEOUT)
        dsoup = BeautifulSoup(detail.text, "html.parser")
        title = (dsoup.select_one("h1") or dsoup.select_one("title"))
        report["series"] = {
            "status": detail.status_code,
            "slug": slug,
            "title": (title.get_text(strip=True)[:80] if title else None),
        }
        chapters = []
        for a in dsoup.select("a[href]"):
            href = a.get("href", "")
            if re.search(cand.chapter_pattern, href):
                full = urljoin(cand.base_url, href)
                if full not in chapters:
                    chapters.append(full)
        report["series"]["chapterLinksFound"] = len(chapters)
        if not chapters:
            report["ok"] = False
            return report

        chapter_url = chapters[0]
        chapter = s.get(chapter_url, timeout=TIMEOUT)
        csoup = BeautifulSoup(chapter.text, "html.parser")
        text = csoup.get_text(" ", strip=True).lower()
        locked = [m for m in LOCK_MARKERS if m in text]
        images = []
        for img in csoup.select(cand.page_selector):
            src = img.get("src") or img.get("data-src") or img.get("data-lazy-src") or ""
            if not src or src.startswith("data:"):
                continue
            images.append(urljoin(chapter_url, src))
        report["chapter"] = {
            "status": chapter.status_code,
            "bytes": len(chapter.content),
            "lockMarkers": locked,
            "imagesInDom": len(images),
            "lazyPlaceholders": len(csoup.select(cand.page_selector)) - len(images),
        }
        if images:
            plain, with_referer = summarize_images(s, images, chapter_url)
            report["chapter"]["imageProbe"] = plain
            report["chapter"]["imageProbeWithReferer"] = with_referer
        report["ok"] = bool(images) and not locked
    except requests.RequestException as exc:
        report["ok"] = False
        report["error"] = type(exc).__name__
    return report


def probe_api_candidate(s: requests.Session, cand: Candidate) -> dict:
    report: dict = {"name": cand.name, "theme": cand.theme, "baseUrl": cand.base_url}
    api_headers = {"Accept": "application/json", "Referer": f"{cand.base_url}/manga"}
    try:
        listing = s.get(urljoin(cand.base_url, cand.listing_path), headers=api_headers, timeout=TIMEOUT)
        report["listing"] = {
            "status": listing.status_code,
            "bytes": len(listing.content),
            "contentType": listing.headers.get("content-type", "")[:40],
        }
        if not listing.ok:
            report["ok"] = False
            return report
        data = listing.json()["data"]["manga"]
        rows = data["data"]
        report["listing"]["seriesFound"] = len(rows)
        report["listing"]["lastPage"] = data.get("last_page")
        first = rows[0]
        report["series"] = {
            "status": 200,
            "slug": first["slug"],
            "title": first.get("title"),
            "chapters": len(first.get("chapters") or []),
            "chaptersCount": first.get("chapters_count"),
        }
        detail = s.get(
            f"{cand.base_url}/api/v1/web/manga/{first['slug']}",
            headers=api_headers,
            timeout=TIMEOUT,
        )
        djson = detail.json()["data"]["manga"]
        chapters = djson.get("chapters") or []
        report["series"]["detailStatus"] = detail.status_code
        report["series"]["detailChapters"] = len(chapters)
        if not chapters:
            report["ok"] = False
            return report
        chapter = chapters[0]
        reader = s.get(
            f"{cand.base_url}/api/v1/reader/{djson['slug']}/{chapter['slug']}",
            headers=api_headers,
            timeout=TIMEOUT,
        )
        rjson = reader.json()
        pages = rjson.get("pages") or []
        scrambled = sum(1 for p in pages if (p.get("scramble") or {}).get("method"))
        report["chapter"] = {
            "status": reader.status_code,
            "pagesInApi": len(pages),
            "scrambledPages": scrambled,
            "lockMarkers": [],
            "imagesInDom": len(pages),
        }
        urls = [p["image_url"] for p in pages if p.get("image_url")]
        if urls:
            plain, with_referer = summarize_images(s, urls, f"{cand.base_url}/manga")
            report["chapter"]["imageProbe"] = plain
            report["chapter"]["imageProbeWithReferer"] = with_referer
        report["ok"] = bool(urls)
    except (requests.RequestException, KeyError, ValueError) as exc:
        report["ok"] = False
        report["error"] = type(exc).__name__
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="reports/candidate-live-probe.json")
    parser.add_argument("--only", nargs="*", default=None)
    args = parser.parse_args()

    s = session()
    results = {}
    for cand in CANDIDATES:
        if args.only and cand.key not in args.only:
            continue
        print(f"probing {cand.key} ...", flush=True)
        if cand.api:
            results[cand.key] = probe_api_candidate(s, cand)
        else:
            results[cand.key] = probe_html_candidate(s, cand)
        report = results[cand.key]
        print(f"  ok={report.get('ok')} chapter={json.dumps(report.get('chapter', {}), ensure_ascii=False)[:400]}", flush=True)

    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "candidates": results,
    }
    from pathlib import Path

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    for key, report in results.items():
        chapter = report.get("chapter") or {}
        images = chapter.get("imageProbe") or []
        good = sum(1 for i in images if i.get("ok"))
        line = (
            f"{key}: ok={report.get('ok')} listing={report.get('listing', {}).get('status')} "
            f"images={chapter.get('imagesInDom')} decodable={good}/{len(images)} "
            f"locked={bool(chapter.get('lockMarkers'))}"
        )
        print(line)
        print(f"::notice title=probe {key}::{line}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
