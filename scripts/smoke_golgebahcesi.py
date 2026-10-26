#!/usr/bin/env python3
"""Direct-network contract smoke test, NOT an Android/WebView end-to-end test.

Exit 0: API and sampled plain images responded with image signatures.
Exit 1: network/contract/image failure. Exit 2: secure/locked chapter needs a device.
No cookies, tokens, payment locks or Cloudflare challenges are bypassed.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys
from typing import Callable
from urllib.parse import quote, urlencode, urljoin, urlparse
from urllib.request import Request, urlopen

SITE = "https://golgebahcesi.com"
API = "https://api.golgebahcesi.com/api"
USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36"


class NeedsDevice(Exception):
    pass


def fetch(url: str, referer: str, maximum: int) -> bytes:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Unsafe source URL")
    request = Request(url, headers={"User-Agent": USER_AGENT, "Referer": referer, "Origin": SITE})
    with urlopen(request, timeout=25) as response:
        data = response.read(maximum + 1)
        if not data or len(data) > maximum:
            raise ValueError("Empty or oversized response")
        return data


def fetch_json(url: str):
    return json.loads(fetch(url, SITE + "/", 8 * 1024 * 1024))


def fetch_image(url: str, referer: str) -> bytes:
    return fetch(url, referer, 32 * 1024 * 1024)


def image_type(data: bytes) -> str:
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "png"
    if data.startswith(b"\xff\xd8\xff"):
        return "jpeg"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    if data.startswith((b"GIF87a", b"GIF89a")):
        return "gif"
    if data[4:8] == b"ftyp" and any(brand in data[8:32] for brand in (b"avif", b"avis")):
        return "avif"
    raise ValueError("Page returned HTML, encrypted bytes or an unsupported image signature")


def check(
    series_slug: str | None = None,
    query: str | None = None,
    chapter_id: str | None = None,
    get_json: Callable = fetch_json,
    get_image: Callable = fetch_image,
) -> dict:
    popular = get_json(API + "/series?" + urlencode({"page": 1, "limit": 24, "sort": "popular"}))
    if not isinstance(popular, dict) or not isinstance(popular.get("data"), list) or not popular["data"]:
        raise ValueError("Popular catalogue is empty or has changed schema")
    slug = series_slug or popular["data"][0]["slug"]
    encoded = quote(slug, safe="")
    details = get_json(f"{API}/series/{encoded}")
    if details.get("slug") != slug or not details.get("title"):
        raise ValueError("Details do not match the selected manga")
    search = get_json(API + "/series?" + urlencode({"page": 1, "limit": 24, "search": query or details["title"]}))
    if not isinstance(search.get("data"), list) or not any(item.get("slug") == slug for item in search["data"]):
        raise ValueError("Search could not find the selected manga")
    chapters = get_json(f"{API}/series/{encoded}/chapters")
    if not isinstance(chapters, list) or not chapters:
        raise ValueError("No chapters returned")
    if chapter_id:
        selected = next((item for item in chapters if str(item.get("id")) == chapter_id), None)
        if selected is None:
            raise ValueError("Requested chapter is not in this manga")
    else:
        available = [item for item in chapters if not item.get("isLocked", False)]
        if not available:
            raise NeedsDevice("All chapters are locked; source-site access is required")
        selected = max(available, key=lambda item: float(item.get("number", 0)))
    if selected.get("isLocked", False):
        raise NeedsDevice("Selected chapter is locked; no reader or image request was made")
    content = get_json(f"{API}/chapters/{quote(str(selected['id']), safe='')}")
    if content.get("id") != selected["id"] or content.get("seriesSlug") != slug or content.get("slug") != selected["slug"]:
        raise ValueError("Chapter identity mismatch")
    if content.get("isLocked", False):
        raise NeedsDevice("Chapter requires access on the source website")
    if str(content.get("deliverySystem", "")).lower() == "secure":
        raise NeedsDevice("API/search/chapters reached; secure pages require the native Android WebView reader. NOT a full pass.")
    pages = content.get("pages")
    if not isinstance(pages, list) or not pages:
        raise ValueError("Chapter returned no readable pages")
    pages = sorted(pages, key=lambda item: item["index"])
    if len({page["index"] for page in pages}) != len(pages):
        raise ValueError("Duplicate page index")
    reader = f"{SITE}/manga/{encoded}/bolum/{quote(selected['slug'], safe='')}"
    samples = []
    for index in sorted({0, len(pages) // 2, len(pages) - 1}):
        url = urljoin(SITE, pages[index]["url"])
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("Unsafe page URL")
        data = get_image(url, reader)
        samples.append({"position": index, "type": image_type(data), "bytes": len(data)})
    return {
        "status": "network_contract_ok",
        "android_verified": False,
        "series": slug,
        "title": details["title"],
        "chapter_id": selected["id"],
        "chapter_count": len(chapters),
        "page_count": len(pages),
        "samples": samples,
        "note": "Image signatures only, not Android bitmap decoding or native-adapter/device validation.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--series", help="Known manga slug; otherwise selects the first popular result")
    parser.add_argument("--query", help="Search text expected to find that manga")
    parser.add_argument("--chapter-id", help="A specific accessible chapter ID")
    parser.add_argument("--report", type=Path, help="Optional JSON report path")
    args = parser.parse_args()
    try:
        result, code = check(args.series, args.query, args.chapter_id), 0
    except NeedsDevice as error:
        result, code = {"status": "needs_device", "android_verified": False, "reason": str(error)}, 2
    except Exception as error:
        result, code = {"status": "failed", "android_verified": False, "reason": str(error)}, 1
    text = json.dumps(result, ensure_ascii=False, indent=2)
    print(text)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text + "\n", encoding="utf-8")
    return code


if __name__ == "__main__":
    sys.exit(main())
