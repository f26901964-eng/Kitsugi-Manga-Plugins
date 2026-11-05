# MangaTR — güncel plan ve durum

## Aktif hedef

Aktif dal `arena/720282f3-kitsugi-manga-plugins`; yeni aday `1.6.34 / 106034`.

Sitenin reader JavaScript/CSS sözleşmesi Android bitmap yoluna uyarlanıyor. Site ekranda eksik veya yarım görsel göstermiyor; CDN'den gelen parçalı/karıştırılmış bitmap'i reader içinde doğru sıraya koyup gerekli parçaları çevirerek tek tam görsel olarak sunuyor. Eklentinin de aynı sonucu üretmesi gerekiyor.

`1.6.33 / 106033` gate/config sorununu açtı ancak her CSS strip'ini ayrı `Page` yaptığı için kullanıcı cihazında yarım/kesik sayfalar üretti. Bu model reddedildi; `1.6.33` ürün düzeltmesi olarak kabul edilmiyor.

## Kesin kabul ölçütleri

- Reader `125451`, Bölüm 1: 52 kaynak eleman → **52 tam okuyucu sayfası**.
- Reader `125989`, Bölüm 2: 26 kaynak eleman → **26 tam okuyucu sayfası**.
- Aynı kaynağın strip'leri ayrı sayfalar olmayacak; tek bitmap'te birleşecek.
- Çıktı genişliği ve yüksekliği kaynak bitmap ile aynı olacak; son satır dahil hiçbir satır kırpılmayacak.
- `flip * 100 + destination` kodları sitenin DOM sırası ve `scaleX/scaleY` dönüşümleriyle eşleşecek.
- Yatay, dikey ve 180° çevirmeler izole edilmiş strip'in kendi koordinatlarında uygulanacak; başka strip'in alanına taşmayacak.
- Current `v4` isteğinde `FORCE_NETWORK` kullanılacak. Config/order/decode hatasında ham karışık görsel sessizce döndürülmeyecek.
- CI, browser, fixture veya sentetik piksel testi tek başına gerçek cihaz görsel kabulü değildir.

## Uygulama

- `MangaTRReaderContract`: current config için kaynak eleman başına tek `v4-{codes}` Page üretir. `v3-{destination}-{codes}` yalnız eski açık reader oturumları için okunur.
- `MangaTRImagePipeline`: bütün strip'leri kırpar, her birini yerel `Matrix` ile çevirir, destination sırasına göre tek bitmap'e yerleştirir. Tek sayılı kaynak yüksekliklerinde kalan satır da korunur.
- `MangaTRLiveTest` ve browser aracı: 104/52 ayrı-slice beklentisi kaldırıldı; 52/26 tam sayfa ve kaynakla aynı boyut doğrulanıyor.
- Browser aracındaki küçük etiketli piksel testi sıra, yatay/dikey/180° flip ve tek sayılı yükseklikleri denetler. Bu yalnız algoritma testidir, cihaz kanıtı değildir.
- Workflow'ların branch referansları aktif `arena/720282f3-kitsugi-manga-plugins` dalına bağlandı.

## Kalan işler

- [ ] Kotlin unit/Robolectric testlerini ve release derlemesini CI'da geçir.
- [ ] Gerçek Chromium live fixture ile gate/config, DOM kodları ve 52/26 full-page sözleşmesini geçir.
- [ ] Testleri geçen `1.6.34` APK'sını ve katalogları yayınla; `1.6.33` dosyasının üzerine yazma.
- [ ] Kullanıcı cihazında Bölüm 1 ve 2'nin tam görsel, sıra ve yön sonucunu doğrula.

## Yayın geçmişi notu

`1.6.32` kullanıcı cihazında gate/config hatası verdi. `1.6.33` gate/config'i açtı fakat iki yarımı ayrı sayfa verdi. Dağıtım veya derleme başarısı ürün başarısı olarak sunulmayacak.
