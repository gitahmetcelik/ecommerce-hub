const express = require('express');
const store = require('../state');

const router = express.Router();

router.get('/returns', (req, res) => {
  const state = store.get();
  const since = req.query.since ? new Date(req.query.since) : null;
  const page = parseInt(req.query.page || '1', 10);
  const pageSize = parseInt(req.query.pageSize || '10', 10);

  const items = state.returns.filter((r) => !since || new Date(r.createdAt) >= since);
  const start = (page - 1) * pageSize;
  const pageItems = items.slice(start, start + pageSize);
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));

  res.json({ items: pageItems, page, pageSize, totalPages, hasMore: page < totalPages });
});

// Idempotent on intentId (Plan §4.3/§8): a repeated call with the same intentId
// must produce exactly one effect, never a second decision recorded.
router.post('/returns/:id/decision', (req, res) => {
  const state = store.get();
  const { id } = req.params;
  const { intentId, decision } = req.body;

  if (!intentId) {
    return res.status(400).json({ error: 'intentId is required' });
  }

  const existing = state.returnDecisionsByIntentId.get(intentId);
  if (existing) {
    state.callLog.push({ intentId, kind: 'RETURN_DECISION', result: existing });
    return res.json(existing);
  }

  const returnRequest = state.returns.find((r) => r.id === id);
  if (!returnRequest) {
    return res.status(404).json({ error: 'return not found' });
  }

  returnRequest.status = decision === 'ACCEPT' ? 'ACCEPTED' : 'REJECTED';
  const result = { id, decision, status: returnRequest.status };
  state.returnDecisionsByIntentId.set(intentId, result);
  state.callLog.push({ intentId, kind: 'RETURN_DECISION', result });

  res.json(result);
});

module.exports = router;
