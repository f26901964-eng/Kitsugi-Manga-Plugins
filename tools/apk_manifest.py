#!/usr/bin/env python3
"""Read versionCode/versionName out of a built APK.

The catalog must describe the APK that is actually published, so the release script
compares the numbers in the manifest with the numbers it is about to write. Reading the
binary AndroidManifest.xml keeps that check dependency-free.
"""
from __future__ import annotations

import struct
import zipfile
from pathlib import Path

_STRING_POOL = 0x0001
_START_ELEMENT = 0x0102
_TYPE_STRING = 0x03


def _string_pool(data: bytes, pos: int, header_size: int) -> list[str]:
    count, _style_count, flags, strings_start, _styles_start = struct.unpack_from("<IIIII", data, pos + 8)
    utf8 = bool(flags & 0x100)
    offsets = struct.unpack_from(f"<{count}I", data, pos + header_size)
    base = pos + strings_start
    values: list[str] = []
    for offset in offsets:
        cursor = base + offset
        if utf8:
            cursor += 2 if data[cursor] & 0x80 else 1  # UTF-16 length, unused
            length = data[cursor]
            cursor += 1
            if length & 0x80:
                length = ((length & 0x7F) << 8) | data[cursor]
                cursor += 1
            values.append(data[cursor:cursor + length].decode("utf-8", "replace"))
        else:
            length = struct.unpack_from("<H", data, cursor)[0]
            cursor += 2
            if length & 0x8000:
                length = ((length & 0x7FFF) << 16) | struct.unpack_from("<H", data, cursor)[0]
                cursor += 2
            values.append(data[cursor:cursor + length * 2].decode("utf-16-le", "replace"))
    return values


def read_android_manifest(apk: Path | str) -> dict[str, object]:
    """Return the manifest attributes that matter for a release."""
    with zipfile.ZipFile(apk) as archive:
        data = archive.read("AndroidManifest.xml")

    strings: list[str] = []
    info: dict[str, object] = {}
    pos = 8  # skip the file header
    while pos < len(data):
        chunk_type, header_size, chunk_size = struct.unpack_from("<HHI", data, pos)
        if chunk_size == 0:
            break
        if chunk_type == _STRING_POOL:
            strings = _string_pool(data, pos, header_size)
        elif chunk_type == _START_ELEMENT:
            _ns, name, attr_start, attr_size, attr_count = struct.unpack_from("<IIHHH", data, pos + 16)
            tag = strings[name]
            for index in range(attr_count):
                attr = pos + 16 + attr_start + index * attr_size
                attr_name = strings[struct.unpack_from("<I", data, attr + 4)[0]]
                value_type = data[attr + 15]
                raw = struct.unpack_from("<I", data, attr + 16)[0]
                value = strings[raw] if value_type == _TYPE_STRING else raw
                if tag == "manifest" and attr_name in ("versionCode", "versionName"):
                    info[attr_name] = value
                elif tag == "data" and attr_name == "host":
                    info["host"] = value
                elif tag == "meta-data" and attr_name == "name":
                    info.setdefault("meta", [])
        pos += chunk_size
    return info


if __name__ == "__main__":
    import sys

    for argument in sys.argv[1:]:
        print(argument, read_android_manifest(argument))
