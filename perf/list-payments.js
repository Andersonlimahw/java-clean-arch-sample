import http from 'k6/http';
import { check, sleep } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const token = __ENV.ACCESS_TOKEN;

export const options = {
  vus: 5,
  duration: '30s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export function setup() {
  if (!token) {
    throw new Error('ACCESS_TOKEN is required');
  }
  const ready = http.get(`${baseUrl}/actuator/health/readiness`);
  if (ready.status !== 200) {
    throw new Error(`readiness returned ${ready.status}`);
  }
}

export default function () {
  const pages = [0, 1, 2];
  for (const page of pages) {
    const response = http.get(
      `${baseUrl}/api/v1/payments?page=${page}&size=20&sort=createdAt,desc`,
      { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'GET /api/v1/payments' } }
    );
    check(response, {
      'list status 200': (r) => r.status === 200,
      'page echoed': (r) => r.json('page') === page,
      'stable sort': (r) => r.json('sort') === 'createdAt,desc',
      'page has items': (r) => Array.isArray(r.json('items')) && r.json('items').length > 0,
    });
  }

  const ascending = http.get(
    `${baseUrl}/api/v1/payments?page=0&size=20&sort=createdAt,asc`,
    { headers: { Authorization: `Bearer ${token}` }, tags: { name: 'GET /api/v1/payments' } }
  );
  check(ascending, {
    'asc status 200': (r) => r.status === 200,
    'asc sort': (r) => r.json('sort') === 'createdAt,asc',
  });
  sleep(0.2);
}
