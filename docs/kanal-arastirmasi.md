# Kanal araştırması — v5 Faz 3

> Plan §3'ün ön araştırması. **Bu belgede kod yok** — Faz 3'ün kapsam çiti buydu.
> 2026-08-07, web araması ile derlendi (kaynaklar her tablonun altında).

## Nasıl okunur

Her satırın karşısındaki değer, resmi geliştirici dokümantasyonundan doğrulandı
(URL'ler tablo altında). Doğrulanamayan / dokümantasyonda bulunamayan alanlar
**"BİLİNMİYOR — spike'ta doğrulanacak"** olarak işaretlendi; Plan §4.2 madde 1 zaten
bunun için var: spike, tahmini gözleme çevirir. Bu belge o gözlemi taklit etmeye
çalışmıyor.

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
| Kimlik bilgisi almak aktif satıcı hesabı gerektiriyor mu? | **Hayır.** Shopify Partners hesabı ücretsiz, sınırsız süreli **development store** ile gerçek bir satıcı olmadan geliştirme yapılabiliyor — araştırılan kanallar arasında en düşük sürtünme |
| Test/sandbox ortamı var mı? | **Evet**, yukarıdaki development store |
| Webhook var mı? | **Evet**, geniş olay kümesi |
| İstemci idempotency anahtarı kabul ediyor mu? | **Evet** — `@idempotent` direktifini destekleyen mutation'larda (örn. envanter güncellemeleri) birinci sınıf `idempotencyKey` parametresi var |
| Ürünler SKU ile mi barkod ile mi anahtarlanıyor? | **SKU** (varyantın kendi `sku` alanı) — ama asıl kalıcı kimlik Shopify'ın kendi GID'i; Trendyol/Hepsiburada'daki "kendi id'miz + bizim sku" ikiliğine benziyor |
| Rate limit (req/dk) kaç? | **req/dk değil, maliyet-bazlı**: GraphQL sorgu başına puan (plan kademesine göre saniyede 100–2000 puan). Toplu işlemler (bulk operations) bu limitin dışında, asenkron çalışıyor, 2026-01+ sürümde mağaza başına eşzamanlı 5 toplu mutation'a kadar |
| Merchant of record kim? Parayı kim iade ediyor? | **Biz (satıcı).** Shopify bir pazaryeri değil, "kendi mağazan" platformu — ödeme satıcının kendi Shopify Payments/üçüncü parti ağ geçidinden geçiyor, iade API üzerinden satıcı tarafından yapılıyor. REFUND_BY_US = **var** |
| Geri kargo etiketini kim üretiyor? | Satıcı/uygulama, Shopify'ın fulfillment/shipping label API'leri üzerinden (kargo firmasına bağlı) — SHIPMENT_CREATE = muhtemelen var |
| Toplu stok/fiyat güncelleme var mı, kaçlık? | **Evet** — asenkron "bulk operations" (mutation) işi; senkron tekil mutation'lardan farklı bir model, iş bitince sonucu ayrıca çekiyorsunuz |
| İmza şeması (başlık adı, algoritma, gövde kodlaması) | **`X-Shopify-Hmac-Sha256` başlığı, HMAC-SHA256** — Shopier ile birlikte SDK'nın varsayımına uyan iki kanaldan biri |
| Komisyon/kesinti kırılımı sipariş yanıtında var mı? | **Kavramsal olarak yok** — pazaryeri komisyonu yok (yalnızca ödeme işlemci ücreti, o da genelde ayrı bir mutabakat raporunda) |

**Önemli şekil farkı:** Shopify GraphQL-zorunlu (REST Admin API Ekim 2024'ten beri
legacy, Nisan 2025'ten sonra yeni genel uygulamalar için tamamen kapalı) ve toplu
güncellemeler **asenkron iş** modeliyle çalışıyor — bizim `PlatformConnector.updateStock`
imzasının varsaydığı "listeyi ver, sonuç listesini senkron al" şekli burada birebir
uymuyor, bir polling/job-sonucu adaptasyonu gerektirir. Ayrıca Shopify bir pazaryeri
değil tekil-mağaza platformu — hub'ın "birden fazla kanalı tek merkezden yönetme"
değer önerisi için ilginç ama farklı bir kanal tipi (Trendyol/Hepsiburada/PTT AVM
"pazaryeri" iken Shopify "kendi mağazan").

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
| Shopify | **En düşük** (ücretsiz dev store) | ✅ (asenkron iş modeli) | ✅ **en iyi** | Pazaryeri değil; GraphQL + asenkron toplu iş, SDK şekline en az uyan |

**Öneri: Trendyol.**

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

## Sıradaki adım

Faz 4: `faz-v5-4-trendyol-connector` dalında, spike ile başlayarak (Plan §4.2 sırası
bozulmadan) Trendyol connector'ı yazılır.
