#!/usr/bin/env python3
"""Reader-crypto probe for Initlise-Next image protection.

Replays the EXACT fetch the site reader performs (chunk 22g9e7_wv23lw.js):
  1. GET /api/settings            -> runtime flags (scramble/crypto/hcaptcha)
  2. GET /api/chapters (signed pages)
  3. POST /api/reader-guard/verify (full body!)
  4. GET signed page + cv=5 + X-Duck-Scramble-Version:5 (+ cookies)

Report: reports/initlise-reader-crypto-probe.json
"""
from __future__ import annotations

import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "reports" / "initlise-reader-crypto-probe.json"
UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
FLIGHT = re.compile(r'self\.__next_f\.push\(\[1,"(.*?)"\]\)</script>', re.S)
SITES = {
    "duckscans": {"base": "https://duckscans.com", "series": "hazbin-otelde-cinayet", "scrambleHeader": "X-Duck-Scramble-Version"},
    "oblivionscans": {"base": "https://oblivionscans.com", "series": "patron-kizinizi-istiyorum", "scrambleHeader": "X-Duck-Scramble-Version"},
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


def probe(name: str, cfg: dict) -> dict:
    base, slug = cfg["base"], cfg["series"]
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})
    out: dict = {"base": base}

    settings = s.get(f"{base}/api/settings", timeout=30)
    out["settingsStatus"] = settings.status_code
    try:
        body = settings.json()
        runtime = body.get("runtime") or {}
        out["runtimeFlags"] = {
            k: runtime.get(k) for k in ("scramble", "scrambleVersion", "readerCrypto", "hcaptcha", "readerGuard", "reader") if k in runtime
        }
        if not out["runtimeFlags"]:
            out["runtimeKeys"] = list(runtime.keys())[:20]
    except ValueError:
        out["runtimeFlags"] = "parse-yok"

    r = s.get(f"{base}/seri/{slug}/", timeout=30)
    flight = "".join(unescape_js(m.group(1)) for m in FLIGHT.finditer(r.text))
    chapters = json.loads(flight_array(flight, "initialChapters"))
    first = next((c for c in chapters if c.get("chapterNumber")), None)
    number = first["chapterNumber"]

    api = s.get(
        f"{base}/api/chapters?series={slug}&chapter={number}&includepages=true&limit=1",
        headers={
            "Referer": f"{base}/seri/{slug}/bolum/{number}",
            "Origin": base,
            "Accept": "*/*",
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-origin",
        },
        timeout=30,
    )
    out["apiStatus"] = api.status_code
    pages = json.loads(api.text)["data"][0].get("pages") or []
    if not pages:
        out["pages"] = "bos"
        return out
    raw_path = pages[0]
    out["signedPathSample"] = raw_path[:120]

    guard = s.post(
        f"{base}/api/reader-guard/verify",
        headers={"Referer": f"{base}/seri/{slug}/bolum/{number}", "Origin": base, "Content-Type": "application/json"},
        json={},
        timeout=30,
    )
    out["guardStatus"] = guard.status_code
    out["guardBody"] = guard.text[:400]
    out["guardSetCookie"] = guard.headers.get("set-cookie", "")[:120]

    # Okuyucunun birebir fetch'i: cv=5 param + scramble header + image Accept
    url = base + raw_path.replace(" ", "%20")
    sep = "&" if "?" in url else "?"
    url_cv = f"{url}{sep}cv=5"
    img_headers = {
        "Accept": "image/webp,image/*;q=0.8",
        cfg["scrambleHeader"]: "5",
        "Referer": f"{base}/seri/{slug}/bolum/{number}",
        "Sec-Fetch-Dest": "image",
        "Sec-Fetch-Mode": "no-cors",
        "Sec-Fetch-Site": "same-origin",
    }
    for label, target, hdrs in {
        "plain-signed": url, "cv5+scramble": url_cv,
    }.items():
        rr = s.get(target, headers=hdrs, timeout=30)
        info = {
            "status": rr.status_code,
            "ct": rr.headers.get("content-type", "")[:40],
            "bytes": len(rr.content),
            "guardHeader": rr.headers.get("x-duck-reader-guard", rr.headers.get(cfg["scrambleHeader"].lower(), ""))[:40],
            "first4": rr.content[:4].hex() if rr.content else "",
        }
        if rr.status_code == 200 and info["ct"].startswith("image/"):
            info["IMAGE_OK"] = True
        out[label] = info
    return out


def main() -> int:
    report = {"probedAt": datetime.now(timezone.utc).isoformat(), "sites": {}}
    for name, cfg in SITES.items():
        try:
            report["sites"][name] = probe(name, cfg)
        except Exception as exc:  # noqa: BLE001
            report["sites"][name] = {"error": f"{type(exc).__name__}: {exc}"[:200]}
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
