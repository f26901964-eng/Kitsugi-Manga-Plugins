"""Live SadScans HTTP contract probe; no success inferred from compilation."""
import json
import os
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = 'https://sadscans.net'
report = {'base': BASE, 'steps': [], 'passed': False}


def call(procedure, payload):
    query = urllib.parse.urlencode({'batch': '1', 'input': json.dumps({'0': {'json': payload}}, separators=(',', ':'))})
    url = BASE + '/api/trpc/' + procedure + '?' + query
    req = urllib.request.Request(url, headers={
        'Accept': 'application/json, text/plain, */*',
        'Referer': BASE + '/',
        'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36',
    })
    step = {'procedure': procedure, 'input': payload, 'url': url}
    report['steps'].append(step)
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            body = response.read().decode('utf-8', errors='replace')
            step.update(status=response.status, contentType=response.headers.get('Content-Type'))
    except urllib.error.HTTPError as error:
        step.update(status=error.code, body=error.read(4096).decode('utf-8', errors='replace'))
        raise RuntimeError(f'{procedure}: HTTP {error.code}') from error
    step['bodyPreview'] = body[:1500]
    batch = json.loads(body)
    data = batch[0]['result']['data']['json']
    step['payloadType'] = type(data).__name__
    if isinstance(data, list):
        step['count'] = len(data)
        if data:
            step['firstItem'] = data[0]
    return data


try:
    series = call('_s4d7g2k9m0.getSeries', {'page': 1, 'limit': 15, 'sortBy': '-views'})
    assert isinstance(series, list) and series, 'Popular returned no series'
    item = next(x for x in series if x.get('href') and x.get('name'))
    # Use the exact title returned by the site, not an assumed catalogue entry.
    found = call('_s4d7g2k9m0.getSeries', {'page': 1, 'limit': 15, 'sortBy': '-views', 'search': item['name']})
    assert any(x.get('href') == item['href'] for x in found), 'Search did not return selected series'
    slug = item['href'].rstrip('/').split('/')[-1]
    detail = call('_n3p6q1v8x5.getSeriesData', {'sef': slug, 'page': 1, 'limit': 10})
    assert detail.get('series'), 'Series details absent'
    chapters = detail.get('chapters')
    assert isinstance(chapters, list) and chapters, 'No chapters'
    assert chapters[0].get('chap_id'), 'Chapter ID absent'
    report['chapterSample'] = chapters[0]
    report['passed'] = True
    report['scope'] = 'HTTP list/search/details/chapters contract only; Android and reader images not verified'
except Exception as error:
    report['error'] = f'{type(error).__name__}: {error}'
finally:
    path = pathlib.Path(os.environ.get('REPORT', 'reports/sadscans-live.json'))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))
sys.exit(0 if report['passed'] else 1)
