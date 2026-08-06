const express = require('express');
const store = require('../state');

const router = express.Router();

/**
 * A second marketplace shape, served under /v2.
 *
 * Not a second copy of the first one with different data — a genuinely different shape,
 * which is the thing a real second integration would actually confront the hub with:
 *
 *   - the catalog is keyed by BARCODE and carries no seller SKU at all
 *   - orders reference lines by barcode too
 *   - it never pushes webhooks (its connector declares no WEBHOOK capability), so
 *     everything has to arrive through polling
 *
 * The point of the capability matrix is that none of this reaches the domain as a
 * special case. If any of it does, these routes are what will show it.
 */

function seedIfEmpty(state) {
  if (state.marketplace2Catalog) {
    return;
  }
  state.marketplace2Catalog = [];
  state.marketplace2Orders = [];

  const now = Date.now();
  for (let i = 0; i < 6; i++) {
    state.marketplace2Catalog.push({
      // No sku field anywhere. The barcode is the only identifier this channel has.
      barcode: `EAN-90000${i}`,
      title: `Marketplace2 Product ${i}`,
      stock: null,
    });
  }
  for (let i = 0; i < 8; i++) {
    state.marketplace2Orders.push({
      id: `m2-order-${i}`,
      createdAt: new Date(now - (8 - i) * 60000).toISOString(),
      lines: [{ barcode: `EAN-90000${i % 6}`, quantity: 1 + (i % 2), unitPrice: 34.5 }],
    });
  }
}

router.get('/v2/catalog', (req, res) => {
  const state = store.get();
  seedIfEmpty(state);

  const page = parseInt(req.query.page || '1', 10);
  const pageSize = parseInt(req.query.pageSize || '10', 10);
  const start = (page - 1) * pageSize;

  const items = state.marketplace2Catalog.slice(start, start + pageSize);
  const totalPages = Math.max(1, Math.ceil(state.marketplace2Catalog.length / pageSize));

  res.json({ items, page, pageSize, totalPages, hasMore: page < totalPages });
});

router.get('/v2/orders', (req, res) => {
  const state = store.get();
  seedIfEmpty(state);

  const since = req.query.since ? new Date(req.query.since) : null;
  const page = parseInt(req.query.page || '1', 10);
  const pageSize = parseInt(req.query.pageSize || '10', 10);

  const matching = state.marketplace2Orders.filter((o) => !since || new Date(o.createdAt) >= since);
  const start = (page - 1) * pageSize;
  const totalPages = Math.max(1, Math.ceil(matching.length / pageSize));

  res.json({
    items: matching.slice(start, start + pageSize),
    page,
    pageSize,
    totalPages,
    hasMore: page < totalPages,
  });
});

/**
 * Shipment creation WITHOUT idempotency-key support.
 *
 * The first marketplace recognises a repeated intent id and returns the original
 * shipment. This one does not: it is a channel that simply creates a second label,
 * which is why its connector declares no REQUEST_IDEMPOTENCY_KEY capability and why
 * recovery there has to go through a status query instead of a retry. Making it
 * genuinely unsafe to retry is the only way to prove the hub never does.
 */
router.post('/v2/shipments', (req, res) => {
  const state = store.get();
  state.marketplace2Shipments = state.marketplace2Shipments || [];

  const shipment = {
    id: `m2-shipment-${state.marketplace2Shipments.length + 1}`,
    orderId: req.body.orderId,
    trackingNumber: `M2-TRACK-${state.marketplace2Shipments.length + 1}`,
    intentId: req.body.intentId ?? null,
  };
  state.marketplace2Shipments.push(shipment);

  // Recorded so /call-status can answer "what happened for this intent" — the only
  // recovery route a channel like this leaves open.
  if (req.body.intentId) {
    state.callLog.push({ intentId: req.body.intentId, kind: 'SHIPMENT', result: shipment });
  }
  res.status(201).json(shipment);
});

router.get('/_admin/v2/shipments', (req, res) => {
  res.json({ shipments: store.get().marketplace2Shipments || [] });
});

module.exports = router;
