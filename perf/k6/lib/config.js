// Shared settings for the k6 suite. Only local Compose targets are accepted: any
// other BASE_URL throws during init, before a VU starts.
export const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
export const PAYMENTS_EMAIL = __ENV.PAYMENTS_EMAIL || '';
export const PAYMENTS_PASSWORD = __ENV.PAYMENTS_PASSWORD || '';
export const RESULTS_DIR = __ENV.RESULTS_DIR || 'perf/results';
export const JSON_HEADERS = { 'Content-Type': 'application/json' };

const LOCAL_TARGET = /^http:\/\/(127\.0\.0\.1|localhost|app)(:\d+)?(\/|$)/;
if (!LOCAL_TARGET.test(BASE_URL)) {
  throw new Error('BASE_URL must be a local Compose target (127.0.0.1, localhost or app); refusing to run');
}
