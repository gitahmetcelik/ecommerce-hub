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
    return { sku: u.sku, success: true };
  });

  res.json({ results });
});

module.exports = router;
