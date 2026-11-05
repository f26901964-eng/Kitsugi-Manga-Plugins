#!/usr/bin/env python3
"""Compare live fourth-source candidates without persisting response bodies.

The UzayManga URL/devalue rules and Mangitto API/CDN rules mirror production. Only
public titles, counts, timestamps, HTTP/image metadata and sanitized errors are reported.
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
# The 2026-11-06 Chromium run ended in unresolved challenges on all three domains.
# Do not repeatedly retry those interactive checks on every subsequent probe.
ATTEMPT_CLOUDFLARE_CLEARANCE = False


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
            headers=(
                {"Accept": "image/avif,image/webp,image/*,*/*;q=0.8"}
                if image else {"Accept": "*/*", "X-Requested-With": "XMLHttpRequest"}
            ),
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
        data = next(
            node["data"]
            for node in reversed(nodes)
            if isinstance(node, dict) and node.get("type") == "data"
        )
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


def browser_clearance(session: requests.Session, candidate: Candidate) -> None:
    """Let Chromium solve a non-interactive challenge, as the app's WebView does."""
    try:
        from playwright.sync_api import sync_playwright
    except ModuleNotFoundError:
        raise ProbeFailure("browser=playwright-unavailable") from None
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(
                headless=True,
                args=["--no-sandbox", "--disable-blink-features=AutomationControlled"],
            )
            context = browser.new_context(
                user_agent=USER_AGENT,
                locale="tr-TR",
                viewport={"width": 412, "height": 915},
                is_mobile=True,
                has_touch=True,
            )
            page = context.new_page()
            page.goto(candidate.base_url + "/", wait_until="domcontentloaded", timeout=45_000)
            challenge_markers = (
                "just a moment", "bir dakika", "attention required",
                "security check", "checking your browser", "doğrulan",
            )
            for _ in range(12):
                title = page.title().strip().lower()
                if title and not any(marker in title for marker in challenge_markers):
                    break
                page.wait_for_timeout(5_000)
            else:
                raise ProbeFailure("browser=unresolved-challenge")
            user_agent = page.evaluate("navigator.userAgent")
            if isinstance(user_agent, str) and user_agent:
                session.headers["User-Agent"] = user_agent
            for cookie in context.cookies(candidate.base_url):
                session.cookies.set(
                    cookie["name"], cookie["value"],
                    domain=cookie.get("domain"), path=cookie.get("path", "/"),
                )
            browser.close()
    except ProbeFailure:
        raise
    except Exception as exc:
        raise ProbeFailure(f"browser={type(exc).__name__}") from None


def probe(candidate: Candidate) -> dict[str, Any]:
    session = requests.Session()
    session.headers.update({
        "User-Agent": USER_AGENT,
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer": candidate.base_url + "/",
        "Origin": candidate.base_url,
    })
    stage = "catalog"
    browser_used = False
    try:
        try:
            catalog = catalog_sample(session, candidate)
        except ProbeFailure as exc:
            if "http=403" not in str(exc):
                raise
            if not ATTEMPT_CLOUDFLARE_CLEARANCE:
                raise ProbeFailure(f"{exc};browser=unresolved-challenge-not-retried") from None
            stage = "browser-clearance"
            browser_clearance(session, candidate)
            browser_used = True
            stage = "catalog-after-clearance"
            catalog = catalog_sample(session, candidate)
        stage = "latest"
        svelte, root = get_svelte(session, f"{candidate.base_url}/__data.json?page=1")
        latest = svelte.resolve_object(root, "lastEpisodes")
        latest_array = svelte.resolve_array(latest or {}, "data")
        if latest_array is None:
            raise ProbeFailure("latest=missing-data")
        latest_series = svelte.series_list(latest_array)
        if not latest_series:
            raise ProbeFailure("latest=empty")
        stage = "reader"
        reader = reader_probe(session, candidate, latest_series)
        return {
            "name": candidate.name,
            "archivedExtensionVersion": candidate.extension_version,
            "requestMode": "upstream-XHR",
            "browserClearanceUsed": browser_used,
            "ok": True,
            "latestPageSize": len(latest_array),
            "latestTitles": [item["name"] for item in latest_series[:10]],
            **catalog,
            **reader,
        }
    except Exception as exc:
        location = "unknown"
        traceback = exc.__traceback__
        if traceback is not None:
            while traceback.tb_next is not None:
                traceback = traceback.tb_next
            location = f"{traceback.tb_frame.f_code.co_name}:{traceback.tb_lineno}"
        return {
            "name": candidate.name,
            "archivedExtensionVersion": candidate.extension_version,
            "requestMode": "upstream-XHR",
            "browserClearanceUsed": browser_used,
            "ok": False,
            "error": (
                f"{stage}:{exc}" if isinstance(exc, ProbeFailure)
                else f"{stage}:unexpected={type(exc).__name__}@{location}"
            ),
        }
    finally:
        session.close()


