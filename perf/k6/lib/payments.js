import http from 'k6/http';
import { BASE_URL, JSON_HEADERS } from './config.js';

// Fixed body: the idempotency hash covers the payload, so every request with the
// same key must send identical bytes or the API answers 409 (payload mismatch).
const PAYLOAD = JSON.stringify({ amount: 10.5, currency: 'BRL', description: 'k6 load' });

export function newKey(prefix) {
  return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
}

export function createPayment(token, idempotencyKey, name) {
  return http.post(`${BASE_URL}/api/v1/payments`, PAYLOAD, {
    headers: {
      ...JSON_HEADERS,
      Authorization: `Bearer ${token}`,
      'Idempotency-Key': idempotencyKey,
    },
    tags: { name },
  });
}

// k6 keeps response header names as sent; look them up case-insensitively.
export function header(res, name) {
  const wanted = name.toLowerCase();
  for (const key of Object.keys(res.headers)) {
    if (key.toLowerCase() === wanted) return res.headers[key];
  }
  return null;
}
