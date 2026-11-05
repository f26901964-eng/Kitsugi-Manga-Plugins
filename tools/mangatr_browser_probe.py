"""Capture Manga-TR's public chapter responses with the site's own browser flow.

No cookies, signatures, gate keys, or request bodies are written to disk. The saved
chapter responses are the same public HTML fragments shown to anonymous readers.
"""
from __future__ import annotations

import json
import re
import shutil
from pathlib import Path
from urllib.parse import parse_qs, urlparse

import requests
from playwright.sync_api import sync_playwright

BASE = "https://manga-tr.com/"
DETAIL = BASE + "manga-chainsaw-man.html"
READER = BASE + "id-123931-read-chainsaw-man-chapter-2.html"
READER_SCRIPT = BASE + "app/manga/themes/default/assets/js/chapter-reader.js?v=2709-2"
OUT = Path("mangatr/live/browser")
OUT.mkdir(parents=True, exist_ok=True)
UA = (
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)


def clear_ddos_guard() -> requests.Session:
    session = requests.Session()
    session.headers["User-Agent"] = UA
    response = session.get(BASE, timeout=30)
    if response.status_code == 403 and "check.ddos-guard.net/check.js" in response.text:
        check = session.get("https://check.ddos-guard.net/check.js", timeout=30)
        match = re.search(r"'([^']+)'", check.text)
        if match and match.group(1).startswith("/") and not match.group(1).startswith("//"):
            gate = session.get(BASE.rstrip("/") + match.group(1), timeout=30)
            print("DDoS-Guard exchange:", gate.status_code)
    return session


