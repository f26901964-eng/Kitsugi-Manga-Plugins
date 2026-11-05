#!/usr/bin/env python3
"""Verify Manga-TR's seed/flip implementation against the site's live Chromium DOM."""
from __future__ import annotations

import base64
import hashlib
import io
import json
import re
import shutil
from pathlib import Path
from urllib.parse import parse_qs, quote, urlparse

from bs4 import BeautifulSoup
from PIL import Image, ImageDraw
from playwright.sync_api import expect, sync_playwright

from mangatr_browser_probe import BASE, UA, clear_ddos_guard

READER_ID = "125451"
READER = BASE + f"id-{READER_ID}-read-chainsaw-man-chapter-1.html?fs_done=1"
SAMPLE_PAGES = (0, 1, 2, 26, 51)
MODULE_BUILD = Path("keiyoushi-extensions-tr/src/tr/mangatr/build")
CORRECTED_OUT = MODULE_BUILD / "mangatr-live" / f"reader-{READER_ID}"
SITE_OUT = MODULE_BUILD / "mangatr-browser-site" / f"reader-{READER_ID}"
REPORT_OUT = MODULE_BUILD / "mangatr-orientation"
PUBLIC_OUT = Path("mangatr/live/browser")
HEX_KEY = re.compile(r"^[0-9a-f]{32}$")


def decrypt(value: str, key: str) -> str:
    normalized = value.replace("-", "+").replace("_", "/")
    encrypted = base64.b64decode(normalized + "=" * (-len(normalized) % 4))
    key_bytes = key.encode()
    plain = bytes(byte ^ key_bytes[index % len(key_bytes)] for index, byte in enumerate(encrypted))
    return plain.decode("utf-8")


def scramble_codes(seed: int, grid: int) -> list[int]:
    state = seed & 0xFFFFFFFF
    if state == 0:
        state = 123456789

    def next_uint() -> int:
        nonlocal state
        state = (state ^ ((state << 13) & 0xFFFFFFFF)) & 0xFFFFFFFF
        state = (state ^ (state >> 17)) & 0xFFFFFFFF
        state = (state ^ ((state << 5) & 0xFFFFFFFF)) & 0xFFFFFFFF
        return state

    destinations = list(range(grid))
    for index in range(grid - 1, 0, -1):
        other = next_uint() % (index + 1)
        destinations[index], destinations[other] = destinations[other], destinations[index]
    return [next_uint() % 4 * 100 + destinations[source] for source in range(grid)]


def attribute(element, name: str) -> str:
    return str(element.get(name) or element.get(name.removeprefix("data-")) or "")


def corrected_image(source: Image.Image, codes: list[int]) -> Image.Image:
    grid = len(codes)
    strip_height = source.height // grid
    if strip_height <= 0:
        raise RuntimeError("Image is shorter than its reader grid")
    output = Image.new("RGB", (source.width, strip_height * grid))
    for source_index, code in enumerate(codes):
        strip = source.crop((0, source_index * strip_height, source.width, (source_index + 1) * strip_height))
        if code // 100 & 1:
            strip = strip.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        if code // 100 & 2:
            strip = strip.transpose(Image.Transpose.FLIP_TOP_BOTTOM)
        output.paste(strip, (0, (code % 100) * strip_height))
    return output


def synthetic_image(url: str) -> bytes:
    digest = hashlib.sha256(url.encode()).hexdigest()
    image = Image.new("RGB", (800, 1200), "white")
    draw = ImageDraw.Draw(image)
    for index in range(2):
        top = index * 600
        draw.rectangle((0, top, 800, top + 600), fill=(235 - index * 30, 245 - index * 20, 255))
        draw.rectangle((20, top + 20, 780, top + 580), outline="black", width=5)
        draw.text((55, top + 70), f"SCRAMBLED STRIP {index}", fill="black", font_size=42)
        draw.text((55, top + 150), f"PAGE {digest[:8].upper()}", fill="black", font_size=36)
        draw.text((55, top + 240), "LEFT RIGHT TOP BOTTOM 0123456789", fill="black", font_size=28)
    output = io.BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def redact(value: object) -> str:
    return re.sub(r"[A-Za-z0-9_\-+/=]{24,}", "<redacted>", str(value))[:1000]


