#!/usr/bin/env python3
"""Read-only live check for the actual Madara browse/search and reader endpoints."""
import io
import json
import sys
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup
from PIL import Image

BASE = 'https://tortugaceviri.com'
SERIES = '/manga/doom-satsujin-keisatsukan-no-danzairoku/'
CHAPTER = SERIES + 'doom-satsujin-keisatsukan-no-danzairoku-1/'
s = requests.Session()
s.headers.update({'User-Agent': 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/124 Safari/537.36'})


def soup(response):
    response.raise_for_status()
    return BeautifulSoup(response.text, 'html.parser')


def archive(mode, query='', page=1):
    body = {
        'action': 'madara_load_more', 'page': str(page - 1), 'template': 'madara-core/content/content-archive',
        'vars[paged]': '1', 'vars[template]': 'archive', 'vars[posts_per_page]': '25',
        'vars[post_type]': 'wp-manga', 'vars[post_status]': 'publish',
        'vars[manga_archives_item_layout]': 'big_thumbnail',
        'vars[meta_query][0][key]': '_wp_manga_chapter_type',
        'vars[meta_query][0][value]': 'manga',
    }
    if query:
        body['vars[s]'] = query
    else:
        body.update({'vars[orderby]': 'meta_value_num', 'vars[order]': 'DESC',
                     'vars[meta_key]': '_wp_manga_views' if mode == 'popular' else '_latest_update'})
    r = s.post(BASE + '/wp-admin/admin-ajax.php', data=body, headers={'X-Requested-With': 'XMLHttpRequest', 'Referer': BASE + '/'}, timeout=40)
    d = soup(r)
    cards = d.select('div.page-item-detail, .manga__item, .c-tabs-item__content')
    usable = [x for x in cards if (x.get('data-post-id') or x.select_one('[data-post-id]')) and x.select_one('.post-title a')]
    titles = [x.select_one('.post-title a').get_text(' ', strip=True) for x in usable]
    ids = [x.get('data-post-id') or x.select_one('[data-post-id]').get('data-post-id') for x in usable]
    covers = sum(bool(x.select_one('img[src], img[data-src], img[data-lazy-src]')) for x in usable)
    return {'status': r.status_code, 'cards': len(cards), 'usable_with_id': len(usable), 'with_cover': covers, 'ids': ids, 'titles': titles[:8]}


def main():
    result = {}
    try:
        for mode, query in [('popular', ''), ('latest', ''), ('search', 'Doom')]:
            result[mode] = archive(mode, query)
        result['popular_page_2'] = archive('popular', page=2)
        d = soup(s.get(BASE + SERIES, timeout=40))
        desc = d.select_one('div.description-summary div.summary__content')
        chapters = d.select('li.wp-manga-chapter a[href]')
        result['details'] = {'title': d.select_one('div.post-title h1').get_text(' ', strip=True),
                             'description': desc.get_text(' ', strip=True) if desc else '',
                             'chapters': len(chapters)}
        reader = soup(s.get(BASE + CHAPTER, timeout=40))
        images = [img.get('data-src') or img.get('data-lazy-src') or img.get('src') for img in reader.select('div.page-break img')]
        images = [urljoin(BASE, x.strip()) for x in images if x and x.strip()]
        result['reader'] = {'images': len(images)}
        result['reader']['probed'] = []
        for url in images[:3]:
            r = s.get(url, headers={'Referer': BASE + CHAPTER}, timeout=60)
            r.raise_for_status()
            with Image.open(io.BytesIO(r.content)) as image:
                image.verify()
                result['reader']['probed'].append({'content_type': r.headers.get('Content-Type'), 'format': image.format, 'size': image.size})
        assert result['popular']['usable_with_id'] > 0, 'popular AJAX cards lack post IDs or titles'
        assert result['latest']['usable_with_id'] > 0, 'latest AJAX cards lack post IDs or titles'
        assert any('doom' in title.lower() for title in result['search']['titles']), 'search AJAX did not find Doom'
        assert result['details']['description'] and result['details']['chapters'], 'missing description or chapters'
        assert result['popular']['with_cover'] > 0, 'popular covers missing'
        if len(result['popular']['ids']) == 25:
            assert result['popular_page_2']['ids'] and result['popular_page_2']['ids'] != result['popular']['ids'], 'pagination repeated or empty'
        assert len(result['reader']['probed']) == min(3, len(images)) and images, 'reader image missing'
        result['ok'] = True
    except Exception as exc:
        result.update(ok=False, error=f'{type(exc).__name__}: {exc}')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result['ok'] else 1

if __name__ == '__main__':
    sys.exit(main())
