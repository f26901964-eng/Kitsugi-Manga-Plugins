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

def _current_referer():
    # Use last detail URL as referer if available, else BASE
    try:
        # Try to get last fetched detail URL from session history? fallback to BASE
        # We store globally
        return globals().get("_last_detail_url", BASE+"/")
    except: return BASE+"/"
def ddos_bypass(resp, referer=None):
    """Manga-TR DDoS-Guard 403 -> Kotlin DDoSGuardInterceptor ile aynı: random __ddg2 + check.js + well-known"""
    try:
        is_ddos = resp.status_code == 403 or (resp.status_code == 200 and "check.ddos-guard.net/check.js" in resp.text)
        # Extra check: Server ddos-guard + small body (but POST 0-byte also Server ddos-guard)
        if not is_ddos and resp.headers.get("Server","") == "ddos-guard" and len(resp.text) < 2000:
            is_ddos = True
        if not is_ddos:
            return False
        ref = referer or _current_referer()
        print(f"  [DDOS-GUARD] 403 tespit, bypass deneniyor... referer={ref[:60]}")
        # 1) random __ddg2 cookie (gallery-dl kanıtlı)
        import random, string, time as _t
        try:
            if "__ddg2" not in sess.cookies.get_dict():
                rnd = ''.join(random.choices(string.ascii_letters + string.digits, k=16))
                # domain .manga-tr.com
                sess.cookies.set("__ddg2", rnd, domain=".manga-tr.com", path="/")
                sess.cookies.set("__ddg2", rnd, domain="manga-tr.com", path="/")
                sess.cookies.set("__ddg2", rnd, domain="www.manga-tr.com", path="/")
                print(f"    injected random __ddg2={rnd[:8]}...")
        except Exception as ce:
            print(f"    random cookie fail {ce}")
        # 2) official 2-request flow: check.js -> /.well-known/ddos-guard/...
        headers_wk = {"Referer": ref, "Origin": BASE}
        wk = sess.get("https://check.ddos-guard.net/check.js", headers=headers_wk, timeout=10)
        # Kotlin: Regex("""['\"](/.well-known/ddos-guard/[^'\"]+)['\"]""")
        m = re.search(r"[\"'](\/.well-known\/ddos-guard\/[^\"']+)[\"']", wk.text)
        if m:
            path = m.group(1)
        else:
            # Fallback: any single/double quoted path
            m2 = re.search(r"'([^']+)'", wk.text)
            if not m2:
                m2 = re.search(r'"([^"]+)"', wk.text)
            if not m2:
                print(f"    well-known parse fail: {wk.text[:600]}")
                return True
            path = m2.group(1)
            wk2 = re.search(r"(\/.well-known\/ddos-guard\/[^\s'\"\"]+)", wk.text)
            if wk2:
                path = wk2.group(1)
        if not path.startswith("/"): path="/"+path
        # Extract well-known via regex as Kotlin does
        check_url = f"{BASE}{path}"
        headers_ck = {"Referer": ref, "Origin": BASE}
        try:
            cr = sess.get(check_url, headers=headers_ck, timeout=10)
            print(f"    bypass ok: {cr.status_code} cookies={list(sess.cookies.get_dict().keys())[:8]} url={check_url[:80]}")
            if cr.status_code != 200:
                # try www fallback
                alt = check_url.replace("https://manga-tr.com", "https://www.manga-tr.com")
                if alt != check_url:
                    cr2 = sess.get(alt, headers=headers_ck, timeout=10)
                    print(f"    alt bypass {cr2.status_code} {alt[:80]}")
        except Exception as ce:
            print(f"    check_url fail {ce}")
        time.sleep(0.4)
        return True
    except Exception as e:
        print(f"    ddos bypass fail: {e}")
        import traceback; traceback.print_exc()
        return False

