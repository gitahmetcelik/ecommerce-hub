# Kanal araştırması — v5 Faz 3

> Plan §3'ün ön araştırması. **Bu belgede kod yok** — Faz 3'ün kapsam çiti buydu.
> 2026-08-07, web araması ile derlendi (kaynaklar her tablonun altında).

## Nasıl okunur

Her satırın karşısındaki değer, resmi geliştirici dokümantasyonundan doğrulandı
(URL'ler tablo altında). Doğrulanamayan / dokümantasyonda bulunamayan alanlar
**"BİLİNMİYOR — spike'ta doğrulanacak"** olarak işaretlendi; Plan §4.2 madde 1 zaten
bunun için var: spike, tahmini gözleme çevirir. Bu belge o gözlemi taklit etmeye
çalışmıyor.

> **Revizyon (2026-08-07, Faz 4 başlarken):** Aşağıdaki §3.4 kararı Trendyol'u
> önermişti — bu hâlâ doğru teknik değerlendirme. Ama Trendyol'un API kimlik
> bilgileri gerçek bir Seller ID'ye bağlı (bkz. §3.4 sonrası not); ne paylaşımlı
> test hesabı ne de entegratör-firma yolu, arkasında bir satıcı hesabı olmadan
> çalışmıyor. Bu projeyi yürüten kullanıcı aktif bir satıcı değil ve satıcı kaydı
> açmak ya da bir satıcı ortağı bulmak istemiyor. Bu saf bir erişim kısıtı —
> Trendyol'un teknik uygunluğunu geçersiz kılmıyor. **Faz 4 bu yüzden Shopify ile
> ilerliyor**: ücretsiz Partner hesabı, şirket/satıcı kaydı gerektirmeyen sınırsız
> süreli development store. §3.4'teki tablo Shopify'ı zaten ikinci sırada
> değerlendirmişti (GraphQL + asenkron toplu iş modeli, pazaryeri değil tekil-mağaza
> şekli) — o riskler hâlâ geçerli ve Faz 4'ün spike'ı bunları doğrulayacak.
> Trendyol araştırması gelecekte bir satıcı ortağı bulunursa geçerliliğini korur;
> silinmedi.

---

## Trendyol

| Soru | Bulgu |
|---|---|
| Satıcı API'si var mı, dokümantasyonu herkese açık mı? | **Evet.** `developers.trendyol.com` — herkese açık, v2/v3 sürümleri var (v1 10 Ağustos 2026'da kapanıyor) |
| Kimlik bilgisi almak aktif satıcı hesabı gerektiriyor mu? | **Kısmen.** Test ortamı için iki yol var: çağrı merkezinden **paylaşılan** bir test hesabı, veya Satıcı Merkezi destek üzerinden e-posta/telefon/vergi kimliği ile **özel** bir test hesabı. Aktif satış yapan bir mağaza şart değil gibi görünüyor ama vergi kimliği isteniyor |
| Test/sandbox ortamı var mı? | **Evet** — `stageapigw.trendyol.com`, IP whitelist gerektiriyor |
| Webhook var mı? | **Evet** — sipariş durumu değişikliklerinin tamamı (CREATED, PICKING, SHIPPED, DELIVERED, ...) webhook ile geliyor |
| İstemci idempotency anahtarı kabul ediyor mu? | BİLİNMİYOR — spike'ta doğrulanacak |
| Ürünler SKU ile mi barkod ile mi anahtarlanıyor? | **Barkod.** Stok/fiyat güncelleme isteği `barcode` alanını anahtar olarak kullanıyor — Faz 1'in `ChannelItemRef.channelVariantId()` yolu bu kanalda gerçek |
| Rate limit (req/dk) kaç? | Uç noktaya ve satıcı kademesine göre değişiyor: stok/fiyat yazma 350–2000 req/dk, sipariş paketi çekme 30–100 req/dk, iade onayı yalnızca 5 req/dk |
| Merchant of record kim? Parayı kim iade ediyor? | BİLİNMİYOR — dokümantasyonda satıcının yalnızca `approveClaimLineItems` / `createClaimIssue` ile onay/red verdiği görülüyor, doğrudan bir "iade et" çağrısı yok. Trendyol'un kendisi merchant of record olabilir (REFUND_BY_US **yok** ihtimali yüksek) — spike'ta doğrulanacak |
| Geri kargo etiketini kim üretiyor? | BİLİNMİYOR — spike'ta doğrulanacak |
| Toplu stok/fiyat güncelleme var mı, kaçlık? | **Evet, 1000 SKU/çağrı** (`updatePriceAndInventory`) |
| İmza şeması (başlık adı, algoritma, gövde kodlaması) | **Kriptografik imza yok.** Webhook `x-api-key` başlığı veya Basic Auth ile doğrulanıyor — bizim SDK'daki `verifySignature` HMAC varsayımı bu kanalda uymuyor, paylaşılan sır/başlık kontrolüne indirgenmesi gerekecek |
| Komisyon/kesinti kırılımı sipariş yanıtında var mı? | BİLİNMİYOR — spike'ta doğrulanacak |

