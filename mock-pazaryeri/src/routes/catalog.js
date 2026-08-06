const express = require('express');
const store = require('../state');

const router = express.Router();

router.get('/catalog', (req, res) => {
  const state = store.get();
  const page = parseInt(req.query.page || '1', 10);
  const pageSize = parseInt(req.query.pageSize || '10', 10);

  const start = (page - 1) * pageSize;
  // Stock is folded into the catalog feed the way a real marketplace does it — the
  // nightly reconcile (Plan §11) walks this feed instead of making a second call per
  // sku. null (not 0) when the channel has never been told a quantity, so the reconcile
  // can tell "no opinion" apart from "genuinely zero" and skip the former.
  const pageItems = state.catalog.slice(start, start + pageSize).map((item) => ({
    ...item,
    stock: Object.prototype.hasOwnProperty.call(state.stockBySku, item.sku) ? state.stockBySku[item.sku] : null,
  }));
  const totalPages = Math.max(1, Math.ceil(state.catalog.length / pageSize));

  res.json({
    items: pageItems,
    page,
    pageSize,
    totalPages,
    hasMore: page < totalPages,
  });
});

module.exports = router;