def mangitto_json(session: requests.Session, url: str) -> dict[str, Any]:
    try:
        response = session.get(url, headers={"Accept": "application/json"}, timeout=TIMEOUT)
    except requests.RequestException as exc:
        raise ProbeFailure(f"network={type(exc).__name__}") from None
    if response.status_code != 200:
        raise ProbeFailure(f"http={response.status_code}")
    try:
        payload = response.json()
    except ValueError:
        raise ProbeFailure("response=not-json") from None
    if not isinstance(payload, dict):
        raise ProbeFailure("response=not-object")
    return payload


def mangitto_chapter_number(value: Any) -> str:
    if not isinstance(value, (int, float)) or isinstance(value, bool):
        raise ProbeFailure("chapter=invalid-number")
    return str(int(value)) if float(value).is_integer() else str(value)


def optional_date(item: dict[str, Any]) -> str | None:
    for key in ("createdAt", "created_at", "publishAt", "publish_at", "releaseDate", "updatedAt"):
        value = item.get(key)
        if isinstance(value, str) and value:
            return value
    return None


def date_age_hours(value: str | None) -> float | None:
    if not value:
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return round((datetime.now(timezone.utc) - parsed.astimezone(timezone.utc)).total_seconds() / 3600, 1)
    except ValueError:
        return None


