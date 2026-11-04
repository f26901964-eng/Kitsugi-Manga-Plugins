#!/usr/bin/env python3
"""
mangatr-exhaustive.py — Manga-TR için eklentinin ihtiyaç duyduğu TÜM veriyi eksiksiz toplar
Kullanım: python3 tools/mangatr-exhaustive.py
Çıktı: mangatr/live/ ve mangatr/raw/ altına ham HTML/JSON
Sandbox'ta manga-tr.com kapalı (SSL EOF) olduğu için bu script GitHub Actions runner'da çalışır.
Lokal PC'de de çalışır.
"""
import re, json, time, pathlib, urllib.parse, sys
try:
    import requests, bs4
except ImportError:
    print("pip install requests beautifulsoup4 lxml")
    sys.exit(1)

BASE = "https://manga-tr.com"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0 Safari/537.36"
OUT_LIVE = pathlib.Path("mangatr/live")
OUT_RAW = pathlib.Path("mangatr/raw")
OUT_LIVE.mkdir(parents=True, exist_ok=True)
OUT_RAW.mkdir(parents=True, exist_ok=True)

sess = requests.Session()
sess.headers.update({
    "User-Agent": UA,
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
})

def save(name, content, subdir="live"):
    p = (OUT_LIVE if subdir=="live" else OUT_RAW) / name
    p.parent.mkdir(parents=True, exist_ok=True)
    if isinstance(content, bytes):
        p.write_bytes(content)
    else:
        p.write_text(content, encoding="utf-8")
    print(f"  -> {p} {len(content)}b")
    return p

def fetch(url, name, subdir="live", method="GET", data=None, headers_extra=None):
    headers = {}
    if headers_extra: headers.update(headers_extra)
    print(f"[GET] {name} <- {url}")
    try:
        if method=="POST":
            r = sess.post(url, data=data, headers=headers, timeout=20)
        else:
            r = sess.get(url, headers=headers, timeout=20)
        print(f"  {r.status_code} {len(r.text)}b ct={r.headers.get('content-type','')}")
        save(name, r.text, subdir)
        # save headers too
        save(name+".headers.json", json.dumps(dict(r.headers), indent=2, ensure_ascii=False), subdir)
        save(name+".meta.json", json.dumps({"url": url, "status": r.status_code, "final_url": r.url}, indent=2, ensure_ascii=False), subdir)
        return r.text, r
    except Exception as e:
        print(f"  FAIL {e}")
        save(name+".error.txt", str(e), subdir)
        return None, None

# ---- 1. ANA SEKMELER ----
home,_ = fetch(BASE+"/", "home.html")
popular1,_ = fetch(BASE+"/manga-list-sayfala.html?sort=views&sort_type=DESC&page=1&listType=pagination", "popular_p1.html")
popular2,_ = fetch(BASE+"/manga-list-sayfala.html?sort=views&sort_type=DESC&page=2&listType=pagination", "popular_p2.html")
popular5,_ = fetch(BASE+"/manga-list-sayfala.html?sort=views&sort_type=DESC&page=5&listType=pagination", "popular_p5.html")
latest1,_ = fetch(BASE+"/manga-list-sayfala.html?sort=last_update&sort_type=DESC&page=1&listType=pagination", "latest_p1.html")
latest2,_ = fetch(BASE+"/manga-list-sayfala.html?sort=last_update&sort_type=DESC&page=2&listType=pagination", "latest_p2.html")

# filtreli listeler — eklentinin Filters.kt'sindeki her tur paramı
filtered = [
    ("filter_durum_tamam", BASE+"/manga-list-sayfala.html?durum=1&page=1&listType=pagination"),
    ("filter_durum_devam", BASE+"/manga-list-sayfala.html?durum=2&page=1&listType=pagination"),
    ("filter_ceviri_tamam", BASE+"/manga-list-sayfala.html?ceviri=1&page=1&listType=pagination"),
    ("filter_yas_18", BASE+"/manga-list-sayfala.html?yas=18&page=1&listType=pagination"),
    ("filter_tur_Action", BASE+"/manga-list-sayfala.html?tur=Action&page=1&listType=pagination"),
    ("filter_tur_Romance", BASE+"/manga-list-sayfala.html?tur=Romance&page=1&listType=pagination"),
    ("filter_icerik_manga", BASE+"/manga-list-sayfala.html?icerik=1&page=1&listType=pagination"),
    ("filter_sort_name", BASE+"/manga-list-sayfala.html?sort=name&sort_type=ASC&page=1&listType=pagination"),
]
for name,url in filtered:
    fetch(url, f"{name}.html")
    time.sleep(0.4)

