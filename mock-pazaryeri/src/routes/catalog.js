const express = require('express');
const store = require('../state');

const router = express.Router();

router.get('/catalog', (req, res) => {
  const state = store.get();
  const page = parseInt(req.query.page || '1', 10);
  const pageSize = parseInt(req.query.pageSize || '10', 10);

  const start = (page - 1) * pageSize;
  const pageItems = state.catalog.slice(start, start + pageSize);
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
