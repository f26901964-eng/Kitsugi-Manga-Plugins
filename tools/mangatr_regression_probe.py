#!/usr/bin/env python3
"""Sanitized live diagnostics for user-reported Manga-TR series and readers."""
from __future__ import annotations

import hashlib
import json
import re
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlencode, urljoin, urlsplit

import requests
from bs4 import BeautifulSoup
from mangatr_browser_probe import clear_ddos_guard

BASE = "https://manga-tr.com"
UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 35
TARGETS = (
    ("weep", "One Piece Doujinshi - WEEP", "zootopia-i-will-survive"),
    ("happy-birthday", "Happy Birthday Luffy", None),
    ("which", "One Piece dj - WHICH!", None),
    ("rick-morty", "Rick and Morty", "one-piece-dj-welcome-to-the-straw-hat-forest"),
    ("lord-mysteries", "Lord of the Mysteries", "lord-of-the-mysteries"),
)
CARD_SELECTOR = (
    "article.bento-ep-card, div.bento-ep-card, div.chapter-item, "
    "article.chapter-item, div.chapter-box, li.chapter-item"
)


def safe_text(value: str, limit: int = 400) -> str:
    value = re.sub(r"[A-Za-z0-9_\-+/=]{32,}", "<redacted>", value)
    return re.sub(r"\s+", " ", value).strip()[:limit]


def request(session: requests.Session, method: str, url: str, **kwargs: Any) -> requests.Response:
    try:
        return session.request(method, url, timeout=TIMEOUT, **kwargs)
    except requests.RequestException as exc:
        raise RuntimeError(f"network={type(exc).__name__}") from None


def navigation_headers(referer: str = BASE + "/") -> dict[str, str]:
    return {
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Referer": referer,
    }


def ajax_headers(referer: str) -> dict[str, str]:
    return {
        "X-Requested-With": "XMLHttpRequest",
        "Accept": "text/html, */*; q=0.01",
        "Accept-Encoding": "identity",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
        "Referer": referer,
        "Origin": BASE,
    }


def get_seed(session: requests.Session) -> int:
    for suffix in ("", "?v=0411-6"):
        url = f"{BASE}/app/manga/themes/default/assets/css/mtr.min.css{suffix}"
        response = request(session, "GET", url, headers=navigation_headers())
        if response.status_code != 200:
            continue
        white = re.search(r"--mdx-white\s*:\s*(\d+)", response.text)
        black = re.search(r"--mdx-black\s*:\s*(\d+)", response.text)
        if white and black:
            return int(white.group(1)) + int(black.group(1))
    return 0


def resolve_slug(session: requests.Session, query: str) -> str | None:
    url = f"{BASE}/arama.html?{urlencode({'view': 'sayfalama', 'icerik': query, 'page': 1})}"
    response = request(session, "GET", url, headers=navigation_headers(url))
    if response.status_code != 200:
        raise RuntimeError(f"search-http={response.status_code}")
    document = BeautifulSoup(response.text, "html.parser")
    needle = query.casefold()
    links = document.select("a.arama-card__title[href], a[href*='/manga-'][href$='.html']")
    for link in links:
        title = link.get_text(" ", strip=True) or link.get("title", "")
        href = link.get("href", "")
        if needle in title.casefold() or title.casefold() in needle:
            match = re.search(r"/manga-([^/]+)\.html", href)
            if match:
                return match.group(1)
    for link in links:
        href = link.get("href", "")
        match = re.search(r"/manga-([^/]+)\.html", href)
        if match:
            return match.group(1)
    return None


def chapter_links(document: BeautifulSoup) -> list[dict[str, str]]:
    results = []
    seen = set()
    for card in document.select(CARD_SELECTOR):
        rows = card.select(".bento-ep-translation-row")
        containers = rows if rows else [card]
        for container in containers:
            link = container.select_one("a.bento-ep-group-link[href*='-read-']")
            if link is None:
                link = container.select_one("a[href*='-read-'], [data-href*='-read-']")
            if link is None:
                continue
            href = link.get("href") or link.get("data-href") or ""
            absolute = urljoin(BASE + "/", href)
            path = urlsplit(absolute).path
            query = urlsplit(absolute).query
            identity = path + "?" + query
            if identity in seen:
                continue
            seen.add(identity)
            number_element = card.select_one(".bento-ep-chapter-num")
            results.append({
                "number": number_element.get_text(" ", strip=True) if number_element else "",
                "group": link.get_text(" ", strip=True)[:100],
                "path": path + (("?" + query) if query else ""),
            })
    return results


