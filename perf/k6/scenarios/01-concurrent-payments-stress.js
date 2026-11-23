// 01 — POST /api/v1/payments under a 0→200 VU ramp (2 min) and a 3 min hold.
// Pass: every create returns 201, p95 < 200 ms, zero 5xx, no HikariCP exhaustion
// (exhaustion would show up as 5xx or 503 "Transient Lock Failure" responses).
// The write limit is per user (PAYMENT_WRITE_RATE_LIMIT_PER_MINUTE, default 120):
// perf/compose.benchmark.yml raises it for the local stack only, so 429s do not mask latency.
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { loginToken } from '../lib/auth.js';
import { createPayment, newKey } from '../lib/payments.js';
import { summaryFiles } from '../lib/summary.js';

const serverErrors = new Counter('server_errors');

export const options = {
  scenarios: {
    stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '2m', target: 200 },
        { duration: '3m', target: 200 },
      ],
      gracefulRampDown: '30s',
    },
  },
  thresholds: {
    'http_req_duration{name:create_payment}': ['p(95)<200'],
    'checks{check:status is 201}': ['rate==1'],
    server_errors: ['count==0'],
    http_req_failed: ['rate==0'],
  },
};

export function setup() {
  return { token: loginToken() };
}

export default function (data) {
  const res = createPayment(data.token, newKey('s01'), 'create_payment');
  if (res.status >= 500) serverErrors.add(1);
  check(res, { 'status is 201': (r) => r.status === 201 });
}

export function handleSummary(data) {
  return summaryFiles('01-concurrent-payments-stress', data);
}
