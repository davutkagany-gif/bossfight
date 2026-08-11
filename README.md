# BossFight (Paper 1.21.x)

Plaka tetikleyicili arena boss fight eklentisi.

## Ne yapar
- 32 chunkluk bölge içinde **%15** şansla bir plaka (ağır basınç plakası) spawn olur.
- Oyuncu plakaya basınca **arena koordinatına** ışınlanır ve dövüş başlar.
- **3 wave**: sadece **iskelet ve zombi** spawn olur (creeper yok). Her wave temizlenince sonraki başlar.
- 3. wave sonrası **boss** (büyük zombi, 10.000 can) gelir.
- Boss iki yeteneğe sahiptir:
  - **En yakın oyuncunun arkasına ışınlanma** — cooldown **20 sn**.
  - **Ateş topu fırlatma** — cooldown **30 sn**.
- Boss ölünce oyuncu **plakaya bastığı yere** geri ışınlanır.
- Boss ölünce **%15 şansla Kadim Kılıç** düşer (netherite görünümlü, sahte parıltılı, gerçek enchant yok, **200 hasar**).
- Boss ölünce **%5 şansla Kadim Zırh** seti düşer. Tam set giyildiğinde alınan hasar %92 azalır — bu kılıca karşı hayatta kalınabilir.
- **Arena kırılamaz**: arena çevresinde (40 blok) blok kırma/koyma ve patlama hasarı engellenir (OP hariç).

## Derleme
Gereksinim: JDK 21 + Maven.

```bash
cd bossfight
mvn clean package
```

Çıktı: `target/BossFight.jar` — bunu sunucunun `plugins/` klasörüne at ve yeniden başlat.

## Kurulum sonrası
1. Bir yetkiliyle arenayı ayarla: arenanın ortasında dur ve `/bossfight setarena` yaz.
2. İstersen ayarları `plugins/BossFight/config.yml` içinden değiştir, sonra `/bossfight reload`.

## Komutlar (yetki: bossfight.admin, varsayılan OP)
- `/bossfight spawnplate` — bulunduğun yerin yakınına test plakası koyar.
- `/bossfight setarena` — arena koordinatını bulunduğun yere ayarlar.
- `/bossfight reload` — config'i yeniden yükler.

## Ayarlar (config.yml)
- `plate-spawn-chance` — plaka spawn şansı (%). Varsayılan 15.
- `plate-spawn-interval-seconds` — otomatik spawn deneme aralığı.
- `region-chunk-radius` — bölge yarıçapı (chunk). Varsayılan 32.
- `waves` — her wave'deki mob sayısı (varsayılan 6, 8, 10).
- `boss-drop-chance` — kılıç düşme şansı (%). Varsayılan 50.
- `boss-teleport-cooldown-seconds` — ışınlanma cooldown (varsayılan 20).
- `boss-fireball-cooldown-seconds` — ateş topu cooldown (varsayılan 30).
- `boss-health`, `boss-name`, `sword-name` — boss ve kılıç ayarları.

## Not
Plaka otomatik olarak her online oyuncu için belirli aralıklarla %15 şansla denenir.
Aynı anda en fazla `max-active-plates` (varsayılan 3) plaka bulunur.