def main() -> None:
    session = clear_ddos_guard()
    reader_script_response = session.get(READER_SCRIPT, timeout=30)
    if reader_script_response.status_code == 200:
        (OUT / "chapter-reader.js").write_text(reader_script_response.text, encoding="utf-8")
    chrome = shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser")
    if not chrome:
        raise RuntimeError("Chrome/Chromium is required for the public browser probe")

    captured: dict[str, str] = {}
    report: dict[str, object] = {
        "chapter_requests": [],
        "chapter_index": {},
        "image_requests": [],
        "reader_script": {"status": reader_script_response.status_code, "bytes": len(reader_script_response.content)},
        "reader_script_responses": [],
        "reader_gate_responses": [],
        "reader_errors": [],
    }

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=chrome, headless=True, args=["--no-sandbox"])
        context = browser.new_context(user_agent=UA)
        context.add_cookies(
            [
                {"name": c.name, "value": c.value, "domain": c.domain, "path": c.path or "/"}
                for c in session.cookies
            ]
        )
        page = context.new_page()
        page.on(
            "pageerror",
            lambda error: report["reader_errors"].append(
                re.sub(r"[A-Za-z0-9_\-+/=]{24,}", "<redacted>", str(error))[:500]
            ),
        )

        def on_response(response) -> None:
            parsed = urlparse(response.url)
            hostname = parsed.hostname or ""
            if parsed.path.endswith("/chapter-reader.js"):
                report["reader_script_responses"].append({"status": response.status, "type": response.request.resource_type})
                return
            if parsed.path.startswith("/cek/f/"):
                report["reader_gate_responses"].append({"status": response.status, "type": response.request.resource_type})
                return
            if "mngtr" in hostname and response.request.resource_type == "image":
                if len(report["image_requests"]) >= 12:
                    return
                headers = response.request.headers
                safe_headers = {
                    key: headers.get(key, "")
                    for key in (
                        "accept",
                        "referer",
                        "sec-fetch-dest",
                        "sec-fetch-mode",
                        "sec-fetch-site",
                        "user-agent",
                    )
                }
                response_headers = response.headers
                report["image_requests"].append(
                    {
                        "host": hostname,
                        "status": response.status,
                        "headers": safe_headers,
                        "response": {
                            key: response_headers.get(key, "")
                            for key in ("content-type", "server", "cf-mitigated", "cache-control")
                        },
                    }
                )
                print(f"Reader image host={hostname}: status={response.status}")
                return
            if parsed.path != "/cek/fetch_pages_manga.php" or response.request.method != "POST":
                return
            try:
                body = response.text()
            except Exception:
                return
            fields = parse_qs(response.request.post_data or "")
            offset = fields.get("offset", ["0"])[0]
            captured[offset] = body
            headers = response.request.headers
            safe_headers = {
                key: headers.get(key, "")
                for key in (
                    "accept",
                    "content-type",
                    "origin",
                    "referer",
                    "sec-fetch-dest",
                    "sec-fetch-mode",
                    "sec-fetch-site",
                    "x-requested-with",
                )
            }
            report["chapter_requests"].append(
                {
                    "offset": offset,
                    "status": response.status,
                    "bytes": len(body),
                    "episode_cards": body.count("bento-ep-card"),
                    "headers": safe_headers,
                }
            )
            print(
                f"Chapter response offset={offset}: status={response.status}, "
                f"bytes={len(body)}, cards={body.count('bento-ep-card')}"
            )

        page.on("response", on_response)
        response = page.goto(DETAIL, wait_until="domcontentloaded", timeout=45_000)
        print("Detail status:", response.status if response else None)
        page.wait_for_selector("#results .bento-ep-card", timeout=30_000)
        page.wait_for_timeout(1_000)

        # The public chapter-navbar index is also a useful unauthenticated fallback.
        index_result = page.evaluate(
            """async () => {
                const response = await fetch('/cek/ajax_chapters_endpoint.php?ajax=chapters&manga=chainsaw-man&offset=0', {
                    headers: {'X-Requested-With': 'XMLHttpRequest'}
                });
                return {status: response.status, body: await response.text()};
            }"""
        )
        report["chapter_index"] = {"status": index_result["status"]}
        if index_result["status"] == 200:
            index_body = index_result["body"]
            (OUT / "chainsaw_chapter_index.json").write_text(index_body, encoding="utf-8")
            try:
                rows = json.loads(index_body).get("rows", [])
                report["chapter_index"] = {"status": 200, "rows": len(rows)}
            except (ValueError, TypeError):
                report["chapter_index"] = {"status": 200, "rows": "invalid JSON"}

        # Capture the first 20 + the next two batches of 100. This includes the
        # early Chainsaw Man chapters that have several independent translations.
        for expected_offset in ("20", "120"):
            if page.locator("#btn-load-more").is_visible():
                with page.expect_response(
                    lambda response, offset=expected_offset: (
                        urlparse(response.url).path == "/cek/fetch_pages_manga.php"
                        and response.request.method == "POST"
                        and parse_qs(response.request.post_data or "").get("offset", ["0"])[0] == offset
                    ),
                    timeout=30_000,
                ):
                    page.locator("#btn-load-more").click()
                page.wait_for_timeout(1_000)
            if expected_offset not in captured:
                print("Warning: offset was not captured:", expected_offset)

        reader_response = page.goto(READER, wait_until="domcontentloaded", timeout=45_000)
        report["reader_status"] = reader_response.status if reader_response else None
        print("Reader status:", report["reader_status"])
        page.wait_for_timeout(8_000)
        if not report["image_requests"]:
            page.mouse.wheel(0, 1_500)
            page.wait_for_timeout(5_000)
        report["reader_title"] = page.title()
        report["reader_dom"] = page.evaluate(
            """() => ({
                imageCount: document.images.length,
                readerConfigCount: document.querySelectorAll('script[id^="rdm-"]').length,
                images: Array.from(document.images).slice(0, 30).map(image => ({
                    host: new URL(image.currentSrc || image.src, location.href).hostname,
                    complete: image.complete,
                    naturalWidth: image.naturalWidth,
                })),
            })"""
        )

        browser.close()

    for offset, body in captured.items():
        (OUT / f"chainsaw_chapters_offset{offset}.html").write_text(body, encoding="utf-8")
    report["captured_offsets"] = sorted(captured, key=int)
    report["rendered_cards"] = sum(item["episode_cards"] for item in report["chapter_requests"])
    (OUT / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))

    if "0" not in captured or not captured["0"].strip() or "bento-ep-card" not in captured["0"]:
        raise RuntimeError("The public chapter response was not captured")
    if not any("fansub" in body.lower() for body in captured.values()):
        raise RuntimeError("No fansub information was found in the public chapter responses")


if __name__ == "__main__":
    main()
