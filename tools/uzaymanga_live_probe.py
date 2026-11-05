#!/usr/bin/env python3
"""Compare live UzayManga-family sources without persisting URLs or response bodies.

The URL, devalue traversal and CDN rules mirror lib-multisrc/uzaymanga. Only public
 titles, counts, timestamps, HTTP/image metadata and sanitized errors are reported.
"""
from __future__ import annotations

import argparse
import io
import json
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

import requests
from PIL import Image

USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 35
PAGE_SIZE = 20
MAX_CATALOG_PAGES = 25


@dataclass(frozen=True)
class Candidate:
    key: str
    name: str
    base_url: str
    cdn_url: str
    extension_version: str


CANDIDATES = (
    Candidate("uzaymanga", "Uzay Manga", "https://uzaymanga.com", "https://cdn-u.efsaneler2.can.re", "1.6.48"),
    Candidate("eldermanga", "Elder Manga", "https://eldermanga.com", "https://cdn-el.efsaneler2.can.re", "1.6.9"),
    Candidate("eskimangalar", "Eski Mangalar", "https://eskimangalar.com", "https://cdn-es.efsaneler2.can.re", "1.6.6"),
)


class ProbeFailure(Exception):
    """A sanitized failure safe to print in CI."""


class SvelteData:
    def __init__(self, array: list[Any]):
        self.array = array

    @staticmethod
    def pointer(node: dict[str, Any], key: str) -> int | None:
        value = node.get(key)
        return value if isinstance(value, int) and not isinstance(value, bool) else None

    def get_object(self, index: int | None) -> dict[str, Any] | None:
        if index is None or index < 0 or index >= len(self.array):
            return None
        value = self.array[index]
        return value if isinstance(value, dict) else None

    def get_array(self, index: int | None) -> list[Any] | None:
        if index is None or index < 0 or index >= len(self.array):
            return None
        value = self.array[index]
        return value if isinstance(value, list) else None

    def get_string(self, index: int | None) -> str | None:
        if index is None or index < 0 or index >= len(self.array):
            return None
        value = self.array[index]
        return value if isinstance(value, str) else None

    def get_int(self, index: int | None) -> int | None:
        if index is None or index < 0 or index >= len(self.array):
            return None
        value = self.array[index]
        return value if isinstance(value, int) and not isinstance(value, bool) else None

    def resolve_object(self, node: dict[str, Any], key: str) -> dict[str, Any] | None:
        return self.get_object(self.pointer(node, key))

    def resolve_array(self, node: dict[str, Any], key: str) -> list[Any] | None:
        return self.get_array(self.pointer(node, key))

    def resolve_string(self, node: dict[str, Any], key: str) -> str | None:
        return self.get_string(self.pointer(node, key))

    def resolve_int(self, node: dict[str, Any], key: str) -> int | None:
        return self.get_int(self.pointer(node, key))

    def resolve_date(self, node: dict[str, Any], key: str) -> datetime | None:
        values = self.resolve_array(node, key)
        if not values or len(values) < 2 or not isinstance(values[1], str):
            return None
        try:
            return datetime.fromisoformat(values[1].replace("Z", "+00:00")).astimezone(timezone.utc)
        except ValueError:
            return None

    def series_summary(self, node: dict[str, Any]) -> dict[str, str] | None:
        name = self.resolve_string(node, "name")
        slug = self.resolve_string(node, "slug")
        if not name or not slug:
            return None
        return {"name": name, "slug": slug}

    def series_list(self, indices: list[Any]) -> list[dict[str, str]]:
        result = []
        for index in indices:
            if not isinstance(index, int) or isinstance(index, bool):
                continue
            item = self.series_summary(self.get_object(index) or {})
            if item:
                result.append(item)
        return result