# index pagination (eski site)
fetch(BASE+"/index-sayfa-1.html", "index_p1.html")
fetch(BASE+"/index-sayfa-2.html", "index_p2.html")

# ---- 2. ARAMA — tüm varyasyonlar ----
search_queries = [
    ("arama_chainsaw_p1", "Chainsaw Man", 1),
    ("arama_chainsaw_p2", "Chainsaw Man", 2),
    ("arama_naruto_p1", "Naruto", 1),
    ("arama_onepiece_p1", "One Piece", 1),
    ("arama_berserk_p1", "Berserk", 1),
    ("arama_solo_p1", "Solo Leveling", 1),
    ("arama_ladyliar_p1", "Lady Liar", 1),
    ("arama_a_p1", "a", 1),
    ("arama_bos", "xyzabc123notfound", 1),
    ("arama_bos_p2", "xyzabc123notfound", 2),
]
for name,q,page in search_queries:
    url = f"{BASE}/arama.html?view=sayfalama&icerik={urllib.parse.quote(q)}&page={page}"
    fetch(url, f"{name}.html")
    # eski legacy arama (view olmadan)
    if page==1 and q in ("Chainsaw Man","Naruto"):
        url2 = f"{BASE}/arama.html?icerik={urllib.parse.quote(q)}"
        fetch(url2, f"{name}_legacy.html")
    time.sleep(0.4)

# ---- 3. MANGA LİNKLERİNİ KEŞFET — canlıdan 20 slug çıkar ----
manga_slugs = set()
for html_file in ["popular_p1.html","popular_p2.html","latest_p1.html","arama_chainsaw_p1.html"]:
    p = OUT_LIVE / html_file
    if not p.exists(): continue
    t = p.read_text(encoding="utf-8", errors="ignore")
    soup = bs4.BeautifulSoup(t, "lxml")
    # 3 seçici de dene
    for a in soup.select("a[href*='/manga-']"):
        href = a.get("href","")
        m = re.search(r"/(manga-[^\"'/?#]+\.html)", href)
        if m: manga_slugs.add(m.group(1))
    for a in soup.select("a.media-card__title"):
        href = a.get("href","")
        m = re.search(r"/(manga-[^\"'/?#]+\.html)", href)
        if m: manga_slugs.add(m.group(1))
    for el in soup.select("div.arama-card a.arama-card__title"):
        href = el.get("href","")
        m = re.search(r"/(manga-[^\"'/?#]+\.html)", href)
        if m: manga_slugs.add(m.group(1))

# fallback: bilinenler
for s in ["manga-chainsaw-man.html","manga-lady-liar.html","manga-naruto.html","manga-one-piece.html","manga-berserk.html"]:
    manga_slugs.add(s)

manga_slugs = sorted(manga_slugs)[:25]
print(f"[INFO] keşfedilen manga sayısı: {len(manga_slugs)} -> {manga_slugs[:10]}")
save("discovered_manga.json", json.dumps(manga_slugs, indent=2, ensure_ascii=False))

# ---- 4. DETAY — her manga için ham HTML + dış JS ----
list_keys = {}
for slug in manga_slugs[:12]:  # ilk 12 detay yeterli, hepsi 25 değil
    html,_ = fetch(BASE+"/"+slug, f"detail_{slug}", subdir="raw")
    if not html: continue
    soup = bs4.BeautifulSoup(html, "lxml")
    # data-manga / data-list-key
    d_manga = None
    d_list = None
    el = soup.select_one("#results")
    if el: d_manga = el.get("data-manga"); d_list = el.get("data-list-key")
    if not d_list:
        el2 = soup.select_one("[data-list-key]")
        if el2: d_list = el2.get("data-list-key")
    # inline listKey
    inline_keys = re.findall(r"listKey:\s*'([^']+)'", html)
    # script src
    scripts = [s.get("src") for s in soup.select("script[src]") if s.get("src")]
    manga_tr_js = next((s for s in scripts if "manga-tr" in s), None)
    if manga_tr_js:
        js_url = manga_tr_js if manga_tr_js.startswith("http") else BASE+ (manga_tr_js if manga_tr_js.startswith("/") else "/"+manga_tr_js)
        js_text,_ = fetch(js_url, f"js_{slime if (slime:=slug.replace('.html','')) else 'x'}.js", subdir="raw")
        if js_text:
            inline_keys += re.findall(r"listKey:\s*'([^']+)'", js_text)
    if inline_keys or d_list:
        # en uzun key genelde doğru
        best = sorted(set(inline_keys + ([d_list] if d_list else [])), key=len, reverse=True)[0]
        list_keys[slug]=best
        print(f"  listKey {slug} -> {best[:20]}...")
    time.sleep(0.4)

