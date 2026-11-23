import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, JSON_HEADERS, PAYMENTS_EMAIL, PAYMENTS_PASSWORD } from './config.js';
import { header } from './payments.js';

// One login per scenario from setup(). Auth is limited to 20 requests/min per IP
// and 15/min per account (identity/infrastructure/AuthRateLimiter), so VUs share the token.
export function loginToken() {
  if (!PAYMENTS_EMAIL || !PAYMENTS_PASSWORD) {
    throw new Error('PAYMENTS_EMAIL and PAYMENTS_PASSWORD are required; perf/run-benchmark.sh sources them from /tmp/payments-k6.env');
  }
  let res = http.post(
    `${BASE_URL}/api/v1/auth/login`,
    JSON.stringify({ email: PAYMENTS_EMAIL, password: PAYMENTS_PASSWORD }),
    { headers: JSON_HEADERS, tags: { name: 'login' } },
  );
  if (res.status === 429) {
    const retryAfter = Number(header(res, 'Retry-After')) || 60;
    console.warn(`login rate-limited (HTTP 429); waiting ${retryAfter}s before retrying...`);
    sleep(retryAfter + 1);
    res = http.post(
      `${BASE_URL}/api/v1/auth/login`,
      JSON.stringify({ email: PAYMENTS_EMAIL, password: PAYMENTS_PASSWORD }),
      { headers: JSON_HEADERS, tags: { name: 'login_retry' } },
    );
  }
  if (res.status !== 200) {
    throw new Error(`login returned HTTP ${res.status}; check the seeded account and the auth rate limit`);
  }
  return res.json('accessToken');
}