Kaynaklar: [developers.trendyol.com](https://developers.trendyol.com/en) ·
[Servis limitleri](https://developers.trendyol.com/docs/1-servis-limitleri.md) ·
[Test ortamı](https://developers.trendyol.com/docs/3-canlı-test-ortam-bilgileri.md) ·
[Webhook modeli](https://developers.trendyol.com/docs/webhook-model.md) ·
[Stok/fiyat güncelleme](https://developers.trendyol.com/docs/stok-ve-fiyat-güncelleme-updatepriceandinventory.md) ·
[İade süreci](https://developers.trendyol.com/docs/i̇ade-süreci-akışı.md)

---

## Hepsiburada

| Soru | Bulgu |
|---|---|
| Satıcı API'si var mı, dokümantasyonu herkese açık mı? | **Evet.** `developers.hepsiburada.com` — herkese açık |
| Kimlik bilgisi almak aktif satıcı hesabı gerektiriyor mu? | **Evet.** Akış: satıcı paneli hesabı oluştur → onay bekle → API kullanıcı adı/şifre üret → merchant ID ata. Sandbox'a girmeden önce bile bu onay adımı var — araştırılan kanallar arasında en yüksek giriş sürtünmesi |
| Test/sandbox ortamı var mı? | **Evet** — `*-sit.hepsiburada.com` (SIT = system integration test), hem ürün hem sipariş (webhook dahil) için ayrı test ortamı var |
| Webhook var mı? | **Evet** — sipariş olayları için push modeli (siz bir BaseURL veriyorsunuz, Hepsiburada `/orders` yoluna POST atıyor). Önce test ortamında doğrulanıyor, sonra canlıya alınıyor |
| İstemci idempotency anahtarı kabul ediyor mu? | BİLİNMİYOR — spike'ta doğrulanacak (webhook alıcı tarafı için "idempotent mantık öneriliyor" deniyor ama bu istemci→Hepsiburada yönü değil) |
| Ürünler SKU ile mi barkod ile mi anahtarlanıyor? | **SKU** (`merchantSku` — bizim kendi SKU'muz) hem de kendi `hepsiburadaSku`'ları var; ikisiyle de sorgulanabiliyor |
| Rate limit (req/dk) kaç? | Listing güncelleme: eş zamanlı **5 POST** ile sınırlı (istek başına SKU sayısını artırmak öneriliyor), tek çağrıda **4000 SKU'ya kadar** |
| Merchant of record kim? Parayı kim iade ediyor? | BİLİNMİYOR — spike'ta doğrulanacak |
| Geri kargo etiketini kim üretiyor? | BİLİNMİYOR — spike'ta doğrulanacak |
| Toplu stok/fiyat güncelleme var mı, kaçlık? | **Evet, 4000 SKU/çağrı** — araştırılan kanallar arasında en yüksek toplu limit |
| İmza şeması (başlık adı, algoritma, gövde kodlaması) | **HTTP Basic Auth** (kullanıcı adı/şifre header'da) — Trendyol gibi kriptografik imza değil |
| Komisyon/kesinti kırılımı sipariş yanıtında var mı? | BİLİNMİYOR — spike'ta doğrulanacak |

Kaynaklar: [Developer Portal](https://developers.hepsiburada.com/hepsiburada/docs/getting-started) ·
[Listing tekil güncelleme](https://developers.hepsiburada.com/hepsiburada/reference/listing-tekil-fiyatstok-g%C3%BCncelleme) ·
[Webhook önemli bilgiler](https://developers.hepsiburada.com/hepsiburada/reference/webhook-%C3%B6nemli-bilgiler) ·
[Sipariş webhook modeli](https://developers.hepsiburada.com/hepsiburada/reference/sipari%C5%9F-webhook-modeli)

---

## PTT AVM

| Soru | Bulgu |
|---|---|
| Satıcı API'si var mı, dokümantasyonu herkese açık mı? | **Hayır — bu kanal burada elenir.** `developers.pttavm.com` gibi herkese açık bir portal bulunamadı; erişilebilen tek bilgi üçüncü parti entegrasyon firmalarının (Paraşüt, Payer, Dokuz Yazılım, ...) blog yazıları |
| Kimlik bilgisi almak aktif satıcı hesabı gerektiriyor mu? | **Evet.** Kayıtlı bir PTT AVM mağaza hesabından destek e-postasına yazılıyor, karşılığında API kullanıcı adı/şifre/mağaza ID e-posta ile geliyor — self-servis değil |
| Test/sandbox ortamı var mı? | Bulunamadı — hiçbir kaynakta sandbox/test ortamından bahsedilmiyor |
| Diğer sorular | Kamuya açık birincil kaynak olmadığı için doldurulamadı |

**Karar: PTT AVM v1 kapsamı dışı.** Plan §3.4'ün üç kriterinden ikisini (dokümantasyon
herkese açık, düşük riskli test yolu) açıkça karşılamıyor. Bu, PTT AVM'nin kötü bir
kanal olduğu anlamına gelmez — yalnızca *ilk* connector için doğru aday olmadığı
anlamına gelir; dokümantasyon istek yoluyla (destek e-postası) sonradan tekrar
değerlendirilebilir.

Kaynaklar: [Payer — PttAvm entegrasyon](https://docs.payer.com.tr/payer-entegrasyon/entegrasyonlar/pazaryeri-entegrasyonlari/pttavm-entegrasyon-islemleri) ·
[Paraşüt — PttAVM API bilgileri](https://www.parasut.com/kullanim-kilavuzu/parasut-e-ticaret-pttavm-entegrasyonu)

---

## Shopier

| Soru | Bulgu |
|---|---|
| Satıcı API'si var mı, dokümantasyonu herkese açık mı? | **Evet.** `developer.shopier.com` — modern, herkese açık, OpenAPI/llms.txt indeksli. (Dikkat: `shopier.github.io`'daki eski SDK yalnızca ödeme/checkout'tur, mağaza/sipariş API'si değil — bunu ayrı bulmak gerekti) |
| Kimlik bilgisi almak aktif satıcı hesabı gerektiriyor mu? | Kısmen — "önce satıcı hesabı aç, sonra geliştirici modunu aç" deniyor; ücretli/aktif bir mağaza şart mı, ücretsiz kayıt yeterli mi BİLİNMİYOR — spike'ta doğrulanacak |
| Test/sandbox ortamı var mı? | Dokümantasyon indeksinde bulunamadı — BİLİNMİYOR, spike'ta doğrulanacak |
| Webhook var mı? | **Evet** — `order.created`, `order.addressUpdated`, `order.fulfilled` olayları |
| İstemci idempotency anahtarı kabul ediyor mu? | Dokümantasyon indeksinde ayrı bir idempotency sayfası yok — BİLİNMİYOR |
| Ürünler SKU ile mi barkod ile mi anahtarlanıyor? | BİLİNMİYOR — Products kaynağı doğrulandı ama alan adları (sku/barcode) görülemedi |
| Rate limit (req/dk) kaç? | **200 req/dk**, uygulama-kullanıcı çifti başına, 60 saniyelik kayan pencere |
| Merchant of record kim? Parayı kim iade ediyor? | Muhtemelen **biz** — ayrı bir `POST /refunds` uç noktası var (Trendyol/Hepsiburada'da böyle bir uç nokta görülmedi). REFUND_BY_US ihtimali yüksek ama spike'ta doğrulanmalı |
| Geri kargo etiketini kim üretiyor? | BİLİNMİYOR — spike'ta doğrulanacak |
| Toplu stok/fiyat güncelleme var mı, kaçlık? | **Dokümantasyon indeksinde bulunamadı.** Bu, aday kanallar arasında en büyük açık risk — Plan §3.4 kriter 2'yi karşılamayabilir. Spike'ın ilk sorusu bu olmalı |
| İmza şeması (başlık adı, algoritma, gövde kodlaması) | **`Shopier-Signature` başlığı, HMAC-SHA256** — araştırılan kanallar arasında SDK'nın `verifySignature` varsayımına en uygun (tek) şema |
| Komisyon/kesinti kırılımı sipariş yanıtında var mı? | BİLİNMİYOR — muhtemelen yok (Shopier bir "kendi mağazan" platformu, pazaryeri komisyonu kavramı Trendyol/Hepsiburada'daki gibi olmayabilir) |

Kaynaklar: [developer.shopier.com](https://developer.shopier.com/) ·
[Rate limits](https://developer.shopier.com/reference/rate-limits.md) ·
[Events/headers/payloads](https://developer.shopier.com/reference/events-headers-payloads.md) ·
[help.shopier.com — API nedir](https://help.shopier.com/help/shopier-api-nedir)

---

## Shopify

| Soru | Bulgu |
|---|---|
| Satıcı API'si var mı, dokümantasyonu herkese açık mı? | **Evet — araştırılan kanallar arasında en iyi dokümantasyon.** `shopify.dev`, sürümlü, örnekli, endüstri standardı |
| Kimlik bilgisi almak aktif satıcı hesabı gerektiriyor mu? | **Hayır — spike ile doğrulandı.** Partners hesabı (yalnızca e-posta) → development store → mağaza admin'inde "Develop apps" (legacy custom app, 2026-01-01 sonrası normal satıcılara kapalı ama Partner'a bağlı, devredilmemiş dev store'larda hâlâ açık) → statik Admin API access token. Gerçek satıcı, şirket, vergi kimliği yok |
| Test/sandbox ortamı var mı? | **Evet, gerçekten kullanıldı** — `paydos-9an1reso.myshopify.com`, örnek katalog verisiyle (Snowboard/Gift Card ürünleri) önceden dolu geliyor |
| Webhook var mı? | **Evet**, geniş olay kümesi (spike'ta test edilmedi — sadece Admin API doğrulandı) |
| İstemci idempotency anahtarı kabul ediyor mu? | **Karışık — spike'ta düzeltildi.** Faz 3'ün "Evet" bulgusu yanlış genellemeydi: stok toplu güncelleme mutation'ı (`inventorySetQuantities`, 2025-10) **`idempotencyKey` alanı kabul etmiyor** (`Field is not defined` hatası) — ama zaten kabul etmesine gerek yok, zira STOCK_PUSH doğası gereği idempotent (aynı miktarı tekrar yazmak zararsız). `fulfillmentCreate`/`refundCreate` gibi asıl CallIntentRef taşıyan çağrılarda idempotency anahtarı **spike'ta test edilmedi** — connector yazılırken doğrulanmalı |
| Ürünler SKU ile mi barkod ile mi anahtarlanıyor? | **SKU — spike'ta doğrulandı.** `ProductVariant.sku` (bizim alanımız, null olabilir) + `ProductVariant.barcode` (null olabilir) + Shopify'ın kendi `InventoryItem` GID'i (örn. `gid://shopify/InventoryItem/506572...`) — bu GID gerçek korelasyon anahtarı, tıpkı Trendyol/Hepsiburada'daki "kendi id + bizim sku" ikiliği gibi |
| Rate limit (req/dk) kaç? | **Ölçüldü (v4 planlama §5'e yazıldı):** bağlantı başına 2000 puanlık kova, saniyede 100 puan dolum. Basit sorgu ~2-6 puan, toplu stok mutation'ı ~11 puan, sipariş/iade mutation'ları ~10-20 puan — kabaca 400-600 mutation/dk sürdürülebilir |
| Merchant of record kim? Parayı kim iade ediyor? | **Biz — spike'ta doğrulandı.** Gerçek bir siparişte `refundCreate` mutation'ı doğrudan çağrıldı ve **başarıyla iade kaydı oluşturdu**, ek bir onay/aracı adımı yok. REFUND_BY_US = **var, kesin** |
| Geri kargo etiketini kim üretiyor? | Kısmen test edildi — `fulfillmentOrders` sorgusu `orderCreate` ile (canlı checkout değil, API'den doğrudan) yaratılan test siparişinde erişilemedi; bu muhtemelen `orderCreate`'in "geçmiş sipariş aktarımı" amaçlı olup normal fulfillment order zinciri kurmamasından kaynaklanıyor, gerçek/webhook kökenli bir siparişte tekrar denenmeli. SHIPMENT_CREATE = muhtemelen var, connector yazılırken kesinleştirilecek |
| Toplu stok/fiyat güncelleme var mı, kaçlık? | **Evet, ve Faz 3'ün tahmininden daha iyi — spike'ta düzeltildi.** Faz 3 "asenkron iş modeli" diye tahmin etmişti; gerçekte `inventorySetQuantities` mutation'ı **senkron**, tek çağrıda birden fazla `{inventoryItemId, locationId, quantity}` kabul ediyor — `PlatformConnector.updateStock`'un "liste ver, liste al" şekliyle doğrudan uyumlu. (Ayrıca gerçekten büyük veri işleri için ayrı, asenkron "bulk operations" mekanizması da var ama küçük/orta toplu güncellemeler için gerekli değil.) Fiyat için benzer bir senkron toplu mutation (`productVariantsBulkUpdate`) şemada var ama spike'ta çağrılmadı — connector yazılırken doğrulanmalı |
| İmza şeması (başlık adı, algoritma, gövde kodlaması) | **`X-Shopify-Hmac-Sha256` başlığı, HMAC-SHA256** (dokümantasyondan — spike webhook test etmedi) |
| Komisyon/kesinti kırılımı sipariş yanıtında var mı? | **Kavramsal olarak yok** — pazaryeri komisyonu yok (yalnızca ödeme işlemci ücreti, o da genelde ayrı bir mutabakat raporunda) |
| Envanter için ekstra gereksinim (spike'ta bulundu, Faz 3'te yoktu) | Envanter **konum-bazlı** (multi-warehouse model) — her `inventoryItem`'ın konum başına ayrı miktarı var. `location.id`'yi bir varyanttan gelen `inventoryLevels` üzerinden almak ek scope gerektirmiyor, ama `location.name` gibi konum detayları için ayrı `read_locations` scope'u gerekiyor. Bağlantı kurulumunda tek-lokasyonlu bir mağaza için sorun değil, `read_locations` yine de connection health/tanı ekranı için eklenmeli |

**Şekil düzeltmesi (Faz 3 → Faz 4 spike):** Shopify GraphQL-zorunlu olması doğruydu
(REST Admin API Ekim 2024'ten beri legacy) ama "toplu güncelleme asenkron" tahmini
**yanlıştı** — `inventorySetQuantities` senkron ve tam olarak `PlatformConnector`'ın
varsaydığı şekilde çalışıyor. Bu, Shopify'ı SDK şekline Trendyol/Hepsiburada kadar
(belki onlardan da) uyumlu yapıyor. Pazaryeri değil tekil-mağaza platformu olması
hâlâ geçerli bir fark (komisyon kavramı yok, REFUND_BY_US spike ile kesinleşti).

Kaynaklar: [shopify.dev — API limits](https://shopify.dev/docs/api/usage/limits) ·
[REST Admin API rate limits](https://shopify.dev/docs/api/admin-rest/usage/rate-limits) ·
[Implementing idempotency](https://shopify.dev/docs/api/usage/implementing-idempotency) ·
[Shopify Engineering — idempotency](https://shopify.engineering/building-resilient-graphql-apis-using-idempotency)

---

## §3.4 Karar

Plan'ın kuralı: sandbox/düşük riskli test yolu + toplu stok güncelleme + herkese açık
dokümantasyon şartlarının **üçünü de** karşılayan **en basit** kanal.

| Kanal | Sandbox sürtünmesi | Toplu güncelleme | Dokümantasyon | Not |
|---|---|---|---|---|
| Trendyol | Düşük–orta (paylaşımlı test hesabı mümkün) | ✅ 1000/çağrı | ✅ açık | Barkod anahtarlı, imza yok (paylaşılan sır) |
| Hepsiburada | **Orta–yüksek** (satıcı paneli onayı şart) | ✅ 4000/çağrı | ✅ açık | SKU anahtarlı, Basic Auth |
| PTT AVM | Bilinmiyor / muhtemelen yüksek | Bilinmiyor | ❌ **kapalı** | **Elendi** |
| Shopier | Bilinmiyor | ❓ **doğrulanamadı** | ✅ açık | En temiz imza şeması ama toplu güncelleme belirsiz |
| Shopify | **En düşük** (ücretsiz dev store) | ✅ (asenkron iş modeli — **düzeltme: aslında senkron, bkz. Faz 4 spike sonuçları altta**) | ✅ **en iyi** | Pazaryeri değil; GraphQL, SDK şekline uyumu tahmin edilenden iyi çıktı |

**Öneri (bu araştırmanın kendi mantığıyla): Trendyol.**
**Fiilen uygulanan (bkz. yukarıdaki 2026-08-07 revizyonu): Shopify** — Trendyol'un
kimlik bilgisi edinme yolu bir satıcı hesabı gerektiriyor ve bu projede o yok. Aşağıdaki
gerekçe Trendyol için hâlâ doğru; sadece erişim kısıtı yüzünden ikinci sıradaki
Shopify'a geçildi.

Gerekçe — "en basit" iki eksende okunuyor ve ikisi de Trendyol'u işaret ediyor:

1. **Sandbox erişimi gerçekten en az sürtünmeli olan iki aday Trendyol ve Shopify.**
   Shopify'ın toplu güncellemesi asenkron bir iş modeli (senkron `updateStock` çağrısı
   varsayan `PlatformConnector` arayüzüyle en az uyumlu) ve pazaryeri değil — hub'ın
   "birden fazla pazaryerini merkezden yönet" değer önerisini ilk connector'da
   sınamıyor. Trendyol senkron, 1000'lik toplu REST çağrısıyla SDK'nın bugünkü
   şekline birebir uyuyor.
2. **Trendyol, Türkiye'nin en büyük pazaryeri** — bir Türk satıcıya satılacak SaaS
   için ilk gerçek entegrasyonun ticari değeri de en yüksek burada.
3. Hepsiburada güçlü bir ikinci seçenek (4000'lik toplu limit, açık webhook) ama
   sandbox'a ulaşmadan önce satıcı paneli onayı istemesi Trendyol'a göre daha
   yüksek bir giriş engeli.

**Spike'ın (Plan §4.2 madde 1) doğrulaması gereken açık sorular, öncelik sırasıyla:**
1. Refund merchant-of-record — biz mi ödüyoruz, Trendyol mu?
2. Geri kargo etiketini kim üretiyor?
3. İstemci idempotency anahtarı kabul ediliyor mu (yoksa her SHIPMENT_CREATE/REFUND
   çağrısı `queryCallStatus` telafisine mi bağımlı)?
4. Sipariş yanıtında komisyon kırılımı var mı (Plan §13: sonradan geriye dönük
   doldurulamaz — varsa şimdi yakalanmalı)?
5. Webhook'un paylaşılan-sır doğrulaması (`x-api-key`/Basic Auth) SDK'nın
   `verifySignature` arayüzüne nasıl oturacak — HMAC varsayımı gevşetilmeli mi?

---

## §3.5 Instagram — ayrı karar

Plan'ın kendi önerisi **(b) `MANUAL` kanal tipi** ile aynı sonuca varıyoruz — bu
araştırma yeni bir bulgu eklemedi, çünkü Instagram'ın checkout'u yok ve bu bir API
araştırması sorusu değil, bir ürün kararı:

- Instagram'da satış DM üzerinden yürüyor; ne `FETCH_ORDERS` ne `STOCK_PUSH` API'si
  var — araştırılacak bir entegrasyon yok.
- `MANUAL` kanal tipi, operatörün siparişi elle girdiği, `Capability` setinin neredeyse
  boş olduğu bir kanal olarak inşa edilir. Bu, yetenek matrisinin şimdiye kadarki en
  sert testidir: hiçbir yeteneği olmayan bir kanalın stok düşüşü ve iade akışı yine de
  çalışmalı.
- SaaS olarak satıldığında "kendi DM'imden gelen siparişi buraya da gireyim" ilk
  istenecek şeylerden biri olacağından, bu kapsam dışı bırakılmıyor — ama gerçek bir
  connector değil, `PlatformConnector`'ın yetenek-boş bir profili (Faz 4'ün parçası
  değil, ayrı, küçük bir iş).

---

## §4 spike sonuçları (2026-08-07, Plan §4.2 madde 1)

Gerçek bir Shopify Partners dev store'una (`paydos-9an1reso.myshopify.com`) karşı
Admin GraphQL API (sürüm 2025-10) elle çağrıldı. Yukarıdaki Shopify tablosundaki
"spike'ta doğrulandı" satırları bu oturumun çıktısı. Özet — tahminden gözleme
dönüşenler:

- **Rate limit ölçüldü**, v4 planlama §5'in boş satırına yazıldı: 2000 puanlık kova,
  saniyede 100 puan dolum.
- **Toplu stok güncelleme senkron** (`inventorySetQuantities`, dizi halinde
  `{inventoryItemId, locationId, quantity}` kabul ediyor) — Faz 3'ün "asenkron"
  tahmini yanlıştı, düzeltildi.
- **REFUND_BY_US kesinleşti**: `refundCreate` doğrudan çağrıldı ve iade kaydı
  oluşturdu.
- **İdempotency anahtarı stok mutation'ında yok** (zaten gerekmiyor, STOCK_PUSH
  idempotent-by-value); asıl önemli olan `fulfillmentCreate`/`refundCreate`'te
  test edilmedi — connector yazımı sırasında doğrulanmalı.
- **Envanter konum-bazlı** — Faz 3'te hiç geçmeyen bir gereksinim; `read_locations`
  scope'u connector'ın kimlik bilgisi kurulum akışına eklenmeli.
- **Kapatılamayan tek soru:** geri kargo etiketi sahipliği — `fulfillmentOrders`
  sorgusu `orderCreate` ile yaratılan test siparişinde çalışmadı (muhtemelen o
  mutation'ın "geçmiş sipariş aktarımı" doğası yüzünden, gerçek scope eksikliği
  değil). Gerçek/webhook kökenli bir sipariş üzerinde connector yazılırken
  tekrar denenecek.

---

## Sıradaki adım

Faz 4: `faz-v5-4-shopify-connector` dalında, `connector-shopify` modülü yazılır
(Plan §4.2 madde 4) — spike bitti, yukarıdaki gözlemler yetenek matrisine ve
connector'ın kendisine aktarılacak.