def request(session: requests.Session, url: str, *, image: bool = False) -> requests.Response:
    try:
        response = session.get(
            url,
            headers={"Accept": "image/avif,image/webp,image/*,*/*;q=0.8"} if image else "*/*",
            timeout=TIMEOUT,
        )
    except requests.RequestException as exc:
        raise ProbeFailure(f"{'image-' if image else ''}network={type(exc).__name__}") from None
    if response.status_code != 200:
        raise ProbeFailure(f"{'image-' if image else ''}http={response.status_code}")
    return response


def get_svelte(session: requests.Session, url: str) -> tuple[SvelteData, dict[str, Any]]:
    response = request(session, url)
    try:
        payload = response.json()
        nodes = payload["nodes"]
        data = next(node["data"] for node in reversed(nodes) if node.get("type") == "data")
    except (ValueError, KeyError, TypeError, StopIteration):
        raise ProbeFailure("response=invalid-svelte-data") from None
    if not isinstance(data, list) or not data or not isinstance(data[0], dict):
        raise ProbeFailure("response=missing-svelte-root")
    return SvelteData(data), data[0]


def catalog_sample(session: requests.Session, candidate: Candidate) -> dict[str, Any]:
    titles: list[str] = []
    slugs: set[str] = set()
    pages = 0
    truncated = False
    for page in range(1, MAX_CATALOG_PAGES + 1):
        url = (
            f"{candidate.base_url}/manga/__data.json?page={page}"
            "&x-sveltekit-invalidated=001&sort=popular"
        )
        svelte, root = get_svelte(session, url)
        series_array = svelte.resolve_array(root, "series")
        if series_array is None:
            raise ProbeFailure(f"catalog=missing-series-page-{page}")
        series = svelte.series_list(series_array)
        if not series:
            break
        pages = page
        for item in series:
            if item["slug"] not in slugs:
                slugs.add(item["slug"])
                titles.append(item["name"])
        if len(series_array) < PAGE_SIZE:
            break
        if page == MAX_CATALOG_PAGES:
            truncated = True
        time.sleep(0.35)
    if not titles:
        raise ProbeFailure("catalog=empty")
    return {
        "catalogPagesScanned": pages,
        "catalogSeriesFound": len(titles),
        "catalogScanComplete": not truncated,
        "catalogCountIsMinimum": truncated,
        "sampledPopularTitles": titles[:10],
    }


def decode_image(session: requests.Session, url: str) -> dict[str, Any]:
    response = request(session, url, image=True)
    body = response.content
    if len(body) <= 100:
        raise ProbeFailure(f"image-bytes={len(body)}")
    try:
        with Image.open(io.BytesIO(body)) as image:
            image.load()
            width, height = image.size
            image_format = image.format or "unknown"
    except Exception as exc:
        raise ProbeFailure(f"image-decode={type(exc).__name__}") from None
    if width <= 0 or height <= 0:
        raise ProbeFailure("image-decode=empty-dimensions")
    return {
        "bytes": len(body),
        "contentType": response.headers.get("Content-Type", "").split(";")[0],
        "format": image_format,
        "width": width,
        "height": height,
    }


def image_url(candidate: Candidate, path: str) -> str:
    if path.startswith("http"):
        return path
    return f"{candidate.cdn_url.rstrip('/')}/{path.lstrip('/')}"


def chapters(svelte: SvelteData, series: dict[str, Any]) -> list[dict[str, Any]]:
    array = svelte.resolve_array(series, "SeriesEpisode") or []
    result = []
    for index in array:
        if not isinstance(index, int) or isinstance(index, bool):
            continue
        chapter = svelte.get_object(index)
        if not chapter:
            continue
        slug = svelte.resolve_string(chapter, "slug")
        if not slug:
            continue
        order = svelte.resolve_string(chapter, "order") or ""
        result.append({
            "slug": slug,
            "order": order.removesuffix(".0"),
            "date": svelte.resolve_date(chapter, "createdDate"),
        })
    result.sort(key=lambda chapter: chapter["date"] or datetime.min.replace(tzinfo=timezone.utc), reverse=True)
    return result


