#!/usr/bin/env python3
"""Generate published catalogs and artifacts from enabled_plugins.txt.

This only prepares files in the checkout. It does not build APKs, publish a Git
branch, or disable extensions already installed on a device.
"""
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'eu.kanade.tachiyomi.extension.'
MODULE = re.compile(r'[a-z0-9_-]+/[a-z0-9_-]+\Z')


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def sync(root):
    root = Path(root)
    lines = (root / 'enabled_plugins.txt').read_text(encoding='utf-8').splitlines()
    modules = [line.strip() for line in lines if line.strip() and not line.strip().startswith('#')]
    if len(modules) != len(set(modules)):
        raise ValueError('enabled_plugins.txt içinde yinelenen eklenti var')
    if any(not MODULE.fullmatch(module) for module in modules):
        raise ValueError('Geçersiz eklenti adı; dil/eklenti biçimi kullanın')
    registry = load_json(root / 'registry/extensions.json')
    v2_data = load_json(root / 'registry/extensions.v2.json')
    v2_registry = v2_data['extensionList']['extensions']
    by_package = {item['pkg']: item for item in registry}
    by_package_v2 = {item['packageName']: item for item in v2_registry}
    if len(by_package) != len(registry) or len(by_package_v2) != len(v2_registry):
        raise ValueError('Kayıt defterinde yinelenen paket adı var')
    selected = []
    selected_v2 = []
    artifacts = []
    for module in modules:
        lang, name = module.split('/')
        package = f'{PREFIX}{lang}.{name}'
        if not (root / 'keiyoushi-extensions-tr/src' / lang / name / 'build.gradle.kts').is_file():
            raise ValueError(f'Kaynak modülü yok: {module}')
        entry = by_package.get(package)
        v2 = by_package_v2.get(package)
        if entry is None or v2 is None:
            raise ValueError(f'Kayıt defterinde eklenti yok: {module}')
        if (v2['versionName'] != entry['version'] or int(v2['versionCode']) != entry['code'] or
                v2['resources']['apkUrl'] != entry['url'] or v2['resources']['iconUrl'] != entry['iconUrl']):
            raise ValueError(f'Katalog sürümü veya bağlantıları eşleşmiyor: {module}')
        apk = entry['apk']
        icon = package + '.png'
        if Path(apk).name != apk:
            raise ValueError(f'Geçersiz APK adı: {apk}')
        for name in (apk, icon):
            path = root / 'prebuilt/apk' / name
            if not path.is_file():
                raise ValueError(f'Arşiv dosyası yok: {name}')
            artifacts.append(name)
        selected.append(entry)
        selected_v2.append(v2)
    if len(artifacts) != len(set(artifacts)):
        raise ValueError('Birden fazla eklenti aynı dosyayı kullanıyor')
    # Validate every input before changing any published files.
    for directory in ('apk', 'builds/apk'):
        out = root / directory
        out.mkdir(parents=True, exist_ok=True)
        unexpected = [p for p in out.iterdir() if not p.is_file() or p.suffix not in ('.apk', '.png')]
        if unexpected:
            raise ValueError(f'Yayın dizininde beklenmeyen dosya: {unexpected[0]}')
    for directory in ('apk', 'builds/apk'):
        out = root / directory
        for name in artifacts:
            shutil.copyfile(root / 'prebuilt/apk' / name, out / name)
        for file in out.iterdir():
            if file.name not in artifacts:
                file.unlink()
    v2_data['extensionList']['extensions'] = selected_v2
    for name in ('index.json', 'plugins.json', 'builds/index.json', 'builds/plugins.json'):
        (root / name).write_text(json.dumps(selected, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    (root / 'builds/index.v2.json').write_text(json.dumps(v2_data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return modules


if __name__ == '__main__':
    try:
        modules = sync(ROOT)
    except (OSError, KeyError, TypeError, ValueError) as exc:
        print(f'Hata: {exc}', file=sys.stderr)
        sys.exit(1)
    print(f'{len(modules)} eklenti etkin: {", ".join(modules) or "(hiçbiri)"}')
