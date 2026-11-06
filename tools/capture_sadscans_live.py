"""sadscans.net'in GERÇEK tarayıcıdaki hâlini kaydeder.

Neden: eklenti hakkında verilen kararlar şimdiye kadar canlı HTML'e değil, tahminlere ve
kullanıcının gönderdiği dökümlere dayanıyordu. Bu betik Chromium ile siteye girer, sayfaların
son hâlini (client-side render sonrası) HTML olarak kaydeder ve seçicilerin gerçekten var olup
olmadığını raporlar.

Çıktılar:
  docs/html-dumps/sadscans/*.html   — sayfaların son HTML'i
  docs/html-dumps/sadscans/*.png    — ekran görüntüleri
  reports/sadscans-capture.json     — seçici/sayaç/transport tanıları
"""

from __future__ import annotations

import json
import pathlib
import sys
import time
import traceback

from playwright.sync_api import sync_playwright

BASE = "https://sadscans.net"
OUT = pathlib.Path("docs/html-dumps/sadscans")
REPORT = pathlib.Path("reports/sadscans-capture.json")
UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

report: dict = {"base": BASE, "pages": {}, "transport": {}, "notes": []}


def sfx(page, name: str, html: str | None = None) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    try:
        if html is None:
            html = page.content()
        (OUT / f"{name}.html").write_text(html, encoding="utf-8")
    except Exception as exc:  # noqa: BLE001
        report["notes"].append(f"{name}: HTML yazılamadı: {exc}")
    try:
        page.screenshot(path=str(OUT / f"{name}.png"), full_page=False)
    except Exception as exc:  # noqa: BLE001
        report["notes"].append(f"{name}: ekran görüntüsü alınamadı: {exc}")


def blocked(page) -> bool:
    try:
        text = page.inner_text("body")[:4000]
    except Exception:  # noqa: BLE001
        return False
    return "Sorry, you have been blocked" in text or "Attention Required" in text


def settle(page, seconds: float) -> None:
    """Cloudflare JS challenge'ı geçebilir; sabırla bekle."""
    deadline = time.time() + seconds
    while time.time() < deadline:
        if blocked(page):
            page.wait_for_timeout(2000)
            continue
        return


