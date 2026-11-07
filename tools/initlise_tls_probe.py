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
    "duckscans": {"base": "https://duckscans.com", "series": "hazbin-otelde-cinayet", "chapter": 48},
    "oblivionscans": {"base": "https://oblivionscans.com", "series": "patron-kizinizi-istiyorum", "chapter": 1},
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


def signed_first_page(base: str, slug: str, number: int, jar_path: str) -> str | None:
    s = requests.Session()
    s.headers.update({"User-Agent": UA, "Accept-Language": "tr-TR,tr;q=0.9"})
    r = s.get(f"{base}/seri/{slug}/", timeout=30)
    if r.status_code != 200:
        print(f"{slug}: series page {r.status_code}", file=sys.stderr)
        return None
    flight = "".join(unescape_js(m.group(1)) for m in FLIGHT.finditer(r.text))
    m = re.search(rf'"chapterNumber":{number}\b.{0,2000}?"pages":\["([^"]+)"', flight, re.S)
    if not m:
        return None
    path = m.group(1)
    if not path.startswith("http"):
        path = base + path
    # API yanıtı çerezler de verebilir; oturumu jar'a yaz.
    open(jar_path, "w").write("")
    for cookie in s.cookies:
        open(jar_path, "a").write(
            f"{cookie.domain}\tFALSE\t{cookie.path}\tFALSE\t{cookie.expires or 0}\t{cookie.name}\t{cookie.value}\n"
        )
    return path


def fetch_status(cmd: list[str]) -> dict:
    try:
        proc = subprocess.run(cmd, capture_output=True, timeout=40, text=False)
        head = proc.stdout[:120]
        ctype = ""
        for line in proc.stderr.decode(errors="replace").splitlines():
            if line.lower().startswith("content-type:"):
                ctype = line.split(":", 1)[1].strip()
        return {"status": proc.returncode and None or 200, "curlCode": proc.returncode, "type": ctype[:30], "bytes": len(proc.stdout), "head": head[:40].hex()[:40] if not ctype else ""}
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
        url = signed_first_page(cfg["base"], cfg["series"], cfg["chapter"], jar)
        if not url:
            report["sites"][name] = {"error": "imzalı URL alınamadı"}
            continue
        out: dict = {"signedUrl": url[:110]}
        out["python-requests"] = fetch_status(["python3", "-c", f"""
import urllib.request
req = urllib.request.Request({url!r}, headers={{'User-Agent': {UA!r}, 'Referer': {cfg['base']!r}}})
try:
    r = urllib.request.urlopen(req, timeout=30)
    print(r.status, len(r.read()), r.headers.get('content-type',''))
except Exception as e:
    print('ERR', e)
"""])
        out["curl-plain"] = fetch_status([
            "curl", "-s", "-o", "/dev/null", "-w", "%{http_code} %{size_download} %{content_type}",
            "-A", UA, "-e", f"{cfg['base']}/", url,
        ])
        out["curl-chrome116"] = fetch_status([
            str(chrome), "-s", "-o", "/dev/null", "-w", "%{http_code} %{size_download} %{content_type}",
            "-A", UA, "-e", f"{cfg['base']}/", url,
        ])
        out["curl-chrome116-cookies"] = fetch_status([
            str(chrome), "-s", "-o", "/dev/null", "-w", "%{http_code} %{size_download} %{content_type}",
            "-b", jar, "-c", jar, "-A", UA, "-e", f"{cfg['base']}/", url,
        ])
        report["sites"][name] = out

    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
