const crypto = require('crypto');
const express = require('express');
const store = require('../state');

const router = express.Router();

const SHARED_SIGNING_SECRET = 'mock-shared-secret';

router.post('/_admin/reset', (req, res) => {
  store.reset();
  res.json({ ok: true });
});

router.post('/_admin/scenario', (req, res) => {
  const state = store.get();
  Object.assign(state.scenarios, req.body);
  res.json({ ok: true, scenarios: state.scenarios });
});

// Overwrites the channel's own stock without going through /stock/bulk-update — this is
// how a test injects the drift the nightly reconcile (Plan §11) is meant to find. Real
// drift comes from a sale we have not seen yet or an edit made in the channel's own
// admin UI; from our side both look exactly like this.
router.post('/_admin/stock', (req, res) => {
  const state = store.get();
  Object.assign(state.stockBySku, req.body || {});
  res.json({ ok: true, stockBySku: state.stockBySku });
});

// Per-path call counts, so a coalescing test can assert on the NUMBER of requests and
// not merely on the final value (see state.js for why that distinction matters).
router.get('/_admin/stats', (req, res) => {
  const state = store.get();
  res.json({ callCountsByPath: state.callCountsByPath, requestCountTotal: state.requestCountTotal || 0 });
});

// Signs a payload the way a real channel would sign an outgoing webhook — lets the
// connector's imzaDogrula be tested against a signature actually produced with the
// shared secret, not one the test just made up.
router.post('/_admin/sign', express.text({ type: '*/*' }), (req, res) => {
  const signature = crypto.createHmac('sha256', SHARED_SIGNING_SECRET).update(req.body).digest('hex');
  res.json({ signature, secret: SHARED_SIGNING_SECRET });
});

module.exports = router;
