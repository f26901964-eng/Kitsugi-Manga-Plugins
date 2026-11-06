#!/usr/bin/env python3
"""Copy a freshly built TR extension APK into the published catalogs.

Usage: python3 tools/prepare_extension_release.py hayalistic
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

try:
    from .sync_enabled_plugins import sync
except ImportError:  # Direct execution
    from sync_enabled_plugins import sync

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "eu.kanade.tachiyomi.extension.tr."
APK_BASE_URL = "https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Manga-Plugins/builds/apk/"


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def release(module_name: str, root: Path = ROOT) -> tuple[str, int]:
    package = PREFIX + module_name
    module = root / "keiyoushi-extensions-tr/src/tr" / module_name
    gradle_text = (module / "build.gradle.kts").read_text(encoding="utf-8")
    lib = re.search(r'libVersion\s*=\s*"(\d+)\.(\d+)"', gradle_text)
    code = re.search(r"versionCode\s*=\s*(\d+)", gradle_text)
    if not lib or not code:
        raise ValueError(f"{module_name}: libVersion/versionCode unreadable")
    major, minor = map(int, lib.groups())
    source_code = int(code.group(1))
    version_name = f"{major}.{minor}.{source_code}"
    catalog_code = major * 100_000 + minor * 1_000 + source_code

    built = list((module / "build/outputs/apk/release").glob("*.apk"))
    if len(built) != 1:
        raise ValueError(f"{module_name}: expected exactly one release APK, found {len(built)}")
    apk_bytes = built[0].read_bytes()
    if len(apk_bytes) < 50_000 or apk_bytes[:2] != b"PK":
        raise ValueError(f"{module_name}: the release APK is empty or not an Android package")

    apk_name = f"tachiyomi-tr.{module_name}-v{version_name}.apk"
    prebuilt = root / "prebuilt/apk" / apk_name
    prebuilt.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(built[0], prebuilt)

    registry_path = root / "registry/extensions.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    entry = next((item for item in registry if item.get("pkg") == package), None)
    if entry is None:
        raise ValueError(f"{module_name}: missing from registry/extensions.json")
    entry.update({"apk": apk_name, "code": catalog_code, "version": version_name, "url": APK_BASE_URL + apk_name})
    write_json(registry_path, registry)

    v2_path = root / "registry/extensions.v2.json"
    v2_data = json.loads(v2_path.read_text(encoding="utf-8"))
    v2_entry = next(
        (item for item in v2_data["extensionList"]["extensions"] if item.get("packageName") == package), None
    )
    if v2_entry is None:
        raise ValueError(f"{module_name}: missing from registry/extensions.v2.json")
    v2_entry["versionName"] = version_name
    v2_entry["versionCode"] = str(catalog_code)
    v2_entry["resources"]["apkUrl"] = APK_BASE_URL + apk_name
    write_json(v2_path, v2_data)

    sync(root)
    digest = hashlib.sha256(prebuilt.read_bytes()).hexdigest()
    print(f"{module_name}: {version_name} ({catalog_code}) apk={prebuilt.name} bytes={prebuilt.stat().st_size} sha256={digest}")
    return version_name, catalog_code


if __name__ == "__main__":
    for name in sys.argv[1:]:
        release(name)
