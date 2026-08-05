const store = require('../state');

// Long enough that it reads as "never responding" to any sane client timeout,
// without actually hanging the test process forever if something goes wrong.
const EFFECTIVE_TIMEOUT_MS = 60000;

function scenarioMiddleware(req, res, next) {
  if (req.path.startsWith('/_admin')) {
    return next();
  }

  const state = store.get();
  const scenarios = state.scenarios;
  state.requestCountTotal = (state.requestCountTotal || 0) + 1;

  if (scenarios.timeoutPaths.includes(req.path)) {
    setTimeout(() => {
      if (!res.headersSent) {
        res.status(504).json({ error: 'simulated timeout' });
      }
    }, EFFECTIVE_TIMEOUT_MS);
    return;
  }

  if (scenarios.rateLimitAfter !== null) {
    scenarios.requestCount += 1;
    if (scenarios.requestCount > scenarios.rateLimitAfter) {
      res.set('Retry-After', '1');
      return res.status(429).json({ error: 'rate limited' });
    }
  }

  const delay = scenarios.delayMsByPath[req.path];
  if (delay) {
    return setTimeout(next, delay);
  }

  next();
}

module.exports = scenarioMiddleware;
