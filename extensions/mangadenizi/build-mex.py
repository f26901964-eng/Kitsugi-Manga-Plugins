#!/usr/bin/env python3
"""D8 only the extension's own jar; host API, Kotlin and OkHttp stay compile-only."""
from pathlib import Path
import json
import os
import subprocess
import zipfile
import hashlib

root = Path(__file__).resolve().parent
sdk = Path(os.environ['ANDROID_HOME'])
android = Path(os.environ['ANDROID_JAR'])
tools = sorted((sdk / 'build-tools').glob('*/d8'), key=lambda p: [int(x) if x.isdigit() else x for x in p.parent.name.replace('-', '.').split('.')])[-1]
jar, = (root / 'extension/build/libs').glob('extension*.jar')
out = root / 'build/mex'
out.mkdir(parents=True, exist_ok=True)
cmd = [str(tools), '--min-api', '26', '--lib', str(android), '--output', str(out)]
for line in (root / 'extension/build/dex-classpath.txt').read_text().splitlines():
    if Path(line).resolve() != android.resolve(): cmd += ['--classpath', line]
subprocess.run(cmd + [str(jar)], check=True)
with zipfile.ZipFile(jar) as file:
    classes = [name for name in file.namelist() if name.endswith('.class')]
    assert classes and all(name.startswith('com/kitsugi/extension/tr/mangadenizi/') for name in classes), classes
manifest = json.loads((root / 'manifest.json').read_text())
package = root / 'build/mangadenizi.mex'
with zipfile.ZipFile(package, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as file:
    for name, data in [('classes.dex', (out / 'classes.dex').read_bytes()), ('manifest.json', (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode()), ('LICENSE', (root / 'LICENSE').read_bytes()), ('NOTICE', (root / 'NOTICE').read_bytes())]:
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        file.writestr(info, data)
print(f'{package}: {package.stat().st_size} bytes; sha256 {hashlib.sha256(package.read_bytes()).hexdigest()}')