def parse_api_body(response: requests.Response) -> tuple[BeautifulSoup, dict[str, Any]]:
    body = response.text
    metadata: dict[str, Any] = {"bodyBytes": len(response.content), "jsonKeys": []}
    html = body
    if body.lstrip().startswith("{"):
        try:
            payload = response.json()
            if isinstance(payload, dict):
                metadata["jsonKeys"] = sorted(payload.keys())[:20]
                for key in ("html", "data"):
                    if isinstance(payload.get(key), str):
                        html = payload[key]
                        break
                for key in ("success", "status", "message", "error"):
                    if key in payload and not isinstance(payload[key], (dict, list)):
                        metadata[key] = safe_text(str(payload[key]), 200)
        except ValueError:
            metadata["jsonError"] = True
    document = BeautifulSoup(html, "html.parser")
    metadata["textPreview"] = safe_text(document.get_text(" ", strip=True), 350)
    metadata["htmlPrefix"] = safe_text(html[:600], 600)
    return document, metadata


def reader_diagnostic(session: requests.Session, chapter: dict[str, str], detail_url: str) -> dict[str, Any]:
    url = urljoin(BASE + "/", chapter["path"])
    response = request(session, "GET", url, headers=navigation_headers(detail_url))
    document = BeautifulSoup(response.text, "html.parser")
    title = document.title.get_text(" ", strip=True) if document.title else ""
    generic = document.select("div.reader img, div.chapter-images img, #reader img, .reader-area img")
    heading = document.select_one("h1")
    is_404 = (
        response.status_code == 404 or "404" in title
        or (heading is not None and heading.get_text(strip=True) == "404")
        or urlsplit(response.url).path == "/404.php"
    )
    return {
        **chapter,
        "http": response.status_code,
        "finalPath": urlsplit(response.url).path,
        "title": safe_text(title, 160),
        "is404": is_404,
        "bodyBytes": len(response.content),
        "gate": bool(re.search(r"(?:_fpx|gatePath|mtrGatePath|readerGate)\s*=", response.text)),
        "configScripts": len(document.select("script[id^=rdm-]")),
        "chapterPages": len(document.select("div.chapter-page")),
        "directPartImages": len(document.select("img[src*='img_part.php'], img[data-src*='img_part.php']")),
        "genericReaderImages": len(generic),
        "textPreview": safe_text(document.get_text(" ", strip=True), 250) if response.status_code != 200 else "",
    }


def probe_target(session: requests.Session, seed: int, key: str, query: str, known_slug: str | None) -> dict[str, Any]:
    try:
        slug = known_slug or resolve_slug(session, query)
        if not slug:
            return {"key": key, "query": query, "error": "series-not-found"}
        detail_url = f"{BASE}/manga-{slug}.html"
        detail_response = request(session, "GET", detail_url, headers=navigation_headers(detail_url))
        detail = BeautifulSoup(detail_response.text, "html.parser")
        result = detail.select_one("#results")
        tur = result.get("data-tur", "") if result else ""
        timestamp = int(time.time())
        signature = hashlib.sha256(f"{slug}_{seed}_{timestamp}".encode()).hexdigest()
        response = request(
            session, "POST", f"{BASE}/cek/fetch_pages_manga.php",
            data={"manga": slug, "tur": tur, "offset": "0", "_t": str(timestamp), "_sig": signature},
            headers=ajax_headers(detail_url),
        )
        api_document, metadata = parse_api_body(response)
        links = chapter_links(api_document)
        readers = []
        for chapter in links[:4]:
            try:
                readers.append(reader_diagnostic(session, chapter, detail_url))
            except Exception as exc:
                readers.append({**chapter, "error": str(exc)})
            time.sleep(0.25)
        return {
            "key": key,
            "query": query,
            "slug": slug,
            "detailHttp": detail_response.status_code,
            "detailHasResults": result is not None,
            "apiHttp": response.status_code,
            "cardCount": len(api_document.select(CARD_SELECTOR)),
            "chapterLinkCount": len(links),
            **metadata,
            "readers": readers,
        }
    except Exception as exc:
        return {"key": key, "query": query, "error": str(exc)}


def main() -> int:
    # Use the same public DDoS-Guard exchange as the production/browser probes.
    session = clear_ddos_guard()
    session.headers.update({"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7"})
    try:
        seed = get_seed(session)
        report = {
            "timestamp": int(time.time()),
            "seedResolved": seed != 0,
            "targets": [probe_target(session, seed, *target) for target in TARGETS],
        }
    finally:
        session.close()
    output = Path("reports/mangatr-regression-results.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
