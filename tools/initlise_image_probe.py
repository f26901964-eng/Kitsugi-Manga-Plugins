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
    out["chapter"] = {"number": first.get("chapterNumber"), "id": first.get("_id"), "samplePath": raw_path}
    series_id = re.search(r'"seriesId":"([a-f0-9]{24})"', flight).group(1)

    chapter_url = f"{base}/seri/{slug}/bolum/{first.get('chapterNumber')}"
    variants = {
        "encoded-no-headers": (raw_path.replace(" ", "%20"), {}),
        "encoded-root-referer": (raw_path.replace(" ", "%20"), {"Referer": f"{base}/"}),
        "encoded-chapter-referer": (raw_path.replace(" ", "%20"), {"Referer": chapter_url}),
        "raw-space-chapter-referer": (raw_path, {"Referer": chapter_url}),
    }
    out["imageVariants"] = {label: try_get(s, base + path, headers) for label, (path, headers) in variants.items()}

    api = f"{base}/api/chapters?series={series_id}&chapter={first.get('_id')}&includepages=true&limit=1"
    out["includePagesApi"] = try_get(s, api, {"Referer": chapter_url})
    if out["includePagesApi"].get("status") == 200 and "image" not in out["includePagesApi"].get("type", ""):
        body = s.get(api, timeout=TIMEOUT).text
        out["includePagesApi"]["sample"] = body[:300]
        m = re.search(r'https?://[^"\\\s]+?\.(?:webp|jpe?g|png)[^"\\\s]*', body)
        if m:
            out["apiImageUrl"] = m.group(0)
            out["apiImageFetch"] = try_get(s, m.group(0), {"Referer": chapter_url})

    cover = f"{base}/uploads/seri-kapak/{slug}.webp"
    out["coverProbe"] = try_get(s, cover, {"Referer": f"{base}/"})
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
