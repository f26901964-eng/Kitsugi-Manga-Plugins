#!/usr/bin/env python3
"""Copy a built Manga Diyarı APK and synchronize its version in every catalog.

Run after `:src:tr:mangadiyari:assembleRelease`. The catalog code follows the repository
formula (`libVersion` 1.6 + source `versionCode` -> `106NNN`), and the version/URL pairs in
`registry/extensions.json` and `registry/extensions.v2.json` must agree before `sync()` runs.
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
from pathlib import Path

try:
    from .sync_enabled_plugins import sync
except ImportError:  # Direct execution: python3 tools/prepare_mangadiyari_release.py
    from sync_enabled_plugins import sync

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "eu.kanade.tachiyomi.extension.tr.mangadiyari"
MODULE = "keiyoushi-extensions-tr/src/tr/mangadiyari"
APK_BASE_URL = "https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Manga-Plugins/builds/apk/"


def release_version(gradle_text: str) -> tuple[str, int, int]:
    lib_match = re.search(r'libVersion\s*=\s*"(\d+)\.(\d+)"', gradle_text)
    code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle_text)
    if not lib_match or not code_match:
        raise ValueError("Manga Diyarı libVersion/versionCode could not be read")
    major, minor = map(int, lib_match.groups())
    source_code = int(code_match.group(1))
    version_name = f"{major}.{minor}.{source_code}"
    catalog_code = major * 100_000 + minor * 1_000 + source_code
    return version_name, source_code, catalog_code


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def prepare_release(root: Path = ROOT) -> tuple[str, int, str]:
    version_name, source_code, catalog_code = release_version(
        (root / MODULE / "build.gradle.kts").read_text(encoding="utf-8")
    )
    built_apks = list((root / MODULE / "build/outputs/apk/release").glob("*.apk"))
    if len(built_apks) != 1:
        raise ValueError(f"Expected exactly one release APK, found {len(built_apks)}")
    built_apk = built_apks[0]
    if built_apk.stat().st_size < 50_000 or built_apk.read_bytes()[:2] != b"PK":
        raise ValueError("The release APK is empty or not a ZIP/Android package")

    apk_name = f"tachiyomi-tr.mangadiyari-v{version_name}.apk"
    prebuilt = root / "prebuilt/apk" / apk_name
    prebuilt.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(built_apk, prebuilt)

    # Yalnız güncel APK arşivde kalır: eski sürümler farklı anahtarlarla imzalanmış olabilir ve
    # katalog yalnız tek sürümü duyurur (tests/test_catalog.py yayın dizinlerinde tek dosya bekler).
    for stale in (root / "prebuilt/apk").glob("tachiyomi-tr.mangadiyari-v*.apk"):
        if stale.name != apk_name:
            stale.unlink()

    registry_path = root / "registry/extensions.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    entry = next((item for item in registry if item.get("pkg") == PACKAGE), None)
    if entry is None:
        raise ValueError("Manga Diyarı is missing from registry/extensions.json")
    entry.update(
        {
            "apk": apk_name,
            "code": catalog_code,
            "version": version_name,
            "url": APK_BASE_URL + apk_name,
        }
    )
    write_json(registry_path, registry)

    v2_path = root / "registry/extensions.v2.json"
    v2_data = json.loads(v2_path.read_text(encoding="utf-8"))
    v2_entry = next(
        (item for item in v2_data["extensionList"]["extensions"] if item.get("packageName") == PACKAGE),
        None,
    )
    if v2_entry is None:
        raise ValueError("Manga Diyarı is missing from registry/extensions.v2.json")
    v2_entry["versionName"] = version_name
    v2_entry["versionCode"] = str(catalog_code)
    v2_entry["resources"]["apkUrl"] = APK_BASE_URL + apk_name
    write_json(v2_path, v2_data)

    sync(root)
    digest = hashlib.sha256(prebuilt.read_bytes()).hexdigest()
    print(f"Manga Diyarı {version_name} ({catalog_code}); source versionCode={source_code}")
    print(f"APK: {prebuilt.relative_to(root)}; bytes={prebuilt.stat().st_size}; sha256={digest}")
    return version_name, catalog_code, digest


if __name__ == "__main__":
    prepare_release()
