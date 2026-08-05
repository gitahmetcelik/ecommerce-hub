const express = require('express');
const store = require('../state');

const router = express.Router();

// Backs PlatformConnector.durumSorgula — "what actually happened for this intent",
// used by ChannelCallIntentService.recoverStuckIntents instead of re-calling.
router.get('/call-status', (req, res) => {
  const state = store.get();
  const intentId = req.query.intentId;

  const entry = state.callLog.find((e) => e.intentId === intentId);
  if (!entry) {
    return res.json({ found: false });
  }
  res.json({ found: true, kind: entry.kind, result: entry.result });
});

module.exports = router;