def reader_probe(
    session: requests.Session,
    candidate: Candidate,
    latest_series: list[dict[str, str]],
) -> dict[str, Any]:
    last_failure = ProbeFailure("chapters=0")
    for item in latest_series[:3]:
        series_slug = item["slug"]
        try:
            svelte, root = get_svelte(
                session,
                f"{candidate.base_url}/manga/{series_slug}/__data.json?x-sveltekit-invalidated=001",
            )
            series = svelte.resolve_object(root, "series")
            if not series:
                raise ProbeFailure("details=missing-series")
            chapter_list = chapters(svelte, series)
            if not chapter_list:
                raise ProbeFailure("chapters=0")
            latest_date = chapter_list[0]["date"]
            for chapter in chapter_list[:4]:
                try:
                    chapter_svelte, chapter_root = get_svelte(
                        session,
                        f"{candidate.base_url}/manga/{series_slug}/{chapter['slug']}/__data.json"
                        "?x-sveltekit-invalidated=001",
                    )
                    episode = chapter_svelte.resolve_object(chapter_root, "episode")
                    images = chapter_svelte.resolve_array(episode or {}, "images")
                    if not images:
                        raise ProbeFailure("pages=0")
                    paths = []
                    for index in images:
                        if isinstance(index, int) and not isinstance(index, bool):
                            path = chapter_svelte.get_string(index)
                            if path:
                                paths.append(path)
                    if not paths:
                        raise ProbeFailure("pages=missing-image-paths")
                    selected = [paths[0]] if len(paths) == 1 else [paths[0], paths[-1]]
                    decoded = [decode_image(session, image_url(candidate, path)) for path in selected]
                    return {
                        "title": item["name"],
                        "chapter": chapter["order"] or chapter["slug"],
                        "chaptersReturned": len(chapter_list),
                        "latestChapterDate": latest_date.isoformat() if latest_date else None,
                        "latestChapterAgeHours": (
                            round((datetime.now(timezone.utc) - latest_date).total_seconds() / 3600, 1)
                            if latest_date else None
                        ),
                        "pages": len(paths),
                        "images": decoded,
                    }
                except ProbeFailure as exc:
                    last_failure = exc
        except ProbeFailure as exc:
            last_failure = exc
    raise last_failure


def probe(candidate: Candidate) -> dict[str, Any]:
    session = requests.Session()
    session.headers.update({
        "User-Agent": USER_AGENT,
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer": candidate.base_url + "/",
        "Origin": candidate.base_url,
    })
    try:
        catalog = catalog_sample(session, candidate)
        svelte, root = get_svelte(session, f"{candidate.base_url}/__data.json?page=1")
        latest = svelte.resolve_object(root, "lastEpisodes")
        latest_array = svelte.resolve_array(latest or {}, "data")
        if latest_array is None:
            raise ProbeFailure("latest=missing-data")
        latest_series = svelte.series_list(latest_array)
        if not latest_series:
            raise ProbeFailure("latest=empty")
        reader = reader_probe(session, candidate, latest_series)
        return {
            "name": candidate.name,
            "extensionVersion": candidate.extension_version,
            "ok": True,
            "latestPageSize": len(latest_array),
            "latestTitles": [item["name"] for item in latest_series[:10]],
            **catalog,
            **reader,
        }
    except Exception as exc:
        return {
            "name": candidate.name,
            "extensionVersion": candidate.extension_version,
            "ok": False,
            "error": str(exc) if isinstance(exc, ProbeFailure) else f"unexpected={type(exc).__name__}",
        }
    finally:
        session.close()


def run() -> dict[str, Any]:
    report: dict[str, Any] = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "sources": {},
    }
    for candidate in CANDIDATES:
        report["sources"][candidate.key] = probe(candidate)
    report["ok"] = any(item["ok"] for item in report["sources"].values())
    report["fullyVerified"] = all(item["ok"] for item in report["sources"].values())
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
    # Each candidate has an independent result; selection can still use successful ones.
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