def probe_mangitto() -> dict[str, Any]:
    base_url = "https://mangtto.com"
    api_url = f"{base_url}/api/manga"
    session = requests.Session()
    session.headers.update({
        "User-Agent": USER_AGENT,
        "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
        "Referer": base_url + "/",
        "Origin": base_url,
    })
    stage = "popular"
    browser_used = False
    try:
        try:
            popular = mangitto_json(session, f"{api_url}/populer?skip=0&take=50")
        except ProbeFailure as exc:
            if "http=403" not in str(exc):
                raise
            stage = "browser-clearance"
            browser_clearance(
                session,
                Candidate("mangitto", "Mangitto", base_url, "", "1.6.1"),
            )
            browser_used = True
            stage = "popular-after-clearance"
            popular = mangitto_json(session, f"{api_url}/populer?skip=0&take=50")
        popular_items = popular.get("mangas")
        total = popular.get("total")
        if not isinstance(popular_items, list) or not popular_items or not isinstance(total, int):
            raise ProbeFailure("popular=invalid-response")
        stage = "latest"
        latest = mangitto_json(session, f"{api_url}/latest?skip=0&take=50")
        latest_items = latest.get("chapters")
        if not isinstance(latest_items, list) or not latest_items:
            raise ProbeFailure("latest=empty")
        latest_series = []
        latest_dates = {}
        for entry in latest_items:
            if not isinstance(entry, dict) or not isinstance(entry.get("manga"), dict):
                continue
            manga = entry["manga"]
            title, slug = manga.get("title"), manga.get("slug")
            if not isinstance(title, str) or not isinstance(slug, str) or not slug:
                continue
            if slug not in latest_dates:
                latest_series.append(manga)
                latest_dates[slug] = optional_date(entry)
        if not latest_series:
            raise ProbeFailure("latest=missing-series")
        stage = "reader"
        last_failure = ProbeFailure("chapters=0")
        reader = None
        for manga in latest_series[:3]:
            slug = manga["slug"]
            try:
                details = mangitto_json(session, f"{api_url}/{slug}")
                if details.get("slug") != slug or not isinstance(details.get("title"), str):
                    raise ProbeFailure("details=invalid-response")
                all_chapters = []
                total_chapters = None
                for skip in range(0, 2000, 50):
                    page = mangitto_json(session, f"{api_url}/{slug}/chapters?skip={skip}&take=50")
                    chapter_page = page.get("chapters")
                    if not isinstance(chapter_page, list):
                        raise ProbeFailure("chapters=invalid-response")
                    all_chapters.extend(chapter_page)
                    total_chapters = page.get("total")
                    if not chapter_page or not isinstance(total_chapters, int) or len(all_chapters) >= total_chapters:
                        break
                if not all_chapters:
                    raise ProbeFailure("chapters=0")
                if not isinstance(total_chapters, int) or len(all_chapters) < total_chapters:
                    raise ProbeFailure("chapters=truncated")
                latest_chapter = max(
                    (item for item in all_chapters if isinstance(item, dict)),
                    key=lambda item: float(item.get("chapter", float("-inf"))),
                )
                chapter = mangitto_chapter_number(latest_chapter.get("chapter"))
                page_data = mangitto_json(session, f"{api_url}/{slug}/{chapter}")
                uploads = page_data.get("uploads")
                cdn = page_data.get("cdn")
                if not isinstance(uploads, list) or not uploads or not isinstance(cdn, str):
                    raise ProbeFailure("pages=missing-upload")
                upload = uploads[0]
                length, fansub = upload.get("fileLength"), upload.get("fansubId")
                if not isinstance(length, int) or length <= 0 or not isinstance(fansub, str):
                    raise ProbeFailure("pages=invalid-upload")
                page_numbers = [1] if length == 1 else [1, length]
                images = [
                    decode_image(session, f"{cdn.rstrip('/')}/manga/{slug}/{chapter}/{number}-{fansub}.webp")
                    for number in page_numbers
                ]
                reader = {
                    "title": manga["title"],
                    "chapter": chapter,
                    "chaptersReturned": total_chapters if isinstance(total_chapters, int) else len(all_chapters),
                    "latestChapterDate": latest_dates.get(slug),
                    "latestChapterAgeHours": date_age_hours(latest_dates.get(slug)),
                    "pages": length,
                    "images": images,
                }
                break
            except ProbeFailure as exc:
                last_failure = exc
        if reader is None:
            raise last_failure
        popular_titles = [item.get("title") for item in popular_items if isinstance(item, dict)]
        return {
            "name": "Mangitto",
            "archivedExtensionVersion": "1.6.1",
            "requestMode": "production-direct",
            "browserClearanceUsed": browser_used,
            "ok": True,
            "latestPageSize": len(latest_items),
            "latestTitles": [item["title"] for item in latest_series[:10]],
            "catalogPagesScanned": 1,
            "catalogSeriesFound": total,
            "catalogScanComplete": True,
            "catalogCountIsMinimum": False,
            "catalogCountSource": "api-total",
            "sampledPopularTitles": [title for title in popular_titles if isinstance(title, str)][:10],
            **reader,
        }
    except Exception as exc:
        return {
            "name": "Mangitto",
            "archivedExtensionVersion": "1.6.1",
            "requestMode": "production-direct",
            "browserClearanceUsed": browser_used,
            "ok": False,
            "error": f"{stage}:{exc}" if isinstance(exc, ProbeFailure) else f"{stage}:unexpected={type(exc).__name__}",
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
    report["sources"]["mangitto"] = probe_mangitto()
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
