# v5 Bulgular

Plan v5 §0'ın kapsam disiplini geregi: bir fazi calisirken bulunan, o fazin kapsaminda
olmayan bulgular burada kayit altina alinir, o an duzeltilmez.

## Faz 6 — MockPlatformConnector.updatePrice, discountedPrice'i tele hic tasimiyor

**Nerede:** `backend/connector-mock/src/main/java/com/ecommercehub/connector/mock/MockPlatformConnector.java`,
`updatePrice` metodu.

**Ne bulundu:** Giden istek govdesi yalnizca `sku` ve `price` alanlarini tasiyor;
`PriceUpdate.discountedPrice()` hic serilize edilmiyor. `hub.channel_price.discounted_price`
kolonu ve `PriceService`/`ChannelPushService` katmani indirimli fiyati dogru sekilde
hesaplayip kuyruga koyuyor (Faz 6), ama MOCK kanalinin kendisi bunu kanala hic iletmiyor —
bu yuzden indirimli fiyatin gercekten kanala ulastigi uctan uca (mock-pazaryeri uzerinden)
dogrulanamiyor.

**Neden Faz 6 kapsaminda degil:** Bu, Faz 1'de `PriceUpdate`/`updatePrice` imzasi
olusturulurken kalan bir eksiklik — Faz 6 fiyat domain'ini yazdi, connector'in tel
formatini degistirmedi. Duzeltmek connector-mock modulune (ve muhtemelen mock-pazaryeri'nin
`/price/bulk-update` route'una) dokunmayi gerektirir, bu da Faz 6'nin "dokunulmayacaklar"
listesindeki connector kapsaminin disinda.

**Etki:** Dusuk. Gercek kanallar (orn. Shopify, Faz 4) fiyati kendi API'lerine gore ayrica
isliyor; bu yalnizca MOCK/test kanalinin eksik bir tel-formati detayi. Indirimli fiyat
degeri dogru hesaplaniyor ve kuyruga giriyor — yalnizca mock connector'in kendisi onu
kanala iletmiyor.
