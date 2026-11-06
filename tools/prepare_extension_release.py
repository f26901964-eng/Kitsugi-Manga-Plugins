#!/usr/bin/env python3
"""Copy a freshly built TR extension APK into the published catalogs.

The catalog numbers are read from the built APK's manifest, so an entry can never
advertise a version (or version code) that the file does not actually carry. A catalog
code that would go backwards is refused, because clients would silently skip it.

Usage: python3 tools/prepare_extension_release.py hayalistic [more modules...]
"""
from __future__ import annotations

import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

try:
    from .apk_manifest import read_android_manifest
    from .release_version import module_version
    from .sync_enabled_plugins import sync
except ImportError:  # Direct execution
    from apk_manifest import read_android_manifest
    from release_version import module_version
    from sync_enabled_plugins import sync

ROOT = Path(__file__).resolve().parents[1]
PREFIX = "eu.kanade.tachiyomi.extension.tr."
APK_BASE_URL = "https://raw.githubusercontent.com/f26901964-eng/Kitsugi-Manga-Plugins/builds/apk/"


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def catalog_entry(root: Path, package: str) -> dict:
    registry = json.loads((root / "registry/extensions.json").read_text(encoding="utf-8"))
    entry = next((item for item in registry if item.get("pkg") == package), None)
    if entry is None:
        raise ValueError(f"{package}: registry/extensions.json içinde yok")
    return entry


def release(module_name: str, root: Path = ROOT) -> tuple[str, int]:
    package = PREFIX + module_name
    module = root / "keiyoushi-extensions-tr/src/tr" / module_name
    expected_name, expected_code = module_version(f"tr/{module_name}", root)

    built = list((module / "build/outputs/apk/release").glob("*.apk"))
    if len(built) != 1:
        raise ValueError(f"{module_name}: tam olarak bir release APK bekleniyordu, {len(built)} bulundu")
    apk_bytes = built[0].read_bytes()
    # A real (even minimal) extension APK is a zip well above 10 KB; the floor only
    # guards against empty/corrupt files — tiny modules can legitimately be ~45 KB.
    if len(apk_bytes) < 10_000 or apk_bytes[:2] != b"PK":
        raise ValueError(f"{module_name}: APK boş ya da bir Android paketi değil")

    manifest = read_android_manifest(built[0])
    version_name = str(manifest.get("versionName"))
    catalog_code = manifest.get("versionCode")
    if not isinstance(catalog_code, int):
        raise ValueError(f"{module_name}: manifest içinde versionCode okunamadı")
    if (version_name, catalog_code) != (expected_name, expected_code):
        raise ValueError(
            f"{module_name}: APK {version_name}/{catalog_code} ile Gradle beklentisi "
            f"{expected_name}/{expected_code} uyuşmuyor",
        )

    previous = catalog_entry(root, package)
    if catalog_code <= int(previous["code"]) and previous["version"] != version_name:
        raise ValueError(
            f"{module_name}: yeni sürüm kodu {catalog_code}, yayındaki {previous['code']} "
            f"({previous['version']}) değerinden büyük olmalı; istemciler aksi hâlde güncellemeyi görmez",
        )

    apk_name = f"tachiyomi-tr.{module_name}-v{version_name}.apk"
    prebuilt = root / "prebuilt/apk" / apk_name
    prebuilt.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(built[0], prebuilt)
    # Older builds of the same extension must not stay in the archive: the catalog names
    # one file and the publication step deletes anything it does not list.
    for stale in prebuilt.parent.glob(f"tachiyomi-tr.{module_name}-v*.apk"):
        if stale != prebuilt:
            stale.unlink()

    registry_path = root / "registry/extensions.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    entry = next(item for item in registry if item.get("pkg") == package)
    entry.update(
        {"apk": apk_name, "code": catalog_code, "version": version_name, "url": APK_BASE_URL + apk_name},
    )
    write_json(registry_path, registry)

    v2_path = root / "registry/extensions.v2.json"
    v2_data = json.loads(v2_path.read_text(encoding="utf-8"))
    v2_entry = next(
        (item for item in v2_data["extensionList"]["extensions"] if item.get("packageName") == package), None,
    )
    if v2_entry is None:
        raise ValueError(f"{module_name}: registry/extensions.v2.json içinde yok")
    v2_entry["versionName"] = version_name
    v2_entry["versionCode"] = str(catalog_code)
    v2_entry["resources"]["apkUrl"] = APK_BASE_URL + apk_name
    write_json(v2_path, v2_data)

    sync(root)
    digest = hashlib.sha256(prebuilt.read_bytes()).hexdigest()
    print(
        f"{module_name}: {version_name} ({catalog_code}) apk={apk_name} "
        f"bytes={prebuilt.stat().st_size} sha256={digest}",
    )
    return version_name, catalog_code


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Kullanım: prepare_extension_release.py <modül> [modül...]")
    try:
        for name in sys.argv[1:]:
            release(name)
    except (OSError, ValueError, KeyError) as exc:
        sys.exit(f"Hata: {exc}")
