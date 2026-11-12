import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://app:8080';
const maxOffset = 10000;
const pageSize = 20;

export const options = {
  scenarios: {
    early_pages: {
      executor: 'constant-vus',
      vus: 5,
      duration: '30s',
      exec: 'earlyPages',
    },
    deep_pages: {
      executor: 'constant-vus',
      vus: 5,
      duration: '30s',
      exec: 'deepPages',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
    'http_req_duration{scenario:early_pages}': ['p(95)<50'],
    'http_req_duration{scenario:deep_pages}': ['p(95)<50'],
  },
};

export function setup() {
  const email = __ENV.PAYMENTS_EMAIL;
  const password = __ENV.PAYMENTS_PASSWORD;
  if (!email || !password) {
    throw new Error('PAYMENTS_EMAIL and PAYMENTS_PASSWORD are required');
  }

  const ready = http.get(`${baseUrl}/actuator/health/readiness`);
  if (ready.status !== 200) {
    throw new Error(`readiness returned ${ready.status}`);
  }

  const login = http.post(
    `${baseUrl}/api/v1/auth/login`,
    JSON.stringify({ email, password }),
    { headers: { 'Content-Type': 'application/json' } }
  );
  if (login.status !== 200) {
    throw new Error(`login returned ${login.status}`);
  }

  const token = login.json('accessToken');
  const first = http.get(
    `${baseUrl}/api/v1/payments?page=0&size=${pageSize}&sort=createdAt,desc`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  if (first.status !== 200) {
    throw new Error(`list returned ${first.status}`);
  }

  const total = first.json('totalElements');
  const lastDataPage = Math.max(0, Math.ceil(total / pageSize) - 1);
  const lastAllowedPage = Math.floor(maxOffset / pageSize);
  const lastPage = Math.min(lastDataPage, lastAllowedPage);
  return {
    token,
    lastPage,
    deepStart: Math.floor(lastPage / 2),
  };
}

function authHeaders(token) {
  return { Authorization: `Bearer ${token}` };
}

export function earlyPages(data) {
  for (const page of [0, 1, 2]) {
    const response = http.get(
      `${baseUrl}/api/v1/payments?page=${page}&size=${pageSize}&sort=createdAt,desc`,
      { headers: authHeaders(data.token), tags: { name: 'GET /api/v1/payments' } }
    );
    check(response, {
      'early status 200': (r) => r.status === 200,
      'early page echoed': (r) => r.json('page') === page,
      'early sort': (r) => r.json('sort') === 'createdAt,desc',
      'early page has items': (r) => Array.isArray(r.json('items')) && r.json('items').length > 0,
    });
  }

  const ascending = http.get(
    `${baseUrl}/api/v1/payments?page=0&size=${pageSize}&sort=createdAt,asc`,
    { headers: authHeaders(data.token), tags: { name: 'GET /api/v1/payments' } }
  );
  check(ascending, {
    'asc status 200': (r) => r.status === 200,
    'asc sort': (r) => r.json('sort') === 'createdAt,asc',
  });
  sleep(0.2);
}

export function deepPages(data) {
  const span = data.lastPage - data.deepStart;
  const page = data.deepStart + Math.floor(Math.random() * (span + 1));
  const response = http.get(
    `${baseUrl}/api/v1/payments?page=${page}&size=${pageSize}&sort=createdAt,desc`,
    { headers: authHeaders(data.token), tags: { name: 'GET /api/v1/payments deep' } }
  );
  check(response, {
    'deep status 200': (r) => r.status === 200,
    'deep page echoed': (r) => r.json('page') === page,
    'deep sort': (r) => r.json('sort') === 'createdAt,desc',
    'deep page has items': (r) => Array.isArray(r.json('items')) && r.json('items').length > 0,
  });
  sleep(0.2);
}
