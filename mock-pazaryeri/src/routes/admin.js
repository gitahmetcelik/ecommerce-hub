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

// Signs a payload the way a real channel would sign an outgoing webhook — lets the
// connector's imzaDogrula be tested against a signature actually produced with the
// shared secret, not one the test just made up.
router.post('/_admin/sign', express.text({ type: '*/*' }), (req, res) => {
  const signature = crypto.createHmac('sha256', SHARED_SIGNING_SECRET).update(req.body).digest('hex');
  res.json({ signature, secret: SHARED_SIGNING_SECRET });
});

module.exports = router;
