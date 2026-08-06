<div align="center">

# E-commerce Hub

**One place to receive orders from every marketplace you sell on, keep stock and order state
consistent across all of them, and push changes back out — without losing anything when a
channel's API misbehaves.**

*Sattığınız her pazaryerinden siparişleri tek yerde toplayan, stok ve sipariş durumunu
hepsi arasında tutarlı tutan ve değişiklikleri geri iten platform — bir kanalın API'si
kötü davrandığında hiçbir şey kaybetmeden.*

<br>

![Java](https://img.shields.io/badge/Java-21-e11f24?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6db33f?style=flat-square)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-336791?style=flat-square)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.13-ff6600?style=flat-square)
![Next.js](https://img.shields.io/badge/Next.js-16-000000?style=flat-square)
![Tests](https://img.shields.io/badge/tests-118%20passing-success?style=flat-square)

</div>

---

## Contents · İçindekiler

[The problem](#the-problem--problem) · [How it works](#how-it-works--nasıl-çalışır) ·
[Stack](#stack--kullanılan-teknolojiler) · [Architecture](#architecture--mimari) ·
[Design decisions](#design-decisions--tasarım-kararları) ·
[Running it](#running-it--çalıştırma) · [Testing](#testing--test) ·
[Repository layout](#repository-layout--dizin-yapısı)

---

## The problem · Problem

<table>
<tr><th width="50%">English</th><th width="50%">Türkçe</th></tr>
<tr valign="top"><td>

Selling on several marketplaces at once means the same physical stock is advertised in
several places that do not know about each other. Each channel has its own API, its own
idea of an order's lifecycle, and its own failure modes. None of them will tell you when
they were wrong.

The hard parts are not the happy paths. They are the ones where a webhook arrives twice,
or arrives out of order, or never arrives at all; where two customers buy the last unit
within the same second; where a shipping label request times out and you cannot tell
whether a label was created.

</td><td>

Aynı anda birden fazla pazaryerinde satış yapmak, aynı fiziksel stoğun birbirinden habersiz
birkaç yerde ilan edilmesi demektir. Her kanalın kendi API'si, siparişin yaşam döngüsüne dair
kendi anlayışı ve kendi hata biçimleri vardır. Hiçbiri size yanıldığı anı söylemez.

Zor kısım mutlu yollar değil. Webhook'un iki kez geldiği, sırasız geldiği ya da hiç gelmediği
durumlar; iki müşterinin aynı saniyede son birimi satın aldığı an; kargo etiketi isteğinin
zaman aşımına uğradığı ve etiketin oluşup oluşmadığını bilemediğiniz durumlar.

</td></tr>
</table>

**This platform is built around those cases, not around the happy path.**
*Bu platform mutlu yola göre değil, tam olarak o durumlara göre kuruldu.*

---

## How it works · Nasıl çalışır

An order's journey from a marketplace to your stock, end to end:
*Bir siparişin pazaryerinden stoğunuza uçtan uca yolculuğu:*

```
   ┌──────────────┐
   │  MARKETPLACE │  webhook (HMAC-signed) ─────┐
   │   Pazaryeri  │                             │
   └──────────────┘ ◄──── stock / price push ─┐ │
                                              │ │
   ══════════════════════════════════════════ │ │ ═══════════════════
                                              │ ▼
   ┌────────────────────────────────────────────────────────────┐
   │  1. INGEST          verify the signature over raw bytes,   │
   │     Giriş           store the event, ACK in < 200 ms       │
   └────────────────────────────────┬───────────────────────────┘
                                    │  same transaction (outbox)
   ┌────────────────────────────────▼───────────────────────────┐
   │  2. DISPATCHER      fair share between tenants,            │
   │     Serpme          FOR UPDATE SKIP LOCKED                 │
   └────────────────────────────────┬───────────────────────────┘
                                    │
   ┌────────────────────────────────▼───────────────────────────┐
   │  3. TASK ENGINE     retry · backoff · DLQ · scheduling     │
   │     Görev motoru    (gorev-motoru, a separate project)     │
   └────────────────────────────────┬───────────────────────────┘
                                    │
   ┌────────────────────────────────▼───────────────────────────┐
   │  4. DOMAIN          order state machine · stock ledger ·   │
   │     Alan mantığı    catalog matching · returns             │
   └────────────────────────────────┬───────────────────────────┘
                                    │
   ┌────────────────────────────────▼───────────────────────────┐
   │  5. PUSH COALESCING one row per (channel, variant):        │
   │     Biriktirme      50 changes ⇒ 1 API call                │
   └────────────────────────────────┬───────────────────────────┘
                                    │
   ┌────────────────────────────────▼───────────────────────────┐
   │  6. CALL INTENT     record the intent BEFORE the call,     │
   │     Niyet kaydı     so a crash never pays twice            │
   └────────────────────────────────┬───────────────────────────┘
                                    └────────────────────────────┘
```

Alongside that path, three things run continuously:
*Bu yolun yanında sürekli çalışan üç şey var:*

| | English | Türkçe |
|---|---|---|
| 🔄 **Reconcile** | Polls channels for what webhooks missed; reports drift and never silently corrects it | Webhook'ların kaçırdığını kanaldan çeker; sapmayı raporlar, asla sessizce düzeltmez |
| ⏱️ **Timers** | Reservation expiry, return approval deadlines (24 h reminder, 48 h escalation) | Rezervasyon süresi, iade onay süreleri (24 s hatırlatma, 48 s eskalasyon) |
| 🧹 **Retention** | Drops expired event partitions, honours individual erasure requests | Süresi dolan olay bölümlerini düşürür, bireysel silme taleplerini yerine getirir |

---

## Stack · Kullanılan teknolojiler

<table>
<tr><th>Layer · Katman</th><th>Choice · Seçim</th><th>Why · Neden</th></tr>

<tr valign="top"><td><b>Language</b><br><i>Dil</i></td><td>Java 21</td>
<td>Records, pattern matching and modern libraries, on a long-term support release.<br>
<i>Kayıtlar, desen eşleme ve modern kütüphaneler; uzun destekli sürüm.</i></td></tr>

<tr valign="top"><td><b>Framework</b><br><i>Çerçeve</i></td><td>Spring Boot 3.5</td>
<td>Transactions, JDBC, security and scheduling without assembling them by hand.<br>
<i>Transaction, JDBC, güvenlik ve zamanlama — elle birleştirmeden.</i></td></tr>

<tr valign="top"><td><b>Database</b><br><i>Veritabanı</i></td><td>PostgreSQL 16</td>
<td>Row-level security enforces tenant isolation <i>in the database</i>, not merely in application code. Also: partitioning, <code>SKIP LOCKED</code>, <code>jsonb</code>.<br>
<i>Satır seviyesi güvenlik kiracı izolasyonunu uygulama kodunda değil <b>veritabanında</b> zorlar. Ayrıca bölümleme, <code>SKIP LOCKED</code>, <code>jsonb</code>.</i></td></tr>

<tr valign="top"><td><b>Messaging</b><br><i>Mesajlaşma</i></td><td>RabbitMQ 3.13<br><sub>delayed-message plugin</sub></td>
<td>Priority queues for the task engine; the plugin backs delayed and scheduled tasks.<br>
<i>Görev motoru için öncelikli kuyruklar; eklenti gecikmeli ve zamanlanmış görevleri taşır.</i></td></tr>

<tr valign="top"><td><b>Task engine</b><br><i>Görev motoru</i></td><td><code>gorev-motoru</code></td>
<td>A companion project: retries, exponential backoff, dead-letter queue, scheduling, transactional outbox.<br>
<i>Kardeş proje: yeniden deneme, üstel geri çekilme, ölü mektup kutusu, zamanlama, transactional outbox.</i></td></tr>

<tr valign="top"><td><b>Migrations</b><br><i>Şema göçü</i></td><td>Flyway</td>
<td>Hub migrations start at <code>V1000</code> so the engine's own chain has room to grow.<br>
<i>Hub göçleri <code>V1000</code>'den başlar; motorun kendi zinciri büyüyebilsin diye.</i></td></tr>

<tr valign="top"><td><b>Auth</b><br><i>Kimlik</i></td><td>Spring Security + JWT<br><sub>BCrypt · jjwt</sub></td>
<td>Short-lived access tokens, rotating refresh tokens. The tenant travels as a signed claim.<br>
<i>Kısa ömürlü erişim, rotasyonlu yenileme token'ı. Kiracı imzalı claim olarak taşınır.</i></td></tr>

<tr valign="top"><td><b>Dashboard</b><br><i>Arayüz</i></td><td>Next.js 16 · React 19<br><sub>Tailwind 4 · TanStack Query</sub></td>
<td>Operator screens: orders, stock, returns, matching, channel health.<br>
<i>Operatör ekranları: sipariş, stok, iade, eşleştirme, kanal sağlığı.</i></td></tr>

<tr valign="top"><td><b>Testing</b><br><i>Test</i></td><td>JUnit 5 · Testcontainers<br><sub>ArchUnit · AssertJ</sub></td>
<td>Every test gets a real Postgres, a real broker and a real mock marketplace. No in-memory substitutes.<br>
<i>Her test gerçek Postgres, gerçek broker ve gerçek sahte pazaryeri alır. Bellek içi taklit yok.</i></td></tr>

</table>

---

## Architecture · Mimari

### Modules · Modüller

```
backend/
├── hub-domain        ── entities, state machines, business rules
│                        varlıklar, durum makineleri, iş kuralları
├── connector-sdk     ── the contract every marketplace connector implements
│                        her pazaryeri connector'ının uyduğu sözleşme
├── connector-mock    ── connector for the bundled mock marketplace
│                        paketteki sahte pazaryeri için connector
├── ingest            ── webhook intake, signature verification, outbox
│                        webhook girişi, imza doğrulama, outbox
├── dispatcher        ── outbox rows ➜ engine tasks, fair across tenants
│                        outbox satırları ➜ motor görevleri, kiracılar arası adil
├── task-handlers     ── thin engine handlers (no business logic)
│                        ince motor handler'ları (iş mantığı taşımaz)
└── app               ── Spring Boot application, REST API, schedulers
                         Spring Boot uygulaması, REST API, zamanlayıcılar

frontend/             ── Next.js operator dashboard · operatör arayüzü
mock-pazaryeri/       ── scenario-driven fake marketplace · senaryo tabanlı sahte pazaryeri
```

### Data model highlights · Veri modeli öne çıkanlar

| Table · Tablo | What it solves · Ne çözüyor |
|---|---|
| `channel_push` | One row per (channel, variant, type). A new value **updates** it — 50 changes coalesce into one API call.<br><i>Kanal-varyant-tip başına tek satır. Yeni değer satırı **günceller** — 50 değişiklik tek çağrıya iner.</i> |
| `channel_call_intent` | Written **before** any side-effecting call. A crash mid-flight asks the channel what happened instead of retrying.<br><i>Yan etkili çağrıdan **önce** yazılır. Uçuşta çökme, tekrar denemek yerine kanala ne olduğunu sorar.</i> |
| `stock_movement` | Append-only ledger. Every counter change has a matching row, so the stock table can be recomputed and verified.<br><i>Yalnızca ekleyen defter. Her sayaç değişikliğinin karşılığı vardır; stok tablosu yeniden hesaplanıp doğrulanabilir.</i> |
| `mapping_candidate` | Channel items that matched nothing. They are queued for a human, never guessed and never dropped.<br><i>Hiçbir şeyle eşleşmeyen kanal kalemleri. İnsana kuyruklanır; ne tahmin edilir ne düşürülür.</i> |
| `operator_queue` | Decisions no retry can make. Deliberately separate from the engine's dead-letter queue.<br><i>Hiçbir yeniden denemenin veremeyeceği kararlar. Motorun ölü mektup kutusundan bilinçli olarak ayrı.</i> |
| `raw_event` | Monthly partitions. Retention is a `DROP`, not a row-by-row delete.<br><i>Aylık bölümler. Süre dolumu satır satır silme değil, `DROP`.</i> |

---

## Design decisions · Tasarım kararları

<details open>
<summary><b>🔐 Tenant isolation lives in the database · Kiracı izolasyonu veritabanında</b></summary>

<br>

**EN** — Postgres row-level security is the enforcement, not application code. Three roles
split the work: one owns the tables and runs migrations, one serves requests and can only
ever see its own tenant, one runs cross-tenant background sweeps. The tenant is set
*transaction-locally*, so it can never leak into the next request through a pooled
connection. A gate test reads the table list from `pg_class` rather than a hardcoded list,
so a table added without a policy fails the build.

**TR** — Zorlayıcı Postgres satır seviyesi güvenliği, uygulama kodu değil. Üç rol işi böler:
biri tabloların sahibi ve göçleri çalıştırır, biri istekleri karşılar ve yalnızca kendi
kiracısını görebilir, biri kiracı üstü arka plan süpürmelerini yapar. Kiracı bağlamı
*transaction-yerel* yazılır; havuzdan gelen bir bağlantı üzerinden sonraki isteğe sızamaz.
Kapı testi tablo listesini sabit listeden değil `pg_class`'tan okur — politikasız eklenen
bir tablo derlemeyi düşürür.

</details>

<details open>
<summary><b>📦 Every webhook carries the whole order · Her webhook siparişin tamamını taşır</b></summary>

<br>

**EN** — Events carry a full order snapshot, not a delta. This is what lets an out-of-order
delivery — a payment event arriving before the order-created event — resolve correctly
without a reordering buffer. Ordering the source never had cannot be reconstructed
downstream; the state machine absorbs it instead.

**TR** — Olaylar delta değil, siparişin tam görüntüsünü taşır. Sırasız teslimatın — ödeme
olayının sipariş olayından önce gelmesinin — yeniden sıralama tamponu olmadan doğru
çözülmesini sağlayan şey budur. Kaynakta olmayan bir sıra aşağı akışta kurulamaz; bunun
yerine durum makinesi onu soğurur.

</details>

<details open>
<summary><b>💸 Nothing side-effecting is ever simply retried · Yan etkili hiçbir şey öylece tekrarlanmaz</b></summary>

<br>

**EN** — The task engine is at-least-once. Creating a shipping label or paying a refund is
not naturally idempotent, so both go through a persisted intent: the intent commits
*before* the call leaves. A crash between the call and its response leaves that intent in
flight, and recovery **asks the channel what happened** rather than repeating the action.
For a refund, that difference is a second payment to a customer.

**TR** — Görev motoru en-az-bir-kez çalışır. Kargo etiketi oluşturmak ya da para iadesi
yapmak doğası gereği idempotent değildir; ikisi de kalıcı bir niyet kaydından geçer ve niyet
çağrı gitmeden **önce** commit edilir. Çağrı ile yanıt arasında çökme, o niyeti uçuşta
bırakır ve kurtarma eylemi tekrarlamak yerine **kanala ne olduğunu sorar**. Para iadesinde
bu farkın karşılığı, müşteriye ikinci bir ödemedir.

</details>

<details open>
<summary><b>🚫 Drift is reported, never auto-corrected · Sapma raporlanır, asla otomatik düzeltilmez</b></summary>

<br>

**EN** — When a channel's stock disagrees with ours, the difference is recorded and left
alone. A system that silently rewrites stock to match whatever a channel last said cannot
tell a real drift from a channel bug — and the correction is precisely the action a human
wants to see before it happens. The same applies to the 48-hour return deadline: it
escalates to a person, it never auto-rejects.

**TR** — Kanalın stoğu bizimkiyle uyuşmadığında fark kaydedilir ve dokunulmaz. Kanalın en
son söylediğine uyacak şekilde stoğu sessizce yeniden yazan bir sistem, gerçek bir sapmayı
kanal hatasından ayırt edemez — üstelik düzeltme, tam da bir insanın gerçekleşmeden önce
görmek isteyeceği eylemdir. Aynısı 48 saatlik iade süresi için de geçerli: insana eskale
edilir, asla otomatik reddedilmez.

</details>

<details open>
<summary><b>🎭 The marketplace drives the tests · Testleri pazaryeri sürüyor</b></summary>

<br>

**EN** — The bundled mock marketplace does not merely answer requests; it **pushes signed
webhooks at the hub** with its own ordering, timing and duplication. Tests that build their
own webhooks verify the hub against the hub's own assumptions, which proves nothing. Seven
named storylines reproduce on demand what a live API would produce occasionally and
unrepeatably.

**TR** — Paketteki sahte pazaryeri yalnızca isteklere cevap vermez; kendi sırası, zamanlaması
ve tekrarlarıyla **hub'a imzalı webhook iter**. Kendi webhook'unu kuran testler, hub'ı hub'ın
kendi varsayımlarına karşı doğrular ve hiçbir şey kanıtlamaz. Yedi adlandırılmış senaryo,
canlı bir API'nin ara sıra ve tekrarlanamaz biçimde ürettiğini istendiğinde üretir.

</details>

---

## Running it · Çalıştırma

### 1 · Infrastructure · Altyapı

```bash
docker compose up -d
```

> Postgres + RabbitMQ. The broker image must be the delayed-message one — the task engine's
> scheduled work needs that plugin and a stock image will refuse the exchange.
>
> *Postgres + RabbitMQ. Broker imajı gecikmeli-mesaj sürümü olmalı — motorun zamanlanmış
> işleri o eklentiyi gerektirir, düz imaj exchange'i reddeder.*

### 2 · Backend

```bash
mvn -o install -DskipTests

cd backend/app
HUB_BOOTSTRAP_ORG="Demo Org" \
HUB_BOOTSTRAP_ADMIN_EMAIL="admin@example.com" \
mvn -o spring-boot:run
```

The bootstrap variables are only needed the first time. They create an organization and
print a one-time invitation token to the log — the password is chosen by whoever accepts
the invitation, never by whoever ran the deploy.

*Önyükleme değişkenleri yalnızca ilk seferde gerekir. Bir organizasyon oluşturup log'a tek
kullanımlık davet token'ı basarlar — parolayı daveti kabul eden kişi seçer, deploy'u
çalıştıran değil.*

```bash
curl -X POST localhost:8080/auth/invitations/accept \
  -H 'Content-Type: application/json' \
  -d '{"token":"<from the log>","password":"<yours>","fullName":"Admin"}'
```

### 3 · Mock marketplace · Sahte pazaryeri

```bash
cd mock-pazaryeri && npm install && node src/server.js   # :4100
```

### 4 · Dashboard · Arayüz

```bash
cd frontend && npm install
echo "NEXT_PUBLIC_API_URL=http://localhost:8080" > .env.local
npm run dev                                              # :3000
```

<div align="center">

| Service · Servis | URL |
|---|---|
| Dashboard | `http://localhost:3000` |
| API | `http://localhost:8080` |
| Mock marketplace | `http://localhost:4100` |
| RabbitMQ management | `http://localhost:15672` |

</div>

> **⚠️ Port note · Port notu** — Spring maps a `SERVER_PORT` environment variable onto
> `server.port`, and it overrides the configured value. If the application starts somewhere
> unexpected, check that variable first.
>
> *Spring, `SERVER_PORT` ortam değişkenini `server.port`'a eşler ve yapılandırılan değeri
> ezer. Uygulama beklenmedik bir yerde açılıyorsa önce o değişkene bakın.*

---

## Testing · Test

```bash
mvn -o test
```

Every test class brings up its own Postgres, its own RabbitMQ and its own mock marketplace
through Testcontainers. Nothing is shared with your development environment, and nothing is
replaced by an in-memory stand-in — the row-level security policies, the real broker
semantics and real HTTP are all part of what is under test.

*Her test sınıfı Testcontainers ile kendi Postgres'ini, kendi RabbitMQ'sunu ve kendi sahte
pazaryerini ayağa kaldırır. Geliştirme ortamınızla hiçbir şey paylaşılmaz ve hiçbir şey
bellek içi taklitle değiştirilmez — satır seviyesi güvenlik politikaları, gerçek broker
semantiği ve gerçek HTTP test edilenin parçasıdır.*

### Driving scenarios by hand · Senaryoları elle sürmek

```bash
curl -X POST localhost:4100/_admin/storylines/run \
  -H 'Content-Type: application/json' \
  -d '{
    "hubUrl": "http://localhost:8080",
    "organizationId": "<org id>",
    "channelConnectionId": "<connection id>",
    "storyline": "out-of-order"
  }'
```

| Storyline · Senaryo | What it reproduces · Neyi üretir |
|---|---|
| `happy-path` | Four events in order, end to end · Sıralı dört olay, uçtan uca |
| `out-of-order` | Payment arriving before its order · Ödeme siparişinden önce geliyor |
| `duplicate-delivery` | The same event delivered three times · Aynı olay üç kez teslim ediliyor |
| `same-second` | Two events sharing one timestamp · Aynı zaman damgalı iki olay |
| `partial-cancel` | One line of three cancelled · Üç kalemin biri iptal |
| `oversell-race` | Two orders racing for the last unit · Son birim için yarışan iki sipariş |
| `unknown-item` | An item the hub has never seen · Hub'ın hiç görmediği bir kalem |

Failure injection is available too — rate limiting, latency, whole-call failures, invalid
credentials — via `POST /_admin/scenario`.

*Hata enjeksiyonu da var — hız sınırlama, gecikme, çağrı bazlı hatalar, geçersiz kimlik —
`POST /_admin/scenario` üzerinden.*

---

## Repository layout · Dizin yapısı

```
ecommerce-hub/
├── backend/                     Maven multi-module Spring Boot application
│   ├── hub-domain/              domain model and business rules
│   ├── connector-sdk/           marketplace connector contract
│   ├── connector-mock/          connector for the mock marketplace
│   ├── ingest/                  webhook intake
│   ├── dispatcher/              tenant-fair work dispatch
│   ├── task-handlers/           task engine handlers
│   └── app/                     application, REST API, migrations, schedulers
├── frontend/                    Next.js operator dashboard
├── mock-pazaryeri/              scenario-driven fake marketplace (Node/Express)
└── docker-compose.yml           Postgres + RabbitMQ
```

Database migrations live in `backend/app/src/main/resources/db/migration/hub/` and start at
`V1000` so the task engine's own migration chain can grow independently.

*Veritabanı göçleri `backend/app/src/main/resources/db/migration/hub/` altındadır ve
`V1000`'den başlar; görev motorunun kendi göç zinciri bağımsız büyüyebilsin diye.*

---

<div align="center">
<sub>

Built on [`gorev-motoru`](https://github.com/gitahmetcelik/gorev-motoru) · Private project

</sub>
</div>
