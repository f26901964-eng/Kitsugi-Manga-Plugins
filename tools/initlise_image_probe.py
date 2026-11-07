#!/usr/bin/env python3
"""Probe whether Initlise-Next page images are anonymously fetchable.

The four extensions resolve page paths from the series page flight data, but the
user reports images never load on device. This probe fetches a REAL embedded page
URL per site with several header combinations, plus the /api/chapters
includepages endpoint the site reader itself calls, and records statuses so the
fix can be evidence-based.

Usage: python tools/initlise_image_probe.py   (report: reports/initlise-image-probe.json)
"""
from __future__ import annotations

import json
import pathlib
import urllib.parse
import re
import sys
from datetime import datetime, timezone

import requests

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPORT = ROOT / "reports" / "initlise-image-probe.json"

MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
FLIGHT = re.compile(r'self\.__next_f\.push\(\[1,"(.*?)"\]\)</script>', re.S)
TIMEOUT = 30

SITES = {
    "duckscans": {"base": "https://duckscans.com", "series": "hazbin-otelde-cinayet"},
    "oblivionscans": {"base": "https://oblivionscans.com", "series": "patron-kizinizi-istiyorum"},
    "lunariscans": {"base": "https://lunariscans.com", "series": "ana-karakter-sagligima-takintili"},
    "ailurascans": {"base": "https://ailurascans.com", "series": "hooky"},
}


def unescape_js(raw: str) -> str:
    out, i = [], 0
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            n = raw[i + 1]
            if n == "u" and i + 6 <= len(raw):
                try:
                    out.append(chr(int(raw[i + 2 : i + 6], 16)))
                    i += 6
                    continue
                except ValueError:
                    pass
            out.append({"n": "\n", "t": "\t"}.get(n, n))
            i += 2
        else:
            out.append(c)
            i += 1
    return "".join(out)


def flight_of(html: str) -> str:
    return "".join(unescape_js(m.group(1)) for m in FLIGHT.finditer(html))


def flight_array(flight: str, key: str) -> str | None:
    ki = flight.find(f'"{key}":')
    if ki < 0:
        return None
    start = flight.find("[", ki)
    depth = instr = esc = 0
    for i in range(start, len(flight)):
        c = flight[i]
        if esc:
            esc = 0
            continue
        if c == "\\" and instr:
            esc = 1
            continue
        if c == '"':
            instr = not instr
            continue
        if instr:
            continue
        if c == "[":
            depth += 1
        elif c == "]":
            depth -= 1
            if depth == 0:
                return flight[start : i + 1]
    return None


def try_get(session: requests.Session, url: str, headers: dict) -> dict:
    try:
        r = session.get(url, headers=headers, timeout=TIMEOUT)
    except requests.RequestException as exc:
        return {"error": type(exc).__name__}
    info = {"status": r.status_code, "type": r.headers.get("content-type", "")[:30], "bytes": len(r.content)}
    if r.status_code == 200 and "image" in r.headers.get("content-type", ""):
        info["ok"] = True
    elif r.status_code == 200:
        info["head"] = r.text[:80]
    return info


