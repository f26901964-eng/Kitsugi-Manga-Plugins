#!/usr/bin/env python3
"""Sanitized live probes for simple, API-driven Turkish manga extensions.

Only counts, public titles, HTTP status/content type and decoded image metadata are
reported. HTML, response bodies, cookies and full page/image URLs are never written.
"""
from __future__ import annotations

import argparse
import io
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any
from urllib.parse import urljoin

import requests
from PIL import Image

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 35


class ProbeFailure(Exception):
    """A failure whose message is safe to expose in CI output."""


@dataclass
class Source:
    key: str
    name: str
    base_url: str


SOURCES = (
    Source("mangaportali", "Manga Portalı", "https://www.mangaportali.com"),
    Source("mangadiyari", "Manga Diyarı", "https://mangadiyari.com"),
)


def get_json(session: requests.Session, url: str) -> Any:
    try:
        response = session.get(url, timeout=TIMEOUT)
    except requests.RequestException as exc:
        raise ProbeFailure(f"network={type(exc).__name__}") from None
    if response.status_code != 200:
        raise ProbeFailure(f"http={response.status_code}")
    try:
        return response.json()
    except (ValueError, UnicodeError):
        raise ProbeFailure("response=not-json") from None


def decode_image(session: requests.Session, url: str) -> dict[str, Any]:
    headers = {
        "Accept": "image/avif,image/webp,image/*,*/*;q=0.8",
    }
    try:
        response = session.get(url, headers=headers, timeout=TIMEOUT)
    except requests.RequestException as exc:
        raise ProbeFailure(f"image-network={type(exc).__name__}") from None
    if response.status_code != 200:
        raise ProbeFailure(f"image-http={response.status_code}")
    data = response.content
    if len(data) <= 100:
        raise ProbeFailure(f"image-bytes={len(data)}")
    try:
        with Image.open(io.BytesIO(data)) as image:
            image.load()
            width, height = image.size
            image_format = image.format or "unknown"
    except Exception as exc:
        raise ProbeFailure(f"decode={type(exc).__name__}") from None
    if width <= 0 or height <= 0:
        raise ProbeFailure("decode=empty-dimensions")
    return {
        "bytes": len(data),
        "contentType": response.headers.get("Content-Type", "").split(";")[0],
        "format": image_format,
        "width": width,
        "height": height,
    }


def image_probe(session: requests.Session, base_url: str, pages: list[dict[str, Any]], url_key: str) -> list[dict[str, Any]]:
    if not pages:
        raise ProbeFailure("pages=0")
    ordered = sorted(pages, key=lambda page: page.get("index", page.get("page_number", 0)))
    selected = [ordered[0]] if len(ordered) == 1 else [ordered[0], ordered[-1]]
    results = []
    for page in selected:
        raw_url = page.get(url_key)
        if not isinstance(raw_url, str) or not raw_url.strip():
            raise ProbeFailure("image-url=missing")
        results.append(decode_image(session, urljoin(base_url + "/", raw_url)))
    return results


def probe_mangaportali(session: requests.Session, source: Source) -> dict[str, Any]:
    listing = get_json(session, f"{source.base_url}/api/series?sort=popular&page=1")
    series = listing.get("items")
    if not isinstance(series, list) or not series:
        raise ProbeFailure("series=0")
    last_failure = ProbeFailure("chapters=0")
    for item in series[:4]:
        slug = item.get("slug")
        if not isinstance(slug, str) or not slug:
            continue
        get_json(session, f"{source.base_url}/api/series/{slug}")
        chapters = get_json(
            session,
            f"{source.base_url}/api/series/{slug}/chapters?pageSize=100&page=1",
        ).get("items", [])
        free = [chapter for chapter in chapters if not chapter.get("isEarlyAccessLocked", False)]
        for chapter in free[:4]:
            chapter_id = chapter.get("id")
            if not chapter_id:
                continue
            try:
                pages = get_json(session, f"{source.base_url}/api/chapters/{chapter_id}").get("pages", [])
                images = image_probe(session, source.base_url, pages, "imageUrl")
                return {
                    "title": item.get("title", slug),
                    "seriesInListing": len(series),
                    "chaptersReturned": len(chapters),
                    "pages": len(pages),
                    "images": images,
                }
            except ProbeFailure as exc:
                last_failure = exc
    raise last_failure


def probe_mangadiyari(session: requests.Session, source: Source) -> dict[str, Any]:
    listing = get_json(session, f"{source.base_url}/api/series?sort=popular&limit=24&page=1")
    series = listing.get("series")
    if not isinstance(series, list) or not series:
        raise ProbeFailure("series=0")
    last_failure = ProbeFailure("chapters=0")
    for item in series[:4]:
        slug = item.get("slug")
        if not isinstance(slug, str) or not slug:
            continue
        details = get_json(session, f"{source.base_url}/api/series/{slug}")
        chapters = details.get("chapters", [])
        for chapter in chapters[:4]:
            chapter_id = chapter.get("id")
            if chapter_id is None:
                continue
            try:
                pages = get_json(session, f"{source.base_url}/api/chapters/{chapter_id}").get("pages", [])
                images = image_probe(session, source.base_url, pages, "display_image")
                return {
                    "title": item.get("title", slug),
                    "seriesInListing": len(series),
                    "chaptersReturned": len(chapters),
                    "pages": len(pages),
                    "images": images,
                }
            except ProbeFailure as exc:
                last_failure = exc
    raise last_failure


PROBES = {
    "mangaportali": probe_mangaportali,
    "mangadiyari": probe_mangadiyari,
}


def run() -> dict[str, Any]:
    report: dict[str, Any] = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "sources": {},
    }
    for source in SOURCES:
        session = requests.Session()
        session.headers.update({
            "User-Agent": USER_AGENT,
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
        })
        try:
            details = PROBES[source.key](session, source)
            report["sources"][source.key] = {"name": source.name, "ok": True, **details}
        except Exception as exc:
            # Unexpected exceptions are reduced to their type; response bodies and URLs are excluded.
            message = str(exc) if isinstance(exc, ProbeFailure) else f"unexpected={type(exc).__name__}"
            report["sources"][source.key] = {"name": source.name, "ok": False, "error": message}
        finally:
            session.close()
    report["ok"] = any(item["ok"] for item in report["sources"].values())
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    report = run()
    with open(args.output, "w", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write("\n")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
