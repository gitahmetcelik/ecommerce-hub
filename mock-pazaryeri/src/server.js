const express = require('express');
const scenarioMiddleware = require('./middleware/scenarios');

const app = express();
app.use(express.json());
app.use(scenarioMiddleware);

app.use(require('./routes/orders'));
app.use(require('./routes/catalog'));
app.use(require('./routes/stock'));
app.use(require('./routes/price'));
app.use(require('./routes/returns'));
app.use(require('./routes/shipments'));
app.use(require('./routes/refunds'));
app.use(require('./routes/callStatus'));
app.use(require('./routes/auth'));
app.use(require('./routes/storylines'));
app.use(require('./routes/admin'));

app.get('/health', (req, res) => res.json({ ok: true }));

const port = process.env.PORT || 4100;
app.listen(port, () => {
  console.log(`mock-pazaryeri listening on :${port}`);
});
