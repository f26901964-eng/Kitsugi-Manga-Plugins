#!/usr/bin/env python3
"""TLS fingerprint experiment for Initlise-Next page images.

Answers: does the same signed image URL behave differently when fetched with a
real Chrome TLS fingerprint (curl-impersonate) versus python-requests / plain
curl? 200 => fingerprint-gated (WebView/Chromium fix will work on device);
403 everywhere => datacenter-IP or cookie gate.

Report: reports/initlise-tls-probe.json
"""
from __future__ import annotations

import json
import pathlib
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
REPORT = ROOT / "reports" / "initlise-tls-probe.json"
FLIGHT = re.compile(r'self\.__next_f\.push\(\[1,"(.*?)"\]\)</script>', re.S)
UA = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)

SITES = {
    "duckscans": {"base": "https://duckscans.com", "series": "hazbin-otelde-cinayet"},
}
CURL_IMPERSONATE = "https://github.com/lwthiker/curl-impersonate/releases/download/v0.6.1/curl-impersonate-v0.6.1.x86_64-linux-gnu.tar.gz"


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


def signed_first_page(base: str, slug: str, jar_path: str) -> tuple[str | None, int]:
    """Okuyucu API'sinden imzalı ilk sayfa yolunu al (+ çerezleri jar'a yaz)."""
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})
    r = s.get(f"{base}/seri/{slug}/", timeout=30)
    if r.status_code != 200:
        print(f"{slug}: series page {r.status_code}", file=sys.stderr)
        return None, 0
    flight = "".join(unescape_js(m.group(1)) for m in FLIGHT.finditer(r.text))
    chapters = json.loads(flight_array(flight, "initialChapters"))
    first = next((c for c in chapters if c.get("chapterNumber")), None)
    if not first:
        return None, 0
    number = first["chapterNumber"]
    api = s.get(
        f"{base}/api/chapters?series={slug}&chapter={number}&includepages=true&limit=1",
        headers={
            "Referer": f"{base}/seri/{slug}/",
            "Origin": base,
            "Accept": "*/*",
            "Sec-Fetch-Dest": "empty",
            "Sec-Fetch-Mode": "cors",
            "Sec-Fetch-Site": "same-origin",
        },
        timeout=30,
    )
    if api.status_code != 200:
        print(f"{slug}: api {api.status_code}", file=sys.stderr)
        return None, number
    pages = json.loads(api.text)["data"][0].get("pages") or []
    if not pages:
        return None, number
    path = pages[0]
    if not path.startswith("http"):
        path = base + path.replace(" ", "%20")
    open(jar_path, "w").write("")
    for cookie in s.cookies:
        open(jar_path, "a").write(
            f"{cookie.domain}\tFALSE\t{cookie.path}\tFALSE\t{cookie.expires or 0}\t{cookie.name}\t{cookie.value}\n"
        )
    return path, number


def fetch_status(cmd: list[str], workdir: Path | None = None) -> dict:
    write_out = "'{\"code\":%{http_code},\"size\":%{size_download},\"ct\":\"%{content_type}\"}'"
    try:
        if workdir is not None:
            # curl-impersonate sarmalayıcısı kendi libcurl'ünü bulmalı
            cmd = ["bash", "-c", f'cd "{workdir}" && LD_LIBRARY_PATH="{workdir}" ' + " ".join(cmd)]
        proc = subprocess.run(cmd[:-1] + ["-w", write_out, "-o", "/dev/null"], capture_output=True, timeout=40, text=False)
        line = proc.stdout.decode(errors="replace").strip()
        try:
            parsed = json.loads(line)
            return {"http": parsed.get("code"), "bytes": parsed.get("size"), "ct": (parsed.get("ct") or "")[:30]}
        except json.JSONDecodeError:
            return {"raw": line[:80], "curlCode": proc.returncode}
    except subprocess.TimeoutExpired:
        return {"error": "timeout"}


def main() -> int:
    workdir = Path(tempfile.mkdtemp(prefix="impersonate-"))
    tar = workdir / "ci.tar.gz"
    tar.write_bytes(requests.get(CURL_IMPERSONATE, timeout=60).content)
    subprocess.run(["tar", "xzf", str(tar), "-C", str(workdir)], check=True)
    chrome = workdir / "curl_chrome116"
    if not chrome.exists():
        candidates = list(workdir.glob("curl_chrome*"))
        chrome = candidates[0]

    report = {"probedAt": datetime.now(timezone.utc).isoformat(), "sites": {}}
    for name, cfg in SITES.items():
        jar = str(workdir / f"{name}.jar")
        url, number = signed_first_page(cfg["base"], cfg["series"], jar)
        if not url:
            report["sites"][name] = {"error": "imzalı URL alınamadı", "chapter": number}
            continue
        out: dict = {"signedUrl": url[:120]}
        try:
            rr = requests.get(url, headers={"User-Agent": UA, "Referer": f"{cfg['base']}/seri/{cfg['series']}/"}, timeout=30, allow_redirects=False)
            out["python-requests"] = {"http": rr.status_code, "bytes": len(rr.content), "ct": rr.headers.get("content-type", "")[:30]}
        except requests.RequestException as exc:
            out["python-requests"] = {"error": type(exc).__name__}
        out["curl-plain"] = fetch_status(["curl", "-s", "-A", UA, "-e", f"{cfg['base']}/seri/{cfg['series']}/", url])
        out["curl-chrome116"] = fetch_status([str(chrome), "-s", "-A", UA, "-e", f"{cfg['base']}/seri/{cfg['series']}/", url], workdir)
        out["curl-chrome116-cookies"] = fetch_status([str(chrome), "-s", "-b", jar, "-c", jar, "-A", UA, "-e", f"{cfg['base']}/seri/{cfg['series']}/", url], workdir)
        report["sites"][name] = out

    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