def main() -> int:
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, args=["--no-sandbox", "--disable-dev-shm-usage"])
        context = browser.new_context(user_agent=UA, locale="tr-TR", viewport={"width": 1366, "height": 900})
        page = context.new_page()

        # ---------------------------------------------------------------- /seriler
        entry: dict = {}
        try:
            response = page.goto(f"{BASE}/seriler", wait_until="domcontentloaded", timeout=60000)
            entry["status"] = response.status if response else None
            settle(page, 45)
            page.wait_for_timeout(9000)
            entry["blocked"] = blocked(page)
            entry["title"] = page.title()
            entry["search_inputs"] = page.eval_on_selector_all(
                "input", "els => els.map(e => ({placeholder: e.placeholder, name: e.name, type: e.type}))"
            )
            entry["dropdown_containers"] = page.eval_on_selector_all(
                ".dropdown-container", "els => els.map(e => e.innerText.trim().slice(0, 80))"
            )
            entry["card_selectors"] = {
                'a[href^="/seriler/"] h3': page.eval_on_selector_all('a[href^="/seriler/"] h3', "e => e.length"),
                'a[href*="/seriler/"]': page.eval_on_selector_all('a[href*="/seriler/"]', "e => e.length"),
                "h3": page.eval_on_selector_all("h3", "e => e.length"),
            }
            entry["first_cards"] = page.eval_on_selector_all(
                'a[href*="/seriler/"]',
                "els => els.slice(0, 10).map(a => ({href: a.getAttribute('href'), text: a.innerText.trim().slice(0, 60)}))",
            )
            entry["sentinel"] = page.eval_on_selector_all(
                'div[aria-hidden="true"].h-px.w-full', "e => e.length"
            )
            sfx(page, "seriler")

            # ------------------------------------------------------- arama (site kutusu)
            try:
                box = page.locator('input[placeholder*="Aramak"], input[placeholder*="ara"]').first
                box.click(timeout=5000)
                box.fill("")
                box.type("chainsaw", delay=120)
                page.wait_for_timeout(9000)
                entry["search_cards"] = page.eval_on_selector_all(
                    'a[href*="/seriler/"]',
                    "els => els.slice(0, 10).map(a => a.getAttribute('href'))",
                )
                entry["search_url"] = page.url
                sfx(page, "seriler-arama")
            except Exception as exc:  # noqa: BLE001
                entry["search_error"] = f"{type(exc).__name__}: {exc}"

            # ------------------------------------------------ aynı kaynaklı tRPC (kademe 2)
            try:
                transport = page.evaluate(
                    """async () => {
                        const url = '/api/trpc/_s4d7g2k9m0.getSeries?batch=1&input=' +
                            encodeURIComponent(JSON.stringify({0: {json: {page: 1, limit: 15, sortBy: '-views'}}}));
                        try {
                            const response = await fetch(url, {credentials: 'same-origin', headers: {accept: 'application/json'}});
                            const text = await response.text();
                            return {status: response.status, length: text.length, head: text.slice(0, 400)};
                        } catch (error) {
                            return {error: String(error)};
                        }
                    }"""
                )
                report["transport"]["page_fetch_getSeries"] = transport
            except Exception as exc:  # noqa: BLE001
                report["transport"]["page_fetch_getSeries"] = {"error": f"{type(exc).__name__}: {exc}"}
        except Exception as exc:  # noqa: BLE001
            entry["error"] = f"{type(exc).__name__}: {exc}"
            entry["trace"] = traceback.format_exc()[-1500:]
        report["pages"]["seriler"] = entry

        # ---------------------------------------------------------------- seri detayı
        detail: dict = {}
        href = None
        for card in entry.get("first_cards", []) or []:
            candidate = card.get("href") or ""
            if candidate.startswith("/seriler/") and candidate.count("/") >= 2:
                href = candidate
                break
        if href:
            try:
                response = page.goto(BASE + href, wait_until="domcontentloaded", timeout=60000)
                detail["status"] = response.status if response else None
                settle(page, 30)
                page.wait_for_timeout(9000)
                detail["blocked"] = blocked(page)
                detail["href"] = href
                detail["title"] = page.title()
                detail["body_head"] = page.inner_text("body")[:600]
                detail["chapter_links"] = {
                    'a[href*="/reader/"]': page.eval_on_selector_all('a[href*="/reader/"]', "e => e.length"),
                    'a[href*="/reader/"] h3': page.eval_on_selector_all('a[href*="/reader/"] h3', "e => e.length"),
                }
                detail["first_chapters"] = page.eval_on_selector_all(
                    'a[href*="/reader/"]',
                    "els => els.slice(0, 8).map(a => ({href: a.getAttribute('href'), text: a.innerText.trim().slice(0, 50)}))",
                )
                detail["pager_buttons"] = page.eval_on_selector_all(
                    "button", "els => els.map(e => (e.getAttribute('aria-label') || e.innerText || '').trim()).filter(Boolean).slice(0, 25)"
                )
                sfx(page, "seri-detay")
                report["pages"]["detail"] = detail
            except Exception as exc:  # noqa: BLE001
                detail["error"] = f"{type(exc).__name__}: {exc}"
                report["pages"]["detail"] = detail
        else:
            report["pages"]["detail"] = {"skipped": "kart bağlantısı bulunamadı"}

        # ---------------------------------------------------------------- okuyucu
        reader: dict = {}
        chapter = None
        for item in (detail.get("first_chapters") or []):
            candidate = item.get("href") or ""
            if candidate.startswith("/reader/"):
                chapter = candidate
                break
        if chapter:
            try:
                response = page.goto(BASE + chapter, wait_until="domcontentloaded", timeout=60000)
                reader["status"] = response.status if response else None
                settle(page, 30)
                for _ in range(6):
                    page.wait_for_timeout(2500)
                    page.mouse.wheel(0, 2200)
                reader["blocked"] = blocked(page)
                reader["href"] = chapter
                reader["title"] = page.title()
                reader["images"] = page.eval_on_selector_all(
                    "img",
                    """els => els.map(img => ({
                        src: img.getAttribute('src'),
                        current: img.currentSrc || null,
                        data_src: img.getAttribute('data-src'),
                        data_lazy: img.getAttribute('data-lazy-src'),
                        srcset: img.getAttribute('srcset'),
                        loading: img.getAttribute('loading'),
                        width: img.naturalWidth,
                        height: img.naturalHeight,
                        alt: (img.getAttribute('alt') || '').slice(0, 40)
                    })).slice(0, 40)""",
                )
                reader["img_count"] = len(reader["images"])
                sfx(page, "okuyucu")
                report["pages"]["reader"] = reader
            except Exception as exc:  # noqa: BLE001
                reader["error"] = f"{type(exc).__name__}: {exc}"
                report["pages"]["reader"] = reader
        else:
            report["pages"]["reader"] = {"skipped": "bölüm bağlantısı bulunamadı"}

        context.close()
        browser.close()

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2)[:6000])
    return 0


if __name__ == "__main__":
    sys.exit(main())
