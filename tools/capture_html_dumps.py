#!/usr/bin/env python3
"""Download the shared HTML dumps (Google Drive) into the workspace for inspection.

The dumps are only used as evidence while fixing selectors; the files are written under
docs/html-dumps/<site>/.
"""
from __future__ import annotations

import pathlib
import sys

import requests

FILES = {
    "hayalistic": {
        "62": "1UxOOT2guZM1tyb8KY3NDIOat-dYv-OjG",
        "61": "1H2R5QkZVhRBSswbpMALpG9cbMiYfEuRf",
        "60": "1kBiAuHPrvT3dKj2Kbc5b5_3VqBhDBdlX",
        "59": "1IQ7zq6x0pWtj440EBd3axc2-PfGja6VI",
    },
    "korelimanga": {
        "66": "19j9qSODFNkO9wDqIv68QTzT2EFoQA0D0",
        "65": "1_x6z1IMQ-U-gQtOtlIF7zebNxjOT3_Mn",
        "64": "1gjto70cnqnDzep6hxHKQsZfn_067kzFA",
        "63": "1v6-8YcU-ijc9o7_pEyiMC_u9ZMdxEuB2",
    },
    "trmangaoku": {
        "69": "1EhNwL5-8Asryi8HXyYZ7J2PDqMKD2Emu",
        "68": "1369LMb1IDfOcOavgOd6g7UqFMEMiMX_w",
        "67": "1nhNKA9iL5ZIxrmX4kNUph8SdZ4RudmHo",
        "66": "1SaX8Aph5GkkKO3pZmtSVIsf0KdupuWq5",
    },
    # 2026-11-06, okutoon.com: the four pages the user saved (plain text dumps of the HTML).
    "okutoon": {
        "70": "1pIlu5CG1J5FRlkHon2N-rnrGKzxVTQD-",
        "71": "1Vnx8amQGrhunaJDz2LHwvtEya4hrkQjK",
        "72": "1vCPkYUSqWbYttTVfxK19k-NwrVx85_3T",
        "73": "1UsfbIoIegI5-oki3dQjBHKP1dKxwqln0",
    },
    # 2026-11-06, epiknovel.com: the four pages the user saved from the shared folder
    # https://drive.google.com/drive/folders/1sK8YqkBznh1k8ZYFqKBXgnykDLazJDsZ
    # 97 = ana sayfa, 98 = seri-listesi?q=Against+The+God (arama),
    # 99 = seri/against-the-god (seri detayı + bölüm tablosu), 100 = bolum/1731 (okuyucu).
    "epiknovel": {
        "97": "15e8eUMJdsygUBsy8Pb6Xvabxkb5_sukz",
        "98": "1M7K1sz4H-v7bBDjLa2AA0Z_SvqCveK4E",
        "99": "1vnx_Thr0lmXSIvr1_XBOYOFvFanBRG_E",
        "100": "1dCMygQYvNZ-cCHEFhC9g3AXMh44Aj4pt",
    },
}

# Live pages fetched straight from the site (no Cloudflare on epiknovel). These keep the
# fixtures current when the site changes and cover pages the user never saved: the default
# listing with Sayfa pagination and a completed (durum=1) series status.
LIVE = {
    "epiknovel": {
        "live-home": "https://www.epiknovel.com/",
        "live-list": "https://www.epiknovel.com/seri-listesi",
        "live-list-page2": "https://www.epiknovel.com/seri-listesi?Sayfa=2",
        "live-search": "https://www.epiknovel.com/seri-listesi?q=Against+The+God",
        "live-list-completed": "https://www.epiknovel.com/seri-listesi?durum=1",
        "live-series-completed": "https://www.epiknovel.com/seri/dimensional-sovereign",
        "live-chapter": "https://www.epiknovel.com/seri/against-the-god/bolum/1731",
    },
}

URL = "https://drive.usercontent.google.com/download?id={id}&export=download&confirm=t"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"}

# The okutoon and epiknovel dumps were saved from the browser as text, so they keep the .txt name.
TEXT_SITES = {"okutoon", "epiknovel"}


def main() -> int:
    root = pathlib.Path("docs/html-dumps")
    only = sys.argv[1:] or list(FILES) + list(LIVE)
    for site in only:
        folder = root / site
        folder.mkdir(parents=True, exist_ok=True)
        for tag, file_id in FILES.get(site, {}).items():
            response = requests.get(URL.format(id=file_id), headers=UA, timeout=60, allow_redirects=True)
            body = response.content
            suffix = ".txt" if site in TEXT_SITES else ".html"
            target = folder / f"message-{tag}{suffix}"
            target.write_bytes(body)
            markup = (b"<html" in body[:2000]) or (b"<!DOCTYPE" in body[:400])
            print(f"{site}/{target.name}: status={response.status_code} bytes={len(body)} markup={bool(markup)}")
        for tag, url in LIVE.get(site, {}).items():
            response = requests.get(url, headers=UA, timeout=60, allow_redirects=True)
            body = response.content
            target = folder / f"{tag}.html"
            target.write_bytes(body)
            markup = (b"<html" in body[:2000]) or (b"<!DOCTYPE" in body[:400])
            print(f"{site}/{target.name}: status={response.status_code} bytes={len(body)} markup={bool(markup)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
