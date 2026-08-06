const express = require('express');
const store = require('../state');

const router = express.Router();

router.post('/stock/bulk-update', (req, res) => {
  const state = store.get();

  // Whole-call failure, distinct from failSkus (which is per-item). A channel that is
  // simply broken, or has revoked our access, fails the request itself — that is the
  // path that has to end in a credential check and the circuit breaker, whereas a
  // per-item rejection is normal business and only retries that item.
  if (state.scenarios.stockUpdateFails) {
    return res.status(500).json({ error: 'simulated channel failure' });
  }

  const updates = req.body.updates || [];
  const failSkus = new Set(state.scenarios.failSkus);

  const results = updates.map((u) => {
    if (failSkus.has(u.sku)) {
      return { sku: u.sku, success: false, error: 'simulated failure for this sku' };
    }
    // Only a successful update moves the channel's own number. A rejected sku keeping
    // its previous quantity is what makes the per-item retry path observable.
    state.stockBySku[u.sku] = u.quantity;
    return { sku: u.sku, success: true };
  });

  res.json({ results });
});

// What the channel currently believes it has. A real marketplace exposes this through
// its inventory/catalog feed; it is a separate endpoint here so a test can read the
// whole picture without paging through the catalog.
router.get('/stock', (req, res) => {
  res.json({ stockBySku: store.get().stockBySku });
});

module.exports = router;
