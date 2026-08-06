const crypto = require('crypto');
const express = require('express');
const store = require('../state');

const router = express.Router();

// Idempotent on intentId — the exact behavior the Phase 1 gate checks: calling this
// twice with the same intentId must create exactly one shipment, not two.
router.post('/shipments', (req, res) => {
  const state = store.get();
  const { intentId, orderId } = req.body;

  if (!intentId) {
    return res.status(400).json({ error: 'intentId is required' });
  }

  // Whole-call failure, for the Plan Phase 5 gate that drives the shipment step into
  // the DLQ and the operator queue.
  if (state.scenarios.shipmentFails) {
    return res.status(500).json({ error: 'simulated shipment failure' });
  }

  const existing = state.shipmentsByIntentId.get(intentId);
  if (existing) {
    state.callLog.push({ intentId, kind: 'SHIPMENT', result: existing });
    return res.json(existing);
  }

  const shipment = {
    id: `shipment-${crypto.randomUUID()}`,
    orderId,
    trackingNumber: `TRACK-${crypto.randomUUID().slice(0, 8).toUpperCase()}`,
    createdAt: new Date().toISOString(),
  };
  state.shipmentsByIntentId.set(intentId, shipment);
  state.callLog.push({ intentId, kind: 'SHIPMENT', result: shipment });

  res.status(201).json(shipment);
});

module.exports = router;