def fetch(url, name, subdir="live", method="GET", data=None, headers_extra=None, retry_ddos=True):
    headers = {}
    if headers_extra: headers.update(headers_extra)
    verb = "POST" if method=="POST" else "GET"
    # Track last detail URL for referer
    if url==BASE+"/" or "/manga-" in url:
        globals()["_last_detail_url"]=url
    print(f"[{verb}] {name} <- {url} data={data if method=='POST' else ''}")
    try:
        if method=="POST":
            r = sess.post(url, data=data, headers=headers, timeout=20)
        else:
            r = sess.get(url, headers=headers, timeout=20)
        # DDoS-Guard ise bir kez bypass dene ve tekrar iste (also for 403 with 0-byte)
        should_retry = (r.status_code==403 or "check.ddos-guard.net" in r.text or (len(r.text)==0 and r.headers.get("Server","").lower()=="ddos-guard"))
        if retry_ddos and should_retry:
            referer_for_bypass = headers.get("Referer") or headers.get("referer") or _current_referer()
            if ddos_bypass(r, referer=referer_for_bypass):
                time.sleep(0.8)
                if method=="POST":
                    r = sess.post(url, data=data, headers=headers, timeout=20)
                else:
                    r = sess.get(url, headers=headers, timeout=20)
                print(f"  [retry] {r.status_code} {len(r.text)}b")
        # Log body preview if empty or 403
        preview = r.text[:600].replace("\n"," ") if r.text else "<EMPTY>"
        if r.status_code==403 or len(r.text)==0:
            print(f"  !! {r.status_code} {len(r.text)}b ct={r.headers.get('content-type','')} preview={preview[:400]} headers={dict(r.headers)}")
        else:
            print(f"  {r.status_code} {len(r.text)}b ct={r.headers.get('content-type','')}")
        save(name, r.text, subdir)
        # save headers too
        try:
            save(name+".headers.json", json.dumps(dict(r.headers), indent=2, ensure_ascii=False), subdir)
        except Exception as he:
            print(f"  headers save fail {he}")
        save(name+".meta.json", json.dumps({"url": url, "status": r.status_code, "final_url": r.url}, indent=2, ensure_ascii=False), subdir)
        return r.text, r
    except Exception as e:
        print(f"  FAIL {e}")
        import traceback; traceback.print_exc()
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
# Collect all CSS URLs seen for seed calc
_all_css_urls = set([BASE+"/app/manga/themes/default/assets/css/mtr.min.css", BASE+"/app/manga/themes/default/assets/css/mtr.min.css?v=0411-6"])
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
    # script src — fetch ALL js (manga-tr ve cover-variants) to debug seed logic
    scripts = [s.get("src") for s in soup.select("script[src]") if s.get("src")]
    for js_src in scripts:
        if any(x in js_src for x in ["manga-tr", "cover-variants", "yorum-icerik"]):
            js_url = js_src if js_src.startswith("http") else BASE+ (js_src if js_src.startswith("/") else "/"+js_src)
            slime = slug.replace('.html','')
            # save with distinct name
            tag = "manga-tr" if "manga-tr" in js_src else ("cover-variants" if "cover-variants" in js_src else "other")
            js_text,_ = fetch(js_url, f"js_{slime}_{tag}.js", subdir="raw")
            if js_text:
                inline_keys += re.findall(r"listKey:\s*'([^']+)'", js_text)
    # collect CSS hrefs for seed
    for link in soup.select("link[href*='.css']"):
        href = link.get("href","")
        if href:
            full = href if href.startswith("http") else BASE + (href if href.startswith("/") else "/" + href)
            _all_css_urls.add(full)
    if inline_keys or d_list:
        # en uzun key genelde doğru
        best = sorted(set(inline_keys + ([d_list] if d_list else [])), key=len, reverse=True)[0]
        list_keys[slug]=best
        print(f"  listKey {slug} -> {best[:20]}...")
    time.sleep(0.4)

save("list_keys.json", json.dumps(list_keys, indent=2, ensure_ascii=False), subdir="live")
print(f"[INFO] toplanan CSS URL sayısı: {len(_all_css_urls)} -> {list(_all_css_urls)[:5]}")

