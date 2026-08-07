const express = require('express');
const store = require('../state');

const router = express.Router();

router.post('/price/bulk-update', (req, res) => {
  const state = store.get();
  const updates = req.body.updates || [];
  const failSkus = new Set(state.scenarios.failSkus);

  const results = updates.map((u) => {
    if (failSkus.has(u.sku)) {
      return { sku: u.sku, success: false, error: 'simulated failure for this sku' };
    }
    // Only a successful update moves the channel's own number, same as /stock/bulk-update.
    state.priceBySku[u.sku] = u.price;
    return { sku: u.sku, success: true };
  });

  res.json({ results });
});

// What the channel currently believes the price is — mirrors GET /stock.
router.get('/price', (req, res) => {
  res.json({ priceBySku: store.get().priceBySku });
});

module.exports = router;
