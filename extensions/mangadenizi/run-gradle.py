#!/usr/bin/env python3
"""Make build errors available in GitHub Checks even if its log CDN is unreachable."""
import os
import subprocess
import sys

result = subprocess.run(sys.argv[1:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
print(result.stdout)
if result.returncode and os.environ.get('GITHUB_ACTIONS') == 'true':
    tail = result.stdout[-12_000:]
    for start in range(0, len(tail), 3000):
        message = tail[start:start+3000].replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
        print('::error title=MangaDenizi build diagnostic::' + message)
sys.exit(result.returncode)