# ---- 5. BÖLÜM LİSTESİ API — yeni yöntem (manga slug + _sig) ---
import hashlib as _hashlib
def decode_mtr_js_blob():
    """manga-tr.min.js içindeki şifreli blob'u çöz ve /tmp/decoded.js'e kaydet; seed mantığını doğrula"""
    try:
        # Use already fetched js file
        for p in OUT_RAW.glob("js_*_manga-tr.js"):
            txt = p.read_text(errors='ignore')
            m = re.search(r'var\s+\w+\s*=\s*"([A-Za-z0-9+/=_-]{1000,})"', txt)
            if not m: continue
            blob = m.group(1)
            Y=[234,13,239,92,33,38,227,1,24,94,238,9,38,45,231,24,234,30,235,16,30,13,15,57,236,18,28]
            R="".join(chr(((y ^ 106) - 19) & 255) for y in Y)
            import base64
            G=base64.b64decode(blob)
            k=len(G)
            M=list(range(256))
            I=0
            for q in range(256):
                I=(I+M[q]+ord(R[q % len(R)]))%256
                M[q],M[I]=M[I],M[q]
            q=I2=0
            yb=bytearray(k)
            for v in range(k):
                q=(q+1)%256
                I2=(I2+M[q])%256
                M[q],M[I2]=M[I2],M[q]
                yb[v]=G[v]^M[(M[q]+M[I2])%256]
            decoded=yb.decode('utf-8', errors='ignore')
            # Save for analysis
            save(f"decoded_mtr_chapters.js", decoded, subdir="raw")
            # Extract seed logic: should contain getComputedStyle and --mdx-white
            if "--mdx-white" in decoded:
                print(f"  [decode] mtr decoded ok len={len(decoded)} contains seed logic")
            else:
                print(f"  [decode] mtr decoded len={len(decoded)} no seed? preview {decoded[:500]}")
            return decoded
    except Exception as e:
        print(f"  [decode] fail {e}")
        import traceback; traceback.print_exc()
    return None

def get_seed():
    # First try to decode mtr js to verify seed logic, but still fetch CSS
    decode_mtr_js_blob()
    tried = []
    css_contents = {}
    try:
        for css_url in list(_all_css_urls)[:12]:
            try:
                r = sess.get(css_url, timeout=10)
                tried.append(f"{css_url} -> {r.status_code}")
                if r.status_code==200:
                    # Save CSS for debug
                    fname = "css_" + re.sub(r'[^a-zA-Z0-9]', '_', css_url.split('/')[-1].split('?')[0]) + ".css"
                    save(fname, r.text, subdir="raw")
                    css_contents[css_url]=r.text
                    if "--mdx-white" in r.text or "--mdx-black" in r.text:
                        wm = re.search(r'--mdx-white\s*:\s*(\d+)', r.text)
                        bm = re.search(r'--mdx-black\s*:\s*(\d+)', r.text)
                        w = int(wm.group(1)) if wm else 0
                        b = int(bm.group(1)) if bm else 0
                        print(f"  [seed] mdx-white={w} mdx-black={b} seed={w+b} from {css_url}")
                        # Save snippet
                        save(f"css_seed_from_{fname}.txt", f"w={w} b={b} seed={w+b} url={css_url}", subdir="raw")
                        return w+b
                    # Log snippet for debug
                    snippet = r.text[:2000]
                    if "--mdx" in snippet:
                        print(f"  [seed] css snippet {css_url[:60]}: {snippet[:400]}")
            except Exception as ce:
                tried.append(f"{css_url} err {ce}")
        print(f"  [seed] tried {tried} -> fallback 0")
        # Dump all --mdx occurrences
        for url, txt in css_contents.items():
            if "--mdx" in txt:
                print(f"  [seed] --mdx found in {url}: {txt[txt.find('--mdx')-20:txt.find('--mdx')+100]}")
    except Exception as e:
        print(f"  [seed] fail {e}")
    return 0

def gen_sig(manga_slug, seed, timestamp):
    raw = f"{manga_slug}_{seed}_{timestamp}"
    return _hashlib.sha256(raw.encode()).hexdigest()

def gen_sig_fallback(manga_slug, seed, timestamp):
    # FNV fallback matching Kotlin
    raw = f"{manga_slug}_{seed}_{timestamp}"
    try:
        h = 0x811c9dc5
        for c in raw:
            h = (h ^ ord(c)) & 0xffffffff
            h = (h * 0x01000193) & 0xffffffff
        return format(h, 'x')
    except: return "0"

seed = get_seed()
# Save seed for later inspection
save("seed_info.json", json.dumps({"seed": seed, "css_urls": list(_all_css_urls), "detected": seed}, indent=2, ensure_ascii=False), subdir="live")
print(f"[INFO] seed={seed} — will try POST with Referer=detail URL and Origin")

