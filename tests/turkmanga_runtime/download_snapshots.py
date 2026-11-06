#!/usr/bin/env python3
"""Fetch the user's shared snapshots into ephemeral CI storage, never commit raw HTML."""
import os
from pathlib import Path
import requests

out = Path(os.environ['RUNNER_TEMP']) / 'turkmanga-snapshots'
out.mkdir(exist_ok=True)
files = {'home': '1DFhSz4aEsK8BgbZdW63uLK2GLOaqSAZe', 'search': '1vo_61Uo4OczqmiL2cgic_wXG1S0PP19Y', 'detail': '1danvYoX-5WQioxiospccZ_vG6EpzQ3E7', 'reader': '1rGTSyQk1aS8MRLOPYbvkKYYecVIXYAPZ'}
for name, file_id in files.items():
    r = requests.get('https://drive.usercontent.google.com/download', params={'id': file_id, 'export': 'download', 'confirm': 't'}, timeout=60)
    r.raise_for_status()
    text = r.text
    if not text.lstrip().lower().startswith('<!doctype html') or 'turkmanga' not in text.lower() or '__NEXT_DATA__' not in text:
        raise ValueError(f'{name}: could not fetch expected Turkmanga HTML; status={r.status_code}, bytes={len(r.content)}')
    (out / (name + '.html')).write_text(text, encoding='utf-8')
    print(f'{name}: {len(r.content)} bytes')
