#!/usr/bin/env python3
"""Record why the MangaDenizi runtime probe failed, so the log is readable as a file."""
import json
import pathlib
import sys

LOG = pathlib.Path('/tmp/mangadenizi-runtime.log')
REPORT = pathlib.Path('reports/mangadenizi-runtime.json')


def main() -> int:
    if REPORT.is_file() and REPORT.stat().st_size:
        return 0
    tail = []
    if LOG.is_file():
        tail = LOG.read_text(encoding='utf-8', errors='replace').splitlines()[-60:]
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(
        json.dumps(
            {'passed': 0, 'total': 0, 'error': 'runtime-script-failed', 'log': tail},
            ensure_ascii=False,
            indent=2,
        )
        + '\n',
        encoding='utf-8',
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