def main() -> None:
    CORRECTED_OUT.mkdir(parents=True, exist_ok=True)
    SITE_OUT.mkdir(parents=True, exist_ok=True)
    REPORT_OUT.mkdir(parents=True, exist_ok=True)
    PUBLIC_OUT.mkdir(parents=True, exist_ok=True)
    report: dict[str, object] = {"reader": READER_ID, "samples": []}

    def save_report() -> None:
        body = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        (REPORT_OUT / "browser-contract-report.json").write_text(body, encoding="utf-8")
        (PUBLIC_OUT / "reader-contract-report.json").write_text(body, encoding="utf-8")

    session = clear_ddos_guard()
    chrome = shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser")
    launch_options = {"executable_path": chrome} if chrome else {}

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            **launch_options,
            headless=False,
            args=["--no-sandbox", "--disable-blink-features=AutomationControlled"],
        )
        context = browser.new_context(
            user_agent=UA,
            viewport={"width": 412, "height": 915},
            device_scale_factor=2.625,
            is_mobile=True,
            has_touch=True,
            locale="tr-TR",
        )
        context.add_cookies(
            [
                {"name": cookie.name, "value": cookie.value, "domain": cookie.domain, "path": cookie.path or "/"}
                for cookie in session.cookies
            ]
        )
        page = context.new_page()
        gates: list[dict[str, object]] = []
        image_bodies: dict[str, bytes] = {}
        fetched_bodies: dict[str, bytes] = {}
        image_sources: dict[str, str] = {}
        image_response_statuses: list[int] = []
        proxy_attempts: list[dict[str, object]] = []

        def serve_reader_image(route) -> None:
            url = route.request.url
            candidates: list[tuple[int, bytes, str]] = []
            try:
                fetched = route.fetch(timeout=45_000)
                body = fetched.body()
                image_response_statuses.append(fetched.status)
                candidates.append((fetched.status, body, str(fetched.headers.get("server", ""))))
            except Exception:
                image_response_statuses.append(0)

            # Retry exactly like MangaTR.imageRequest rather than a CSS browser
            # request. The root referer and cross-site marker are significant.
            try:
                direct = context.request.get(
                    url,
                    headers={
                        "User-Agent": UA,
                        "Accept": "image/avif,image/webp,image/*,*/*;q=0.8",
                        "Referer": BASE,
                        "Sec-Fetch-Dest": "image",
                        "Sec-Fetch-Mode": "no-cors",
                        "Sec-Fetch-Site": "cross-site",
                    },
                    timeout=45_000,
                )
                candidates.append((direct.status, direct.body(), str(direct.headers.get("server", ""))))
            except Exception:
                pass

            for status, body, _server in candidates:
                if status != 200:
                    continue
                try:
                    with Image.open(io.BytesIO(body)) as probe:
                        image_format = probe.format
                    mime = {"JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}.get(image_format or "")
                except Exception:
                    mime = None
                if mime:
                    fetched_bodies[url] = body
                    image_sources[url] = "live"
                    route.fulfill(status=200, content_type=mime, body=body)
                    return

            # Fetch signed images while they are fresh. The later five-sample
            # fallback cannot prove orientation if a short-lived URL expired.
            try:
                proxied = context.request.get(
                    "https://images.weserv.nl/?url=" + quote(url, safe=""),
                    timeout=8_000,
                )
                proxy_body = proxied.body()
                proxy_attempts.append({"stage": "route", "service": "weserv", "status": proxied.status})
                if proxied.status == 200:
                    with Image.open(io.BytesIO(proxy_body)) as probe:
                        image_format = probe.format
                        probe.verify()
                    mime = {"JPEG": "image/jpeg", "PNG": "image/png", "WEBP": "image/webp"}.get(image_format or "")
                    if mime is None:
                        raise ValueError("proxy returned a non-image body")
                    fetched_bodies[url] = proxy_body
                    image_sources[url] = "live-weserv"
                    route.fulfill(status=200, content_type=mime, body=proxy_body)
                    return
            except Exception as error:
                proxy_attempts.append({"stage": "route", "service": "weserv", "error": redact(error)})

            body = synthetic_image(url)
            fetched_bodies[url] = body
            image_sources[url] = "synthetic"
            route.fulfill(status=200, content_type="image/png", body=body)

        page.route("**/img_part.php*", serve_reader_image)

        def on_response(response) -> None:
            path = urlparse(response.url).path
            try:
                if path.startswith("/cek/f/"):
                    gates.append(
                        {
                            "url": response.url,
                            "status": response.status,
                            "body": response.text(),
                            "post": response.request.post_data or "",
                        }
                    )
                elif path.endswith("/img_part.php") and response.status == 200:
                    image_bodies[response.url] = response.body()
            except Exception:
                # The explicit requests below provide a second, checked path.
                pass

        page.on("response", on_response)
        response = page.goto(READER, wait_until="domcontentloaded", timeout=45_000)
        report["reader_status"] = response.status if response else None
        if response is None or response.status != 200:
            raise RuntimeError(f"Live reader returned {report['reader_status']}")
        raw_html = response.text()
        page.wait_for_timeout(4_000)

        gate = next((item for item in reversed(gates) if item["status"] == 200), None)
        if gate is None:
            raise RuntimeError("The browser did not receive a successful reader gate response")
        gate_data = json.loads(str(gate["body"]))
        gate_key = str(gate_data["k"])
        try:
            post_data = json.loads(str(gate["post"] or "{}"))
        except ValueError:
            post_data = {}
        nonce = str(post_data.get("n") or parse_qs(urlparse(str(gate["url"])).query).get("n", [""])[0])
        pointer = page.evaluate(
            "nonce => typeof window._calcPointerDelta === 'function' ? (window._calcPointerDelta(nonce || '') || '') : ''",
            nonce,
        )
        pointer_source = page.evaluate(
            "() => typeof window._calcPointerDelta === 'function' ? window._calcPointerDelta.toString() : ''"
        )
        (PUBLIC_OUT / "pointer-delta.js").write_text(pointer_source + "\n", encoding="utf-8")
        pointer_inputs = page.evaluate(
            """() => {
                const body = document.body || document.documentElement;
                return {
                    ease: String((window._mouseCurve || {}).ease || ''),
                    flow: String(window.getComputedStyle(body).getPropertyValue('--ui-flow') || '').trim(),
                };
            }"""
        )
        (PUBLIC_OUT / "reader-pointer-input.json").write_text(
            json.dumps(pointer_inputs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        snippets = []
        for token in ("mTr_v4", "--ui-flow", "_mouseCurve"):
            start = raw_html.find(token)
            snippets.append(f"--- {token} ---\n" + (redact(raw_html[max(0, start - 250):start + 500]) if start >= 0 else "not in raw HTML"))
        (PUBLIC_OUT / "reader-pointer-snippets.txt").write_text("\n".join(snippets) + "\n", encoding="utf-8")
        safe_gate_request = dict(post_data)
        for token_name in ("n", "c"):
            if token_name in safe_gate_request:
                safe_gate_request[token_name] = "<redacted>"
        (PUBLIC_OUT / "reader-gate-request.json").write_text(
            json.dumps(safe_gate_request, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        report["pointer_delta_length"] = len(str(pointer))
        save_report()

        soup = BeautifulSoup(raw_html, "html.parser")
        config_script = next((node for node in soup.select("script[id^='rdm-']") if node.get("id")), None)
        if config_script is None:
            raise RuntimeError("Raw reader response did not contain its encrypted config")
        page_key = next((str(value) for value in config_script.attrs.values() if HEX_KEY.match(str(value))), "")
        if not page_key:
            raise RuntimeError("Reader config did not contain a page key")
        encrypted_config = config_script.decode_contents().strip()
        config = None
        used_gate_format = None
        for label, gate_segment in (
            ("browser-pointer", f"{gate_key}|{pointer}"),
            ("empty-pointer", f"{gate_key}|"),
            ("legacy", gate_key),
        ):
            try:
                candidate = json.loads(decrypt(decrypt(encrypted_config, f"gate|{gate_segment}|reader"), f"boot|{page_key}|reader"))
                if candidate.get("data") and candidate.get("key"):
                    config = candidate
                    used_gate_format = label
                    break
            except (ValueError, UnicodeError, KeyError):
                continue
        if config is None:
            raise RuntimeError("None of the browser/current/legacy gate formats decrypted the config")
        report["gate_format"] = used_gate_format

        # Use a fresh reader where the site's automatic gate is aborted. The
        # extension's empty-body request is therefore the first and only gate,
        # rather than an invalid second attempt against a one-time key.
        extension_page = context.new_page()
        extension_page.route("**/cek/f/**", lambda route: route.abort())
        extension_page.route("**/img_part.php*", lambda route: route.abort())
        extension_response = extension_page.goto(READER, wait_until="domcontentloaded", timeout=45_000)
        if extension_response is None or extension_response.status != 200:
            raise RuntimeError("Fresh extension reader could not be loaded")
        extension_html = extension_response.text()
        extension_page.unroute("**/cek/f/**")
        extension_soup = BeautifulSoup(extension_html, "html.parser")
        extension_script = next((node for node in extension_soup.select("script[id^='rdm-']") if node.get("id")), None)
        extension_key_match = re.search(r"_fpx\s*=\s*[\"']([^\"']+)[\"']", extension_html)
        if extension_script is None or extension_key_match is None:
            raise RuntimeError("Fresh extension reader did not expose its config/gate")
        extension_page_key = next(
            (str(value) for value in extension_script.attrs.values() if HEX_KEY.match(str(value))), ""
        )
        extension_encrypted = extension_script.decode_contents().strip()
        extension_gate_url = BASE.rstrip("/") + extension_key_match.group(1)
        extension_nonce = parse_qs(urlparse(extension_gate_url).query).get("n", [""])[0]
        extension_pointer = extension_page.evaluate(
            "nonce => typeof window._calcPointerDelta === 'function' ? (window._calcPointerDelta(nonce || '') || '') : ''",
            extension_nonce,
        )
        report["extension_pointer_delta_length"] = len(str(extension_pointer))
        report["extension_gate_request"] = "n-cv"
        extension_gate = extension_page.evaluate(
            """async gateUrl => {
                const url = new URL(gateUrl);
                const n = url.searchParams.get('n') || '';
                const c = url.searchParams.get('c') || '';
                let cv = '';
                if (c.length >= 16) {
                    const v = [0, 4, 8, 12].map(offset => parseInt(c.slice(offset, offset + 4), 16) || 0);
                    const base = [v[0] * 37 % 200 + 20, v[1] * 41 % 200 + 20, v[2] * 43 % 200 + 20];
                    const extra = [v[1] * 17 % 100 + 10, v[2] * 19 % 100 + 10, v[3] * 23 % 100 + 10];
                    cv = base.join(',') + '|' + base.map((value, index) => Math.min(255, value + extra[index])).join(',');
                }
                const attempt = async options => {
                    const response = await fetch(gateUrl, {...options, credentials: 'include'});
                    return {status: response.status, body: await response.text()};
                };
                let result = await attempt({
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest'},
                    body: JSON.stringify({n, cv}),
                });
                if (result.status !== 200) result = await attempt({method: 'GET'});
                return result;
            }""",
            extension_gate_url,
        )
        if extension_gate["status"] != 200:
            raise RuntimeError(f"Extension gate returned {extension_gate['status']} on its first request")
        extension_gate_key = str(json.loads(extension_gate["body"])["k"])
        extension_gate_format = None
        for label, gate_segment in (
            ("current-pointer", f"{extension_gate_key}|{extension_pointer}"),
            ("empty-pointer", f"{extension_gate_key}|"),
            ("legacy", extension_gate_key),
        ):
            try:
                candidate = json.loads(
                    decrypt(
                        decrypt(extension_encrypted, f"gate|{gate_segment}|reader"),
                        f"boot|{extension_page_key}|reader",
                    )
                )
                if candidate.get("data") and candidate.get("key"):
                    extension_gate_format = label
                    break
            except (ValueError, UnicodeError, KeyError):
                continue
        if extension_gate_format is None:
            report["extension_gate_format"] = None
            report["extension_gate_decrypts"] = False
            save_report()
            extension_page.close()
            raise RuntimeError("Extension n-cv request and current pointer did not decrypt the fresh config")
        extension_page.close()
        page.bring_to_front()
        page.wait_for_timeout(1_000)
        report["group_skip_visible"] = page.locator("#skipGroupBtn").is_visible()
        if report["group_skip_visible"]:
            page.locator("#skipGroupBtn").click()
            page.wait_for_timeout(5_000)
        report["extension_gate_format"] = extension_gate_format
        report["extension_gate_decrypts"] = extension_gate_format is not None
        save_report()

        attributes = config["data"]
        raw_seeds = config.get("seeds") or {}
        seed_items = raw_seeds.items() if isinstance(raw_seeds, dict) else enumerate(raw_seeds)
        seeds = {str(key): int(value) for key, value in seed_items}
        grid = int(config.get("grid") or 2)
        if not seeds:
            raise RuntimeError("Live reader config did not expose current seeds")
        report["grid"] = grid
        report["seed_count"] = len(seeds)
        pages = soup.select(f"[{attributes['parts']}]")
        pages.sort(key=lambda node: int(attribute(node, attributes["pageIndex"]) or 0))
        report["page_count"] = len(pages)
        if len(pages) != 52:
            raise RuntimeError(f"Expected 52 chapter-one pages, found {len(pages)}")

        for position in SAMPLE_PAGES:
            node = pages[position]
            page_index = attribute(node, attributes["pageIndex"])
            seed = seeds.get(page_index, seeds.get("0", 0))
            codes = scramble_codes(seed, grid)
            encrypted_parts = attribute(node, attributes["parts"])
            image_url = json.loads(decrypt(encrypted_parts, f"attr|{page_key}|reader"))[0]

            locator = page.locator(f"[{attributes['parts']}]").nth(position)
            locator.scroll_into_view_if_needed(timeout=30_000)
            slices = locator.locator("[data-reader-slice]")
            try:
                expect(slices).to_have_count(grid, timeout=45_000)
            except Exception:
                report["reader_debug"] = locator.evaluate(
                    """el => ({
                        loaded: el.getAttribute('data-loaded') || el.getAttribute('loaded') || '',
                        children: el.children.length,
                        className: el.className,
                        display: getComputedStyle(el).display,
                        text: el.textContent.slice(0, 120),
                    })"""
                )
                report["image_response_statuses"] = image_response_statuses
                save_report()
                raise
            dom_slices = slices.evaluate_all(
                """(nodes, grid) => nodes.map(node => ({
                    destination: Number(node.getAttribute('data-reader-slice')),
                    backgroundPosition: node.style.backgroundPosition,
                    transform: node.style.transform,
                }))""",
                grid,
            )
            reference_codes = [None] * grid
            for item in dom_slices:
                destination = int(item["destination"])
                y_percent = float(str(item["backgroundPosition"]).split()[1].rstrip("%"))
                source_index = round(y_percent / 100 * (grid - 1)) if grid > 1 else 0
                transform = str(item["transform"])
                if "scale(-1, -1)" in transform:
                    flip = 3
                elif "scaleX(-1)" in transform:
                    flip = 1
                elif "scaleY(-1)" in transform:
                    flip = 2
                else:
                    flip = 0
                reference_codes[source_index] = flip * 100 + destination
            matches = reference_codes == codes
            sample = {
                "page": position + 1,
                "page_index": page_index,
                "generated_codes": codes,
                "dom_codes": reference_codes,
                "matches_live_dom": matches,
            }
            report["samples"].append(sample)
            save_report()
            if not matches:
                raise RuntimeError(f"Page {position + 1} seed/flip codes differ from the live DOM")

            locator.screenshot(path=str(SITE_OUT / f"page-{position + 1}.png"), timeout=30_000)
            body = fetched_bodies.get(image_url) or image_bodies.get(image_url)
            if body is None:
                image_response = context.request.get(
                    image_url,
                    headers={"Referer": READER, "Accept": "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"},
                    timeout=45_000,
                )
                if image_response.status != 200:
                    raise RuntimeError(f"Page {position + 1} image returned {image_response.status}")
                body = image_response.body()
            if image_sources.get(image_url) == "synthetic":
                # Test-only fallbacks for GitHub-runner CDN blocks, limited to
                # the five OCR samples. Runtime extension code never uses them.
                for service, template in (
                    ("weserv", "https://images.weserv.nl/?url="),
                    ("allorigins", "https://api.allorigins.win/raw?url="),
                    ("codetabs", "https://api.codetabs.com/v1/proxy?quest="),
                ):
                    try:
                        proxied = context.request.get(template + quote(image_url, safe=""), timeout=8_000)
                        proxy_body = proxied.body()
                        proxy_attempts.append({"page": position + 1, "service": service, "status": proxied.status})
                        if proxied.status == 200:
                            with Image.open(io.BytesIO(proxy_body)) as probe:
                                probe.verify()
                            body = proxy_body
                            image_sources[image_url] = f"live-{service}"
                            break
                    except Exception as error:
                        proxy_attempts.append({"page": position + 1, "service": service, "error": redact(error)})
            sample["image_source"] = image_sources.get(image_url, "live")
            save_report()
            with Image.open(io.BytesIO(body)) as raw:
                source = raw.convert("RGB")
                source.load()
            corrected = corrected_image(source, codes)
            corrected.save(CORRECTED_OUT / f"page-{position + 1}.png", format="PNG")
            print(
                f"Reader {READER_ID} page {position + 1}: live DOM matches generated order/flips; "
                f"corrected {corrected.width}x{corrected.height}; source={sample['image_source']}"
            )

        browser.close()

    report["dom_verification_passed"] = True
    report["image_response_statuses"] = image_response_statuses
    report["proxy_attempts"] = proxy_attempts
    report["real_images_verified"] = (
        len(report["samples"]) == len(SAMPLE_PAGES)
        and all(str(sample.get("image_source", "")).startswith("live") for sample in report["samples"])
    )
    report["contract_passed"] = bool(report["extension_gate_decrypts"] and report["dom_verification_passed"])
    # Pixel evidence is deliberately a separate result: synthetic images never
    # satisfy real_images_verified and are not used to claim visual acceptance.
    report["passed"] = report["contract_passed"]
    save_report()
    print(json.dumps({key: value for key, value in report.items() if key != "samples"}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        REPORT_OUT.mkdir(parents=True, exist_ok=True)
        (REPORT_OUT / "browser-contract-error.txt").write_text(redact(error) + "\n", encoding="utf-8")
        raise RuntimeError(redact(error)) from error
