// 03 — POST /api/v1/auth/login rate limits, as the code enforces them
// (identity/web/AuthController + identity/infrastructure/AuthRateLimiter):
//   - IP key: 20 requests per minute across every auth endpoint (AUTH_IP_RATE_LIMIT_PER_MINUTE);
//   - account key: 15 requests per minute per SHA-256 of the lowercased email
//     (AUTH_ACCOUNT_RATE_LIMIT_PER_MINUTE). The spec's "20/min" is the IP value, not the account one.
// Phase A: 20 attempts on one email → 1–15 answer 200 or 401, 16–20 answer 429 with Retry-After.
// Phase B (after a 61 s wait so the IP window resets): 21 distinct emails from one IP →
//   1–20 answer 200 or 401, the 21st answers 429 with Retry-After (IP limit).
// Credentials here are throwaway and invalid on purpose; no account is needed.
import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';
import { BASE_URL, JSON_HEADERS } from '../lib/config.js';
import { header } from '../lib/payments.js';
import { summaryFiles } from '../lib/summary.js';

const RUN = Date.now().toString(36);
const expectationMet = new Rate('auth_expectation_met');
const limited429 = new Counter('auth_429_responses');
const unexpected = new Counter('auth_unexpected_status');

export const options = {
  scenarios: {
    limits: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '5m',
    },
  },
  thresholds: {
    auth_expectation_met: ['rate==1'],
    auth_unexpected_status: ['count==0'],
    auth_429_responses: ['count==6'],
    'http_req_duration{name:login_attempt}': ['p(95)<500'],
  },
};

function attempt(email) {
  return http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email, password: 'K6-wrong-Password1!' }),
    { headers: JSON_HEADERS, tags: { name: 'login_attempt' } },
  );
}

function judge(res, expectLimited) {
  let ok;
  if (expectLimited) {
    ok = res.status === 429 && header(res, 'Retry-After') !== null;
  } else {
    ok = res.status === 200 || res.status === 401;
  }
  if (res.status === 429) limited429.add(1);
  if (!ok) unexpected.add(1);
  expectationMet.add(ok);
}

export default function () {
  const sameAccount = `k6-limit-a-${RUN}@example.com`;
  for (let i = 1; i <= 20; i++) {
    judge(attempt(sameAccount), i >= 16);
  }

  // Both windows are 60 s from their first request; wait past phase A's window.
  sleep(61);

  for (let i = 1; i <= 21; i++) {
    judge(attempt(`k6-limit-b-${RUN}-${i}@example.com`), i === 21);
  }
}

export function handleSummary(data) {
  return summaryFiles('03-rate-limiting-auth-stress', data);
}
