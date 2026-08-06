// In-memory state for the fake marketplace. Reset between tests via POST /_admin/reset.

const DEFAULT_SCENARIOS = {
  rateLimitAfter: null,       // number: requests allowed before every further call gets 429
  requestCount: 0,            // internal counter, reset alongside scenarios
  delayMsByPath: {},          // { "/orders": 200 } — artificial latency per path
  timeoutPaths: [],           // paths that hang well past any sane client timeout
  failSkus: [],               // skus that always fail in stock/price bulk updates
  duplicateOrders: false,     // repeats the first order in the feed
  outOfOrderOrders: false,    // shuffles order sequence instead of chronological
  credentialsInvalid: false,  // GET /auth/status returns 401
  stockUpdateFails: false,    // POST /stock/bulk-update returns 500 (whole call fails, not per-item)
};

function freshState() {
  return {
    scenarios: JSON.parse(JSON.stringify(DEFAULT_SCENARIOS)),
    orders: [],
    catalog: [],
    returns: [],
    shipmentsByIntentId: new Map(),
    returnDecisionsByIntentId: new Map(),
    callLog: [], // { intentId, kind, result } — backs GET /call-status
    // What this channel currently believes it can sell, keyed by sku. Written by
    // /stock/bulk-update (so a test can assert the LAST pushed value actually landed)
    // and by /_admin/stock (so a test can inject the drift the nightly reconcile is
    // meant to notice). A sku absent from here means the channel reports no stock for it.
    stockBySku: {},
    // Per-path request counter. Coalescing is only meaningful if the number of CALLS
    // can be shown to have dropped — 50 separate calls each carrying the correct value
    // would sail through a final-value-only assertion while being exactly the failure
    // the coalescing table exists to prevent.
    callCountsByPath: {},
  };
}

let state = freshState();

function reset() {
  state = freshState();
  seed();
}

function seed() {
  const now = Date.now();
  for (let i = 0; i < 50; i++) {
    state.orders.push({
      id: `order-${i}`,
      customerOrderNumber: `CO-${1000 + i}`,
      createdAt: new Date(now - (50 - i) * 60000).toISOString(),
      sequence: i,
      items: [{ sku: `SKU-${i % 10}`, quantity: 1 + (i % 3), unitPrice: 19.99 }],
    });
  }
  for (let i = 0; i < 10; i++) {
    state.catalog.push({ id: `product-${i}`, sku: `SKU-${i}`, barcode: `BARCODE-${i}`, title: `Product ${i}` });
  }
  // Non-ASCII title on purpose (plan §8 contract test: character encoding round-trip).
  state.catalog.push({ id: 'product-tr', sku: 'SKU-TR', barcode: 'BARCODE-TR', title: 'Türkçe Ürün İçeriği 😀' });
  for (let i = 0; i < 5; i++) {
    state.returns.push({
      id: `return-${i}`,
      orderId: `order-${i}`,
      createdAt: new Date(now - i * 60000).toISOString(),
      status: 'REQUESTED',
    });
  }
}

reset();

module.exports = {
  get: () => state,
  reset,
};
