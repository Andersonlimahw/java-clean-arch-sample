// 02 — 50 simultaneous POSTs with one Idempotency-Key and one user, then a replay.
// Code facts (shared/infrastructure/idempotency/IdempotencyService, GlobalExceptionHandler):
//   - a request that finds the key PROCESSING gets 409 Conflict;
//   - a DB lock contention (40001 / 40P01) gets 503 with Retry-After: 1;
//   - a request that arrives after completion gets 201 with Idempotent-Replayed: true.
// Pass: exactly one 201 without the replay header; every other response is 409, a
// replayed 201, or 503 with Retry-After: 1; nothing else. The sequential replay after
// the race returns 201 with Idempotent-Replayed: true.
// Note: k6 starts VUs with a small spread, not a barrier, so "simultaneous" is best effort.
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { loginToken } from '../lib/auth.js';
import { createPayment, header, newKey } from '../lib/payments.js';
import { summaryFiles } from '../lib/summary.js';

const created = new Counter('race_created_201');
const replayed = new Counter('race_replayed_201');
const inFlight409 = new Counter('race_inflight_409');
const transient503 = new Counter('race_transient_503');
const retryAfterMissing = new Counter('race_503_retry_after_missing');
const unexpected = new Counter('race_unexpected_status');

export const options = {
  scenarios: {
    race: {
      executor: 'per-vu-iterations',
      vus: 50,
      iterations: 1,
      maxDuration: '1m',
      exec: 'race',
    },
    replay: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '1m',
      startTime: '15s',
      exec: 'replay',
    },
  },
  thresholds: {
    race_created_201: ['count==1'],
    race_unexpected_status: ['count==0'],
    race_503_retry_after_missing: ['count==0'],
    'checks{check:replay returns 201 with Idempotent-Replayed}': ['rate==1'],
  },
};

export function setup() {
  return { token: loginToken(), key: newKey('s02') };
}

export function race(data) {
  const res = createPayment(data.token, data.key, 'race_create');
  const replayHeader = header(res, 'Idempotent-Replayed');
  if (res.status === 201 && replayHeader === null) created.add(1);
  else if (res.status === 201 && replayHeader === 'true') replayed.add(1);
  else if (res.status === 409) inFlight409.add(1);
  else if (res.status === 503) {
    transient503.add(1);
    if (header(res, 'Retry-After') !== '1') retryAfterMissing.add(1);
  } else unexpected.add(1);
}

export function replay(data) {
  // Runs after the race window, so the key is COMPLETED and must replay.
  const res = createPayment(data.token, data.key, 'replay_create');
  check(res, {
    'replay returns 201 with Idempotent-Replayed': (r) =>
      r.status === 201 && header(r, 'Idempotent-Replayed') === 'true',
  });
}

export function handleSummary(data) {
  return summaryFiles('02-idempotency-race-condition', data);
}
