#!/usr/bin/env python3
"""Download the shared HTML dumps (Google Drive) into the workspace for inspection.

The dumps are only used as local evidence while fixing selectors; the files are
written under docs/html-dumps/<site>/.
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
}

URL = "https://drive.usercontent.google.com/download?id={id}&export=download&confirm=t"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36"}


def main() -> int:
    root = pathlib.Path("docs/html-dumps")
    for site, ids in FILES.items():
        folder = root / site
        folder.mkdir(parents=True, exist_ok=True)
        for tag, file_id in ids.items():
            response = requests.get(URL.format(id=file_id), headers=UA, timeout=60, allow_redirects=True)
            body = response.content
            ok = response.ok and b"<!DOCTYPE html>" in body[:400] or b"<html" in body[:2000]
            target = folder / f"message-{tag}.html"
            target.write_bytes(body)
            print(f"{site}/{target.name}: status={response.status_code} bytes={len(body)} html={bool(ok)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