def probe_site(name: str, base: str, slug: str) -> dict:
    s = requests.Session()
    s.headers.update({"User-Agent": MOBILE_UA, "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.8"})
    out: dict = {"base": base, "series": slug}

    r = s.get(f"{base}/seri/{slug}/", timeout=TIMEOUT)
    out["seriesPage"] = {"status": r.status_code}
    if r.status_code != 200:
        return out

    flight = flight_of(r.text)
    pages_raw = flight_array(flight, "initialChapters")
    if not pages_raw:
        out["chapters"] = "flight-yok"
        return out
    chapters = json.loads(pages_raw)
    first = next((c for c in chapters if c.get("pages")), None)
    if not first:
        out["chapters"] = "sayfa-yok"
        return out

    raw_path = first["pages"][0]
    series_mongo = re.search(r'"seriesId":"([a-f0-9]{24})"', flight).group(1)
    series_num = re.search(r'"_id":"[a-f0-9]{24}","id":(\d+)', flight)
    out["chapter"] = {
        "number": first.get("chapterNumber"),
        "mongoId": first.get("_id"),
        "slug": first.get("slug"),
        "samplePath": raw_path,
        "seriesMongo": series_mongo,
        "seriesNumeric": series_num.group(1) if series_num else None,
    }

    chapter_url = f"{base}/seri/{slug}/bolum/{first.get('chapterNumber')}"

    # --- API parametre matrisi: okuyucunun includepages çağrısı hangi
    #     kimlikle çalışıyor? (gövde ilk 200 bayt hata mesajını verir)
    combos = {
        "slug+chslug": f"series={slug}&chapter={first.get('slug')}",
        "mongo+mongo": f"series={series_mongo}&chapter={first.get('_id')}",
        "mongo+chslug": f"series={series_mongo}&chapter={first.get('slug')}",
        "slug+mongo": f"series={slug}&chapter={first.get('_id')}",
    }
    api_out = {}
    for label, q in combos.items():
        info = try_get(s, f"{base}/api/chapters?{q}&includepages=true&limit=1", {"Referer": chapter_url})
        if info.get("status") not in (None, 200):
            try:
                body = s.get(f"{base}/api/chapters?{q}&includepages=true&limit=1", timeout=TIMEOUT).text
                info["body"] = body[:220]
            except requests.RequestException:
                pass
        if info.get("status") == 200:
            body = s.get(f"{base}/api/chapters?{q}&includepages=true&limit=1", timeout=TIMEOUT).text
            info["body"] = body[:600]
            m = re.search(r'https?://[^"\\\s]+?\.(?:webp|jpe?g|png)[^"\\\s]*', body)
            if m:
                info["signedUrlFound"] = True
                info["signedUrlFetch"] = try_get(s, m.group(0), {"Referer": chapter_url})
                out["apiSignedUrl"] = m.group(0)
                out["apiSignedCombo"] = label
        api_out[label] = info
    out["apiMatrix"] = api_out

    # --- Okuyucu rotasının JS chunk'ını kanıt olarak indir; URL imzalama
    #     mantığı yerel inceleme için burada olmalı.
    reader = s.get(chapter_url, timeout=TIMEOUT)
    if reader.status_code == 200:
        chunks = re.findall(r'src="(/_next/static/chunks/[^"]+)"', reader.text)
        saved = []
        folder = ROOT / "docs" / "html-dumps" / name / "chunks"
        folder.mkdir(parents=True, exist_ok=True)
        for chunk in chunks[:24]:
            try:
                js = s.get(base + chunk, timeout=TIMEOUT).text
            except requests.RequestException:
                continue
            if "includepages" in js or "uploads/" in js:
                target = folder / chunk.split("/")[-1].split("?")[0]
                target.write_text(js, encoding="utf-8")
                saved.append(target.name)
        out["readerChunksSaved"] = saved

    # --- Tarayıcı benzeri tam başlık seti + _next/image optimizer denemeleri
    encoded = base + raw_path.replace(" ", "%20")
    browser_headers = {
        "Referer": chapter_url,
        "Accept": "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
        "Sec-Fetch-Dest": "image",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Site": "same-origin",
        "sec-ch-ua": '"Chromium";v="131", "Not_A Brand";v="24"',
        "sec-ch-ua-mobile": "?1",
        "sec-ch-ua-platform": '"Android"',
    }
    out["imageBrowserHeaders"] = try_get(s, encoded, browser_headers)

    optimizer = f"{base}/_next/image?url={urllib.parse.quote(raw_path)}&w=828&q=75"
    out["imageOptimizer"] = try_get(s, optimizer, {"Referer": chapter_url})
    return out


def main() -> int:
    report = {"probedAt": datetime.now(timezone.utc).isoformat(), "sites": {}}
    for name, cfg in SITES.items():
        report["sites"][name] = probe_site(name, cfg["base"], cfg["series"])

    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name, data in report["sites"].items():
        print(f"== {name}")
        for label, info in (data.get("imageVariants") or {}).items():
            print(f"   {label}: {info}")
        print(f"   includePagesApi: {data.get('includePagesApi')}")
        if data.get("apiImageUrl"):
            print(f"   apiImageUrl: {data['apiImageUrl'][:100]} -> {data.get('apiImageFetch')}")
        print(f"   cover: {data.get('coverProbe')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