# Yeni API: manga, tur, offset, _t, _sig — correct Referer per manga
for slug in manga_slugs[:12]:
    mslug = slug.replace("manga-","").replace(".html","")
    # data-tur'u detail'den al
    tur = ""
    detail_url = BASE + "/" + slug
    try:
        detail_p = OUT_RAW / f"detail_{slug}"
        if detail_p.exists():
            dt = detail_p.read_text(errors='ignore')
            mt = re.search(r'id="results"[^>]*data-tur="([^"]*)"', dt)
            if mt: tur = mt.group(1)
            # Also try data-tur attribute elsewhere
            mt2 = re.search(r'data-tur="([^"]*)"', dt)
            if mt2 and not tur: tur = mt2.group(1)
    except: pass
    print(f"  [manga] {mslug} tur='{tur}' detail={detail_url}")
    for off in [0, 20, 120]:
        ts = int(time.time())
        sig = gen_sig(mslug, seed, ts)
        sig_fnv = gen_sig_fallback(mslug, seed, ts)
        print(f"    POST manga={mslug} offset={off} _t={ts} _sig={sig[:12]}... fnv={sig_fnv[:12]} seed={seed}")
        headers_ajax = {"X-Requested-With":"XMLHttpRequest", "Accept":"*/*", "Content-Type":"application/x-www-form-urlencoded", "Referer": detail_url, "Origin": BASE}
        data = {"manga": mslug, "tur": tur, "offset": str(off), "_t": str(ts), "_sig": sig}
        # Also try without tur if empty vs with
        txt, resp = fetch(BASE+"/cek/fetch_pages_manga.php", f"api_new_{mslug}_off{off}.html", subdir="raw", method="POST", data=data, headers_extra=headers_ajax)
        # If empty/403, retry with seed 0 immediately
        if resp is not None and (resp.status_code==403 or len(resp.text)==0):
            print(f"    !! empty/403 -> retry seed 0")
            ts2 = int(time.time())
            sig0 = gen_sig(mslug, 0, ts2)
            data0 = {"manga": mslug, "tur": tur, "offset": str(off), "_t": str(ts2), "_sig": sig0}
            headers_ajax2 = {"X-Requested-With":"XMLHttpRequest", "Accept":"*/*", "Content-Type":"application/x-www-form-urlencoded", "Referer": detail_url, "Origin": BASE}
            fetch(BASE+"/cek/fetch_pages_manga.php", f"api_new_{mslug}_off{off}_seed0.html", subdir="raw", method="POST", data=data0, headers_extra=headers_ajax2)
            time.sleep(0.6)
        time.sleep(0.5)
    # bolum_cek de dene (arama) — may not need seed
    ts = int(time.time()); sig = gen_sig(mslug, seed, ts)
    headers_ajax_ch = {"X-Requested-With":"XMLHttpRequest", "Accept":"*/*", "Content-Type":"application/x-www-form-urlencoded", "Referer": detail_url, "Origin": BASE}
    fetch(BASE+"/cek/bolum_cek.php", f"api_bolum_cek_{mslug}.html", subdir="raw", method="POST", data={"manga": mslug, "chapter":"1", "_t": str(ts), "_sig": sig}, headers_extra=headers_ajax_ch)
    time.sleep(0.4)

# Eski fallback'ler de kalsın (geriye dönük)
headers_ajax_old = {"X-Requested-With":"XMLHttpRequest", "Accept":"*/*", "Content-Type":"application/x-www-form-urlencoded"}
for slug,key in list_keys.items():
    for off in [0, 20, 100]:
        data = {"chapter_list_key": key}
        if off!=0: data["offset"]=str(off)
        fetch(BASE+"/cek/fetch_pages_manga.php", f"api_chapters_{slug.replace('.html','')}_off{off}.html", subdir="raw", method="POST", data=data, headers_extra=headers_ajax_old)
        time.sleep(0.3)
    time.sleep(0.5)

for slug in manga_slugs[:6]:
    mslug = slug.replace("manga-","").replace(".html","")
    fetch(BASE+f"/cek/fetch_pages_manga.php?manga_cek={mslug}", f"api_manga_cek_{mslug}.html", subdir="raw", headers_extra={"X-Requested-With":"XMLHttpRequest"})
    time.sleep(0.3)
    fetch(BASE+"/cek/fetch_pages_manga.php", f"api_manga_cek_{mslug}_post.html", subdir="raw", method="POST", data={"manga_cek": mslug, "offset":"0"}, headers_extra=headers_ajax_old)
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
