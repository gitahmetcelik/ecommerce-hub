const express = require('express');
const store = require('../state');

const router = express.Router();

router.get('/auth/status', (req, res) => {
  const state = store.get();
  if (state.scenarios.credentialsInvalid) {
    return res.status(401).json({ valid: false, reason: 'credentials invalid' });
  }
  res.json({ valid: true });
});

module.exports = router;
