const express = require('express');
const store = require('../state');

const router = express.Router();

router.get('/orders', (req, res) => {
  const state = store.get();
  const since = req.query.since ? new Date(req.query.since) : null;
  const page = parseInt(req.query.page || '1', 10);
  const pageSize = parseInt(req.query.pageSize || '10', 10);

  let orders = state.orders.filter((o) => !since || new Date(o.createdAt) >= since);

  if (state.scenarios.duplicateOrders && orders.length > 0) {
    orders = [orders[0], ...orders];
  }
  if (state.scenarios.outOfOrderOrders) {
    orders = [...orders].reverse();
  }

  const start = (page - 1) * pageSize;
  const pageItems = orders.slice(start, start + pageSize);
  const totalPages = Math.max(1, Math.ceil(orders.length / pageSize));

  res.json({
    items: pageItems,
    page,
    pageSize,
    totalPages,
    hasMore: page < totalPages,
  });
});

module.exports = router;
