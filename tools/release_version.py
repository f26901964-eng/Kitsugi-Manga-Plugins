#!/usr/bin/env python3
"""Turn a module's Gradle version into the version the APK and the catalog must share.

The build logic adds the theme's `baseVersionCode` to the source `versionCode`
(ExtensionPlugin.kt), so reading only the source number published an APK and a catalog
entry that disagreed. Both numbers are now derived the same way here.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def theme_base_version_code(theme: str | None, root: Path = ROOT) -> int:
    if not theme:
        return 0
    gradle = root / "keiyoushi-extensions-tr/lib-multisrc" / theme / "build.gradle.kts"
    if not gradle.is_file():
        raise ValueError(f"Bilinmeyen tema: {theme}")
    match = re.search(r"baseVersionCode\s*=\s*(\d+)", gradle.read_text(encoding="utf-8"))
    if match is None:
        raise ValueError(f"baseVersionCode okunamadı: {theme}")
    return int(match.group(1))


def release_version(gradle_text: str, theme_base: int = 0) -> tuple[str, int]:
    """Return (versionName, androidVersionCode) exactly as the built APK carries them.

    Mirrors ExtensionPlugin.kt: the Gradle versionCode is the theme base plus the source
    number, the name is "<libVersion>.<that number>", and the Android code packs the lib
    version into thousands: 1.6 + 62 -> 1.6.62 / 106062.
    """
    lib = re.search(r'libVersion\s*=\s*"(\d+)\.(\d+)"', gradle_text)
    code = re.search(r"versionCode\s*=\s*(\d+)", gradle_text)
    if lib is None or code is None:
        raise ValueError("libVersion/versionCode okunamadı")
    major, minor = (int(part) for part in lib.groups())
    version_code = theme_base + int(code.group(1))
    version_name = f"{major}.{minor}.{version_code}"
    # libVersion "1.6" -> "0106" -> 106, then the Gradle versionCode lands in the last digits.
    lib_number = int(f"{major:02d}{minor:02d}")
    return version_name, lib_number * 1_000 + version_code


def module_version(module: str, root: Path = ROOT) -> tuple[str, int]:
    gradle = root / "keiyoushi-extensions-tr/src" / module / "build.gradle.kts"
    text = gradle.read_text(encoding="utf-8")
    theme = re.search(r'theme\s*=\s*"([^"]+)"', text)
    return release_version(text, theme_base_version_code(theme.group(1) if theme else None, root))
