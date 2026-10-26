#!/usr/bin/env python3
"""Fetch the real, pinned host API; never package these classes with the MEX.

Only the host's default network implementation is replaced with a throwing body
for compile-only use. The extension implements EVERY interface member itself.
MangaModels.kt, all declarations, constructors, names and types are unchanged.
The separate Android smoke test then uses the actual published host APK, not this
compilation aid, to catch ABI and dynamic-class-loading incompatibilities.
"""
import base64
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent
contract = json.loads((ROOT / 'host-contract.json').read_text())
out = ROOT / 'host-contract/src/main/kotlin/com/kitsugi/animelist/data/manga'
out.mkdir(parents=True, exist_ok=True)
for name, expected in contract['files'].items():
    path = 'app/src/main/java/com/kitsugi/animelist/data/manga/' + name
    response = json.loads(subprocess.check_output(['gh', 'api', f"repos/{contract['repository']}/contents/{path}?ref={contract['revision']}"]))
    data = base64.b64decode(response['content'])
    blob = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
    if response['sha'] != expected or blob != expected:
        raise RuntimeError(f'Unexpected host API revision: {name}')
    source = data.decode('utf-8-sig')
    if name == 'MangaSource.kt':
        signature = '    suspend fun getImage(page: MangaPage): java.io.InputStream'
        start = source.index(signature + ' {')
        end = source.index('\n    }\n}', start) + len('\n    }')
        source = source[:start] + signature + ' = throw UnsupportedOperationException("Compile-only host contract")' + source[end:]
    (out / name).write_text(source)
    print(f'Verified actual host source {name}: {blob}')