save("list_keys.json", json.dumps(list_keys, indent=2, ensure_ascii=False), subdir="live")

# ---- 5. BÖLÜM LİSTESİ API — her manga için 3 offset ---
headers_ajax = {"X-Requested-With":"XMLHttpRequest", "Accept":"*/*", "Content-Type":"application/x-www-form-urlencoded"}
for slug,key in list_keys.items():
    for off in [0, 20, 100]:
        data = {"chapter_list_key": key}
        if off!=0: data["offset"]=str(off)
        fetch(BASE+"/cek/fetch_pages_manga.php", f"api_chapters_{slug.replace('.html','')}_off{off}.html", subdir="raw", method="POST", data=data, headers_extra=headers_ajax)
        time.sleep(0.3)
    time.sleep(0.5)

# fallback manga_cek ile de dene
for slug in manga_slugs[:10]:
    mslug = slug.replace("manga-","").replace(".html","")
    # GET ?manga_cek=
    fetch(BASE+f"/cek/fetch_pages_manga.php?manga_cek={mslug}", f"api_manga_cek_{mslug}.html", subdir="raw", headers_extra={"X-Requested-With":"XMLHttpRequest"})
    time.sleep(0.3)
    # POST manga_cek + offset
    fetch(BASE+"/cek/fetch_pages_manga.php", f"api_manga_cek_{mslug}_post.html", subdir="raw", method="POST", data={"manga_cek": mslug, "offset":"0"}, headers_extra=headers_ajax)
    time.sleep(0.3)

# ---- 6. OKUYUCU — her mangadan 1-2 bölüm çek, _fpx / rdm- / img_part topla ----
for slug in list(manga_slugs[:6]):
    # detail'den bölüm linklerini çıkar
    detail_p = OUT_RAW / f"detail_{slug}"
    if not detail_p.exists(): continue
    html = detail_p.read_text(encoding="utf-8", errors="ignore")
    soup = bs4.BeautifulSoup(html, "lxml")
    # olası bölüm linkleri
    # 1) /id-... , 2) bento-ep-card, 3) fansub-group-item
    links = []
    for a in soup.select("a[href*='/id-'], a.bento-ep-title-link, a.fansub-group-item, a[href*='read']"):
        href = a.get("href") or a.get("data-href")
        if href and ("/id-" in href or "read" in href or "/bolum" in href):
            if href.startswith("/"): href = BASE+href
            links.append(href)
    links = list(dict.fromkeys(links))[:3]
    if not links:
        # API'den gelen chapter html'i varsa ondan da çıkar
        api_p = OUT_RAW / f"api_manga_cek_{slug.replace('manga-','').replace('.html','')}.html"
        if api_p.exists():
            t = api_p.read_text(encoding="utf-8", errors="ignore")
            soup2 = bs4.BeautifulSoup(t, "lxml")
            for a in soup2.select("a[href*='/id-']"):
                href=a.get("href")
                if href:
                    if href.startswith("/"): href=BASE+href
                    links.append(href)
            links=list(dict.fromkeys(links))[:2]
    for idx,ch_url in enumerate(links[:2]):
        name = f"reader_{slug.replace('.html','')}_c{idx}.html"
        rhtml,_ = fetch(ch_url, name, subdir="raw")
        if not rhtml: continue
        # gate bilgileri logla
        fpx = re.search(r'_fpx\s*=\s*"([^"]+)"', rhtml)
        has_rdm = "rdm-" in rhtml
        has_turn = "turnstile" in rhtml.lower() or "cf-turnstile" in rhtml
        has_slider = "sliderCanvas" in rhtml
        img_count = rhtml.count("img_part.php")
        print(f"    reader {name}: rdm={has_rdm} fpx={fpx.group(1) if fpx else None} img_part={img_count} turnstile={has_turn} slider={has_slider}")
        time.sleep(0.5)

# ---- 7. ÖZET ----
summary = {
    "collected_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    "manga_slugs_total": len(manga_slugs),
    "manga_slugs": manga_slugs,
    "list_keys_found": len(list_keys),
    "files_live": sorted([str(p) for p in OUT_LIVE.glob("*")]),
    "files_raw": sorted([str(p) for p in OUT_RAW.glob("*")]),
}
save("summary.json", json.dumps(summary, indent=2, ensure_ascii=False), subdir="live")
print("\n=== BİTTİ ===")
print(json.dumps(summary, indent=2, ensure_ascii=False))
