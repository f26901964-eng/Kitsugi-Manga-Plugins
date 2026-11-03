#!/usr/bin/env python3
"""Copy only generated catalog outputs to an existing publication checkout."""
import shutil
import sys
from pathlib import Path

FILES = ('enabled_plugins.txt', 'index.json', 'plugins.json', 'builds/index.json',
         'builds/plugins.json', 'builds/index.v2.json')
DIRECTORIES = ('apk', 'builds/apk')


def stage(source, destination):
    source, destination = Path(source), Path(destination)
    if source.resolve() == destination.resolve():
        raise ValueError('Kaynak ve yayın dizinleri aynı olamaz')
    for path in FILES:
        if not (source / path).is_file():
            raise ValueError(f'Yayın dosyası yok: {path}')
    for path in DIRECTORIES:
        source_dir, destination_dir = source / path, destination / path
        if not source_dir.is_dir():
            raise ValueError(f'Yayın dizini yok: {source_dir}')
        for directory in (source_dir, destination_dir):
            # Git cannot retain empty directories; an empty builds branch may
            # legitimately not contain the destination directory yet.
            if directory.exists() and (not directory.is_dir() or any(
                not x.is_file() or x.suffix not in ('.apk', '.png') for x in directory.iterdir()
            )):
                raise ValueError(f'Yayın dizininde beklenmeyen öğe: {directory}')
    # Validation above prevents deleting unrelated files on either side.
    for path in FILES:
        (destination / path).parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / path, destination / path)
    for path in DIRECTORIES:
        source_dir, destination_dir = source / path, destination / path
        destination_dir.mkdir(parents=True, exist_ok=True)
        for file in destination_dir.iterdir():
            if not (source_dir / file.name).is_file():
                file.unlink()
        for file in source_dir.iterdir():
            shutil.copyfile(file, destination_dir / file.name)


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit('Kullanım: stage_publication.py KAYNAK YAYIN')
    try:
        stage(sys.argv[1], sys.argv[2])
    except (OSError, ValueError) as exc:
        sys.exit(f'Hata: {exc}')
