const crypto = require('crypto');
const express = require('express');
const store = require('../state');
const storylines = require('../scenarios/storylines');

const router = express.Router();

const SHARED_SIGNING_SECRET = 'mock-shared-secret';
const SIGNATURE_HEADER = 'X-Mock-Signature';

router.get('/_admin/storylines', (req, res) => {
  res.json({ storylines: storylines.names() });
});

/**
 * Plays a storyline AT the hub: the marketplace signs each event and POSTs it to the
 * hub's webhook endpoint, exactly as a real channel would.
 *
 * This is the direction that was never exercised before. Every earlier test built its
 * own request and handed it to the controller, which tests the hub against the hub's
 * own idea of a webhook. Here the channel decides the shape, the order, the timing and
 * the duplicates, and the hub has to cope — including the HMAC being computed over the
 * bytes that actually went down the wire rather than the bytes a test meant to send.
 *
 * Body: { hubUrl, organizationId, channelConnectionId, storyline, params }
 */
router.post('/_admin/storylines/run', async (req, res) => {
  const state = store.get();
  const { hubUrl, organizationId, channelConnectionId, storyline, params } = req.body;

  if (!hubUrl || !organizationId || !channelConnectionId || !storyline) {
    return res.status(400).json({
      error: 'hubUrl, organizationId, channelConnectionId and storyline are all required',
    });
  }

  const steps = storylines.build(storyline, params);
  if (!steps) {
    return res.status(404).json({ error: `unknown storyline: ${storyline}`, known: storylines.names() });
  }

  const endpoint = `${hubUrl}/webhooks/${organizationId}/${channelConnectionId}`;
  // One instant shared by every step that asks for it, so "same second" really is the
  // same second rather than two timestamps that happen to round together.
  const sharedInstant = new Date().toISOString();
  const delivered = [];

  for (const step of steps) {
    if (step.delayMs) {
      await sleep(step.delayMs);
    }

    const eventId = `${storyline}-${step.eventType}-${step.sequence}-${crypto.randomUUID().slice(0, 8)}`;
    const envelope = {
      eventId,
      eventType: step.eventType,
      eventAt: step.sameInstant ? sharedInstant : new Date().toISOString(),
      sequence: step.sequence ?? null,
      order: step.order,
    };

    // repeat delivers the SAME eventId again — that is what at-least-once means. A
    // fresh id each time would be a different event, and would prove nothing about
    // the hub's deduplication.
    const times = step.repeat ?? 1;
    for (let i = 0; i < times; i++) {
      delivered.push(await deliver(endpoint, envelope));
    }

    state.callLog.push({ intentId: eventId, kind: 'WEBHOOK_SENT', result: { eventType: step.eventType } });
  }

  res.json({ storyline, endpoint, delivered });
});

async function deliver(endpoint, envelope) {
  // Serialised once and signed over exactly those bytes. Re-serialising for the
  // signature would risk signing a different byte sequence than the one sent, which is
  // the classic way a signature check passes in testing and fails in production.
  const body = JSON.stringify(envelope);
  const signature = crypto.createHmac('sha256', SHARED_SIGNING_SECRET).update(body).digest('hex');

  try {
    const response = await fetch(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', [SIGNATURE_HEADER]: signature },
      body,
    });
    return { eventId: envelope.eventId, eventType: envelope.eventType, status: response.status };
  } catch (error) {
    return { eventId: envelope.eventId, eventType: envelope.eventType, error: String(error) };
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = router;
