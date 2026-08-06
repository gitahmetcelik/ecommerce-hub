// Named storylines the fake marketplace can play at the hub.
//
// These live here, on the channel side, rather than in the hub's tests on purpose. A
// real marketplace decides what it sends and in what order; the hub only reacts. Test
// fixtures that build their own webhooks quietly encode the hub's assumptions about
// what a channel does, and then verify those same assumptions — which proves nothing.
//
// Each storyline is a list of steps. A step is one webhook, plus how the channel
// chooses to (mis)behave while delivering it:
//   delayMs   — wait before sending (real channels are not instantaneous)
//   repeat    — deliver the same event N times (at-least-once delivery, plan §4.1)
//   sequence  — the channel's own ordering number, when it has one (plan §6)

function item(sku, quantity, unitPrice, targetStatus, extra = {}) {
  return {
    sku,
    channelProductId: extra.channelProductId ?? sku,
    channelVariantId: extra.channelVariantId ?? sku,
    barcode: extra.barcode ?? null,
    quantity,
    unitPrice,
    vatRate: 0,
    targetStatus: targetStatus ?? null,
  };
}

function order(channelOrderNumber, items, total, currency = 'USD') {
  return { channelOrderNumber, total, currency, items };
}

const STORYLINES = {
  // The ordinary life of an order, delivered in order and once each.
  'happy-path': ({ orderNumber = 'SC-HAPPY', sku = 'SKU-0' } = {}) => [
    { eventType: 'order.created', sequence: 1, order: order(orderNumber, [item(sku, 2, 19.99)], 39.98) },
    { eventType: 'payment.succeeded', sequence: 2, delayMs: 50, order: order(orderNumber, [item(sku, 2, 19.99)], 39.98) },
    { eventType: 'order.shipped', sequence: 3, delayMs: 50, order: order(orderNumber, [item(sku, 2, 19.99)], 39.98) },
    { eventType: 'order.delivered', sequence: 4, delayMs: 50, order: order(orderNumber, [item(sku, 2, 19.99)], 39.98) },
  ],

  // plan Faz 2 gate: payment lands BEFORE the order that it pays for. Channels do this
  // routinely and no amount of queueing on our side can fix ordering the source never had.
  'out-of-order': ({ orderNumber = 'SC-OOO', sku = 'SKU-1' } = {}) => [
    { eventType: 'payment.succeeded', sequence: 2, order: order(orderNumber, [item(sku, 1, 25.0)], 25.0) },
    { eventType: 'order.created', sequence: 1, delayMs: 80, order: order(orderNumber, [item(sku, 1, 25.0)], 25.0) },
  ],

  // At-least-once delivery. The same event, three times, as fast as the channel can.
  'duplicate-delivery': ({ orderNumber = 'SC-DUP', sku = 'SKU-2' } = {}) => [
    { eventType: 'order.created', sequence: 1, repeat: 3, order: order(orderNumber, [item(sku, 1, 10.0)], 10.0) },
  ],

  // plan §6 (v3): two events inside the same second. The v2 timestamp rule dropped the
  // second one silently; the sequence number is what resolves them.
  'same-second': ({ orderNumber = 'SC-SEC', sku = 'SKU-3' } = {}) => [
    { eventType: 'order.created', sequence: 1, sameInstant: true, order: order(orderNumber, [item(sku, 1, 15.0)], 15.0) },
    { eventType: 'payment.succeeded', sequence: 2, sameInstant: true, order: order(orderNumber, [item(sku, 1, 15.0)], 15.0) },
  ],

  // One line of a three-line order is cancelled — the case that makes order status a
  // derived value rather than a column somebody sets (plan §0, kalem seviyesi).
  'partial-cancel': ({ orderNumber = 'SC-PARTIAL' } = {}) => {
    const allPaid = [item('SKU-4', 1, 20.0), item('SKU-5', 1, 30.0), item('SKU-6', 1, 40.0)];
    const oneCancelled = [
      item('SKU-4', 1, 20.0, 'PAID'),
      item('SKU-5', 1, 30.0, 'CANCELLED'),
      item('SKU-6', 1, 40.0, 'PAID'),
    ];
    return [
      { eventType: 'order.created', sequence: 1, order: order(orderNumber, allPaid, 90.0) },
      { eventType: 'payment.succeeded', sequence: 2, delayMs: 50, order: order(orderNumber, allPaid, 90.0) },
      { eventType: 'order.cancelled', sequence: 3, delayMs: 50, order: order(orderNumber, oneCancelled, 90.0) },
    ];
  },

  // A sale that outruns the stock behind it. Two orders for the same variant, back to
  // back, each claiming the last unit (plan Faz 4 oversell gate).
  'oversell-race': ({ sku = 'SKU-7' } = {}) => [
    { eventType: 'order.created', sequence: 1, order: order('SC-RACE-A', [item(sku, 1, 12.0)], 12.0) },
    { eventType: 'order.created', sequence: 1, order: order('SC-RACE-B', [item(sku, 1, 12.0)], 12.0) },
  ],

  // An item the hub has never seen. plan Faz 3: it must land in mapping_candidate and
  // the operator queue rather than being invented or dropped.
  'unknown-item': ({ orderNumber = 'SC-UNKNOWN' } = {}) => [
    {
      eventType: 'order.created',
      sequence: 1,
      order: order(orderNumber, [item('SKU-NEVER-SEEN', 1, 9.99, null, { barcode: 'BARCODE-NEVER-SEEN' })], 9.99),
    },
  ],
};

function build(name, params) {
  const factory = STORYLINES[name];
  if (!factory) {
    return null;
  }
  return factory(params ?? {});
}

module.exports = { build, names: () => Object.keys(STORYLINES) };
