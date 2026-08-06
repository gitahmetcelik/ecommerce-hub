const crypto = require('crypto');
const express = require('express');
const store = require('../state');

const router = express.Router();

// Idempotent on intentId, like /shipments — and here it is not a nicety. A retried
// refund is a second payment to the customer; the whole point of the intent record on
// our side is that this channel-side check is the last line of defence, not the first.
router.post('/refunds', (req, res) => {
  const state = store.get();
  const { intentId, orderId, returnId, amount, currency } = req.body;

  if (!intentId) {
    return res.status(400).json({ error: 'intentId is required' });
  }

  if (state.scenarios.refundFails) {
    return res.status(500).json({ error: 'simulated refund failure' });
  }

  const existing = state.refundsByIntentId.get(intentId);
  if (existing) {
    state.callLog.push({ intentId, kind: 'REFUND', result: existing });
    return res.json(existing);
  }

  const refund = {
    id: `refund-${crypto.randomUUID()}`,
    orderId,
    returnId,
    amount,
    currency,
    createdAt: new Date().toISOString(),
  };
  state.refundsByIntentId.set(intentId, refund);
  state.callLog.push({ intentId, kind: 'REFUND', result: refund });

  res.status(201).json(refund);
});

// How many refunds actually happened. A test proving "the crashed worker did not pay
// twice" has to count payments on the channel, not statuses in our own database.
router.get('/_admin/refunds', (req, res) => {
  const state = store.get();
  res.json({ refunds: Array.from(state.refundsByIntentId.values()) });
});

module.exports = router;
