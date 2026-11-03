"""Publish CI diagnostics as a GitHub check (not an APK publication)."""
import json, os, pathlib, subprocess, sys
p = pathlib.Path(sys.argv[2])
text = p.read_text(errors='replace') if p.exists() else 'Step did not run.'
# Keep enough context from build errors without flooding the check.
text = text[-55000:]
payload = {'name': sys.argv[1], 'head_sha': os.environ['HEAD_SHA'], 'status': 'completed', 'conclusion': 'neutral', 'output': {'title': sys.argv[1], 'summary': '```\n' + text + '\n```'}}
subprocess.run(['gh', 'api', '--method', 'POST', f"repos/{os.environ['GH_REPO']}/check-runs", '--input', '-'], input=json.dumps(payload), text=True, check=True, stdout=subprocess.DEVNULL)
