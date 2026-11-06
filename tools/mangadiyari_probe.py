#!/usr/bin/env python3
"""Manga Diyarı (mangadiyari.com) canlı API sözleşmesi sondası.

Eklentiyle **birebir aynı** uç noktaları ve sorgu parametrelerini kullanır:

  * popüler / son güncellenen listeleri ve sayfalama
  * arama (düz, Türkçe aksanlı, aksansız, çok kelimeli)
  * sıralama / durum / tür filtreleri (Türkçe ve İngilizce tür adları)
  * seri detayı + bölüm listesi (bölüm numarası boşlukları/tekrarları)
  * bölüm sayfaları ve gerçek görsel çözümü (boyut + format)

Yalnızca sayılar, HTTP durumları, başlık adları ve görsel üst verisi raporlanır;
HTML gövdesi, çerez veya tam sayfa URL'si dosyaya yazılmaz. Çıktı JSON'dur ve
`--summary` ile tek satırlık özet basılır (CI loglarında okunabilir olsun diye).
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import unicodedata
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.parse import quote, urljoin

import requests

try:  # Pillow yalnızca görsel doğrulaması için gerekli.
    from PIL import Image
except Exception:  # pragma: no cover - Pillow yoksa görsel testi atlanır
    Image = None

BASE = "https://mangadiyari.com"
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
)
TIMEOUT = 40
PAGE_SIZE = 24

# `Filters.kt` içindeki her tür grubunun adayları: (Türkçe ad, gönderilecek etiket, aday etiketler).
# Site etiketi birebir eşleştiriyor ve eski seriler İngilizce, yeniler Türkçe etiket taşıyor; bu yüzden
# her grup için **tek** bir etiket gönderilir ve hangisinin gerçekten sonuç verdiği burada ölçülür.
# `tests/test_mangadiyari_probe.py` bu tabloyu `Filters.kt` ile karşılaştırır.
GENRE_GROUPS: list[tuple[str, str, tuple[str, ...]]] = [
    # (görünen ad, eklentinin gönderdiği etiket, canlı ölçümde denenecek adaylar)
    ("Aksiyon", "Aksiyon", ("Aksiyon", "Action")),
    ("Macera", "Macera", ("Macera", "Adventure")),
    ("Komedi", "Komedi", ("Komedi", "Comedy")),
    ("Dram", "Dram", ("Dram", "Drama")),
    ("Fantastik", "Fantezi", ("Fantezi", "Fantasy")),
    ("Tarihi", "Tarihi", ("Tarihi", "Historical")),
    ("Korku", "Korku", ("Korku", "Horror")),
    ("Isekai", "Isekai", ("Isekai",)),
    ("Dövüş Sanatları", "Dövüş Sanatları", ("Dövüş Sanatları", "Martial Arts")),
    ("Gizem", "Gizem", ("Gizem", "Mystery")),
    # Türkçe etiketle seri yok, İngilizcesiyle var (2026-11-06 ölçümü: 0 / 3).
    ("Reenkarnasyon", "Reincarnation", ("Reenkarnasyon", "Reincarnation")),
    ("Romantik", "Romantizm", ("Romantizm", "Romance", "Romantic")),
    ("Okul", "Okul Hayatı", ("Okul Hayatı", "School Life")),
    ("Bilim Kurgu", "Bilim Kurgu", ("Bilim Kurgu", "Science Fiction", "Sci-Fi")),
    ("Doğaüstü", "Doğaüstü", ("Doğaüstü", "Supernatural")),
    ("Gerilim", "Gerilim", ("Gerilim", "Thriller")),
    ("Ecchi", "Ecchi", ("Ecchi",)),
    ("Harem", "Harem", ("Harem",)),
    ("Josei", "Josei", ("Josei",)),
    ("Yetişkin", "Müstehcen", ("Müstehcen", "Mature")),
    ("Mecha", "Mecha", ("Mecha",)),
    ("Psikolojik", "Psikolojik", ("Psikolojik", "Psychological")),
    ("Seinen", "Seinen", ("Seinen",)),
    ("Shoujo", "Shoujo", ("Shoujo",)),
    ("Shounen", "Shounen", ("Shounen",)),
    ("Günlük Yaşam", "Slice of Life", ("Günlük Yaşam", "Slice of Life")),
    ("Spor", "Spor", ("Spor", "Sports")),
    ("Trajedi", "Trajedi", ("Trajedi", "Tragedy")),
    ("Webtoon", "Webtoon", ("Webtoon",)),
    ("Manhwa", "Manhwa", ("Manhwa",)),
    ("Manhua", "Manhua", ("Manhua",)),
    ("Murim", "Murim", ("Murim",)),
    ("Sistem", "Sistem", ("Sistem", "System")),
    ("Şeytan", "Şeytan", ("Şeytan", "Demon")),
    ("Ters Harem", "Ters Harem", ("Ters Harem", "Reverse Harem")),
]


def strip_diacritics(value: str) -> str:
    """Türkçe metni aksansız/ASCII hâle getirir (ı -> i, ş -> s, ...).

    Arama karşılaştırmalarında kullanılır: site hem aksanlı hem aksansız sorguda aynı seriyi
    buluyor, eklenti de kullanıcının yazdığı metni olduğu gibi gönderiyor.
    """
    lowered = value.replace("ı", "i").replace("İ", "i").lower()
    folded = unicodedata.normalize("NFKD", lowered)
    return "".join(ch for ch in folded if not unicodedata.combining(ch))


class Probe:
    def __init__(self) -> None:
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": USER_AGENT,
                "Accept": "application/json, text/plain, */*",
                "Accept-Language": "tr-TR,tr;q=0.9,en;q=0.7",
                "Referer": BASE + "/",
                "Origin": BASE,
            }
        )
        self.report: dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "base": BASE,
            "checks": {},
            "genres": {},
            "failures": [],
            "warnings": [],
        }

    # ── HTTP yardımcıları ────────────────────────────────────────────────────

    def get_json(self, path: str, timeout: int = TIMEOUT) -> tuple[int, Any]:
        response = self.session.get(BASE + path, timeout=timeout)
        try:
            payload = response.json()
        except ValueError:
            payload = None
        return response.status_code, payload

    def record(self, name: str, ok: bool, hard: bool = True, **data: Any) -> bool:
        """Kaydeder; `hard` olmayan kontroller ortam (CDN/ASN) kaynaklı olabildiği için
        sözleşme hatası sayılmaz, uyarı olarak raporlanır."""
        entry = {"ok": ok, "hard": hard, **data}
        self.report["checks"][name] = entry
        if not ok:
            self.report["failures" if hard else "warnings"].append({"check": name, **data})
        flag = "OK  " if ok else ("WARN" if not hard else "FAIL")
        detail = " ".join(f"{key}={value}" for key, value in data.items())
        print(f"{flag} {name}: {detail}", flush=True)
        return ok

    # ── Tek tek kontroller ───────────────────────────────────────────────────

    def check_listing(self) -> list[dict[str, Any]]:
        status, payload = self.get_json(f"/api/series?sort=popular&limit={PAGE_SIZE}&page=1")
        series = (payload or {}).get("series") if isinstance(payload, dict) else None
        self.record(
            "popular",
            status == 200 and isinstance(series, list) and len(series) > 0,
            http=status,
            count=len(series or []),
            hasMore=(payload or {}).get("hasMore"),
        )

        status, payload = self.get_json(f"/api/series/latest-updates?limit={PAGE_SIZE}&page=1")
        updates = (payload or {}).get("updates") if isinstance(payload, dict) else None
        self.record(
            "latest",
            status == 200 and isinstance(updates, list) and len(updates) > 0,
            http=status,
            count=len(updates or []),
            hasMore=(payload or {}).get("hasMore"),
        )
        first = (updates or [{}])[0] if updates else {}
        self.record(
            "latest-shape",
            bool(first.get("slug")) and isinstance(first.get("chapters", []), list),
            keys=sorted(first.keys())[:12],
        )
        return series or []

    def check_search(self, sample_title: str) -> None:
        # 1) Tam başlık (canlı site büyük/küçük harf duyarsız arıyor; bu yüzden
        #    eklenti de aynı biçimde davranmalı)
        full = sample_title
        status, payload = self.get_json(f"/api/series?search={quote(full)}&sort=latest&limit={PAGE_SIZE}&page=1")
        titles = [item.get("title") for item in (payload or {}).get("series", [])]
        self.record("search-full", status == 200 and full in titles, http=status, hits=len(titles))

        # 2) Küçük harfli aksansız hâli (site slug üzerinden eşleştiriyor)
        ascii_lower = strip_diacritics(full)
        status, payload = self.get_json(f"/api/series?search={quote(ascii_lower)}&sort=latest&limit={PAGE_SIZE}&page=1")
        hits = (payload or {}).get("series", [])
        self.record(
            "search-ascii",
            status == 200 and len(hits) > 0,
            http=status,
            hits=len(hits),
            query=ascii_lower,
        )

        # 3) Aksanlı küçük harf (site Türkçe karakterlerle ne yapıyor?)
        turkish_lower = full.replace("İ", "i").replace("I", "ı").lower()
        status, payload = self.get_json(f"/api/series?search={quote(turkish_lower)}&sort=latest&limit={PAGE_SIZE}&page=1")
        hits = (payload or {}).get("series", [])
        self.record(
            "search-turkish-lower",
            status == 200 and len(hits) > 0,
            http=status,
            hits=len(hits),
        )

        # 4) Uzun kelimeler tek tek (eklentinin yedek davranışı)
        words = [word for word in strip_diacritics(full).split() if len(word) >= 4]
        word_hits: dict[str, int] = {}
        for word in words[:3]:
            status, payload = self.get_json(f"/api/series?search={quote(word)}&sort=latest&limit={PAGE_SIZE}&page=1")
            word_hits[word] = len((payload or {}).get("series", [])) if status == 200 else -1
        self.record(
            "search-words",
            any(count > 0 for count in word_hits.values()),
            hits=word_hits,
        )

        # 5) Olmayan başlık boş liste mi dönüyor (hata değil)?
        status, payload = self.get_json("/api/series?search=zzqqxx&sort=latest&limit=5&page=1")
        self.record(
            "search-miss",
            status == 200 and (payload or {}).get("series") == [],
            http=status,
        )

    def check_filters(self) -> None:
        for name, query in (
            ("sort-popular", "sort=popular"),
            ("sort-rating", "sort=rating"),
            ("sort-title", "sort=title"),
            ("status-ongoing", "status=ongoing"),
            ("status-completed", "status=completed"),
            ("type-manhwa", "type=manhwa"),
            ("type-manhua", "type=manhua"),
            ("type-manga", "type=manga"),
            ("genre-turkish", "genre=" + quote("Aksiyon")),
            ("genre-english", "genre=Action"),
            ("genre-combined", "genre=" + quote("Aksiyon,Fantezi")),
            ("pagination", "page=2"),
        ):
            suffix = "" if query.startswith("page=") else "&page=1"
            status, payload = self.get_json(f"/api/series?{query}&limit={PAGE_SIZE}{suffix}")
            count = len((payload or {}).get("series", [])) if isinstance(payload, dict) else -1
            self.record(f"filter-{name}", status == 200 and count >= 0, http=status, count=count, hasMore=(payload or {}).get("hasMore"))

    def check_details_and_pages(self, series: list[dict[str, Any]]) -> None:
        if not series:
            self.record("details", False, reason="no series")
            return
        target = next((item for item in series if (item.get("chapterCount") or 0) > 0), series[0])
        slug = target.get("slug")
        status, payload = self.get_json(f"/api/series/{quote(slug)}")
        details = (payload or {}).get("series") if isinstance(payload, dict) else None
        chapters = (payload or {}).get("chapters") if isinstance(payload, dict) else None
        self.record(
            "details",
            status == 200 and details is not None and isinstance(chapters, list),
            http=status,
            slug=slug,
            chapters=len(chapters or []),
        )
        if not chapters:
            # Bölümsüz seri hata değil (bkz. /api/series/{slug} -> chapters: []).
            self.record("details-empty-series", True, slug=slug)
            return

        numbers = [chapter.get("chapter_number") for chapter in chapters]
        usable = [number for number in numbers if isinstance(number, (int, float))]
        duplicates = len(usable) - len(set(usable))
        gaps = 0
        if usable and all(float(number).is_integer() for number in usable):
            ordered = sorted(int(number) for number in usable)
            gaps = sum(1 for previous, current in zip(ordered, ordered[1:]) if current - previous > 1)
        self.record(
            "chapter-numbers",
            len(usable) == len(numbers),
            hard=False,
            total=len(numbers),
            numeric=len(usable),
            duplicates=duplicates,
            gaps=gaps,
            sample=usable[:3],
        )

        # Bölüm sayfaları: ilk ve son bölüm
        for label, chapter in (("first", chapters[0]), ("last", chapters[-1])):
            chapter_id = chapter.get("id")
            status, payload = self.get_json(f"/api/chapters/{chapter_id}")
            pages = (payload or {}).get("pages") if isinstance(payload, dict) else None
            ok = status == 200 and isinstance(pages, list) and len(pages) > 0
            self.record(f"pages-{label}", ok, http=status, chapter=chapter.get("chapter_number"), pages=len(pages or []))
            if ok:
                self.check_image(pages, label)
                self.report["checks"][f"pages-{label}"]["absolute"] = sum(
                    1 for page in pages if str(page.get("display_image", "")).startswith("http")
                )

    def check_image(self, pages: list[dict[str, Any]], label: str) -> None:
        if Image is None:
            self.record(f"image-{label}", False, hard=False, reason="Pillow missing")
            return
        for index in (0, len(pages) - 1):
            page = pages[index]
            raw = page.get("display_image") or page.get("image_path")
            if not raw:
                self.record(f"image-{label}", False, reason="page carries no image url")
                return
            url = raw if raw.startswith("http") else urljoin(BASE + "/", raw)
            try:
                response = self.session.get(url, timeout=TIMEOUT, headers={"Accept": "image/avif,image/webp,image/*,*/*;q=0.8"})
            except requests.RequestException as exc:
                self.record(f"image-{label}", False, hard=False, error=type(exc).__name__)
                return
            if response.status_code != 200 or len(response.content) <= 100:
                self.record(
                    f"image-{label}",
                    False,
                    hard=False,
                    http=response.status_code,
                    bytes=len(response.content),
                    relative=not raw.startswith("http"),
                )
                return
            try:
                with Image.open(io.BytesIO(response.content)) as image:
                    image.load()
                    size = image.size
                    fmt = image.format
            except Exception as exc:
                self.record(f"image-{label}", False, hard=False, decode=type(exc).__name__)
                return
            self.record(
                f"image-{label}",
                size[0] > 0 and size[1] > 0,
                hard=False,
                http=response.status_code,
                bytes=len(response.content),
                format=fmt,
                width=size[0],
                height=size[1],
                relative=not raw.startswith("http"),
            )

    def _total(self, path: str) -> int:
        status, payload = self.get_json(path)
        if status != 200 or not isinstance(payload, dict):
            return -1
        total = payload.get("total")
        return total if isinstance(total, int) else len(payload.get("series") or [])

    def genre_total(self, label: str) -> int:
        status, payload = self.get_json(f"/api/series?genre={quote(label)}&limit=1&page=1")
        if status != 200 or not isinstance(payload, dict):
            return -1
        total = payload.get("total")
        return total if isinstance(total, int) else len(payload.get("series") or [])

    def check_genre_catalog(self) -> None:
        """Her filtre grubu için ölçüm: seçilen etiket sonuç veriyor mu, daha iyi aday var mı.

        API virgüllü listeyi AND olarak okuyor ve tekrarlı `genre` parametresinde yalnız ilkini
        kullanıyor; yani "Türkçe veya İngilizce" tek sorguda ifade edilemiyor. Bu yüzden her grup
        tek etiket gönderir ve etiketin gerçekten seri döndürdüğü burada ölçülür.
        """
        dead: list[str] = []
        better: dict[str, str] = {}
        chosen_totals: dict[str, int] = {}
        for name, chosen, candidates in GENRE_GROUPS:
            totals = {candidate: self.genre_total(candidate) for candidate in candidates}
            chosen_total = totals.get(chosen, 0)
            chosen_totals[name] = chosen_total
            if chosen_total <= 0:
                # Etiket katalogda yok: sitede karşılığı olan başka bir ad varsa onu bildir.
                alternative = next((c for c in candidates if totals.get(c, 0) > 0), None)
                if alternative:
                    better[name] = alternative
                else:
                    dead.append(name)
            self.report["genres"][name] = {"chosen": chosen, "totals": totals}
        # Eklentinin gönderdiği etiket boş dönüyor ama sitede karşılığı olan başka bir ad varsa bu
        # bizim hatamızdır (filtre sessizce sonuçsuz kalır) ve derleme durmalı.
        self.record(
            "genre-label-mismatch",
            not better,
            better_label_available=better,
            groups=len(GENRE_GROUPS),
        )
        # Hiçbir adı olmayan grup ise katalogda henüz seri olmadığı anlamına gelir; bilgi amaçlı.
        self.record(
            "genre-labels",
            True,
            hard=False,
            groups=len(GENRE_GROUPS),
            with_results=sum(1 for total in chosen_totals.values() if total > 0),
            empty_labels=dead,
        )

        # Virgüllü liste AND demek: tek etiket her zaman tercih edilmeli (kanıt).
        for label, other in (("Dram", "Drama"), ("Fantezi", "Fantasy")):
            single = self.genre_total(label)
            combined = self._total(f"/api/series?genre={quote(label + ',' + other)}&limit=1&page=1")
            self.record(
                f"genre-and-{label}",
                combined <= single,
                hard=False,
                single=single,
                combined=combined,
            )

    def check_web_routes(self, series: list[dict[str, Any]]) -> None:
        """Eklentinin ürettiği web adresleri gerçekten açılıyor mu (tarayıcıda açılan sayfa)."""
        slug = next(
            (item.get("slug") for item in series if (item.get("chapterCount") or 0) > 0),
            (series[0].get("slug") if series else None),
        )
        if not slug:
            self.record("web-routes", False, reason="no slug")
            return
        for label, path in (
            ("series-page", f"/series/{slug}"),
            ("reader-page", f"/seri/{slug}/bolum/1"),
        ):
            marker = b"/api/"
            try:
                response = self.session.get(BASE + path, timeout=TIMEOUT)
            except requests.RequestException as exc:
                self.record(f"web-{label}", False, error=type(exc).__name__)
                continue
            body = response.content
            self.record(
                f"web-{label}",
                response.status_code == 200 and marker in body,
                hard=False,
                http=response.status_code,
                bytes=len(body),
                api_marker=marker in body,
                final=response.url.replace(BASE, ""),
            )

    def check_cdn_headers(self, series: list[dict[str, Any]]) -> None:
        """Görsellerin Referer/Origin ihtiyacını ve yönlendirmeleri ölçer."""
        if not series:
            return
        cover = series[0].get("cover_url")
        if not cover:
            self.record("cover", False, reason="cover_url missing")
            return
        url = cover if cover.startswith("http") else urljoin(BASE + "/", cover)
        for label, headers in (
            ("with-referer", {"Referer": BASE + "/", "Origin": BASE}),
            ("no-headers", {}),
        ):
            try:
                response = self.session.get(url, timeout=TIMEOUT, headers=headers, allow_redirects=True)
            except requests.RequestException as exc:
                self.record(f"cover-{label}", False, hard=False, error=type(exc).__name__)
                continue
            self.record(
                f"cover-{label}",
                response.status_code == 200 and len(response.content) > 1000,
                hard=False,
                http=response.status_code,
                bytes=len(response.content),
                content_type=response.headers.get("Content-Type", "").split(";")[0],
                host=response.url.split("/")[2],
            )

    def run(self) -> dict[str, Any]:
        series = self.check_listing()
        if series:
            self.check_search(series[0]["title"])
        else:
            self.check_search("Ben Ölümsüzüm")
        self.check_filters()
        self.check_genre_catalog()
        self.check_details_and_pages(series)
        self.check_cdn_headers(series)
        self.check_web_routes(series)
        self.report["ok"] = not self.report["failures"]
        return self.report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--summary", action="store_true")
    args = parser.parse_args()

    report = Probe().run()
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.summary:
        failed = [item["check"] for item in report["failures"]]
        print(
            "SUMMARY "
            + json.dumps(
                {
                    "ok": report["ok"],
                    "checks": len(report["checks"]),
                    "failed": failed,
                    "warnings": [item["check"] for item in report["warnings"]],
                },
                ensure_ascii=False,
            ),
            flush=True,
        )
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
