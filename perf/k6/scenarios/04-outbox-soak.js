// 04 — transactional outbox soak: 5,000 payments in 1 min (constant arrival rate),
// then the drain. A monitor scrapes /actuator/prometheus every 2 s and records:
//   payments_outbox_pending                              (OutboxMetrics, unpublished outbox rows)
//   payments_queue_messages{queue="payments.created.notifications"}      (ready messages)
//   payments_queue_messages{queue="payments.created.notifications.dlq"}  (dead letters)
// Pass: every create is 201, zero 5xx, zero failed scrapes, and at the end the outbox,
// the notifications queue and its DLQ are all 0.
// Limit of this check: "ready" depth does not see unacked messages, so a message in flight
// at the end is caught only by the final zero requirement. Drain rate is printed by the
// monitor (console lines with pending/queue/dlq per sample) for run-benchmark.sh to keep.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Gauge } from 'k6/metrics';
import { BASE_URL } from '../lib/config.js';
import { loginToken } from '../lib/auth.js';
import { createPayment, newKey } from '../lib/payments.js';
import { summaryFiles } from '../lib/summary.js';

const serverErrors = new Counter('server_errors');
const scrapeMissing = new Counter('scrape_missing');
const outboxPending = new Gauge('outbox_pending');
const notificationsReady = new Gauge('notifications_ready');
const dlqReady = new Gauge('dlq_ready');

const DRAIN_TIMEOUT_MS = 5 * 60 * 1000;
const POLL_MS = 2000;

export const options = {
  scenarios: {
    soak: {
      executor: 'constant-arrival-rate',
      rate: 5000,
      timeUnit: '1m',
      duration: '1m',
      preAllocatedVUs: 200,
      maxVUs: 400,
      exec: 'soak',
    },
    monitor: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '7m',
      exec: 'monitor',
    },
  },
  thresholds: {
    'checks{check:status is 201}': ['rate==1'],
    server_errors: ['count==0'],
    http_req_failed: ['rate==0'],
    scrape_missing: ['count==0'],
    outbox_pending: ['value==0'],
    notifications_ready: ['value==0'],
    dlq_ready: ['value==0'],
  },
};

export function setup() {
  return { token: loginToken() };
}

export function soak(data) {
  const res = createPayment(data.token, newKey('s04'), 'soak_create');
  if (res.status >= 500) serverErrors.add(1);
  check(res, { 'status is 201': (r) => r.status === 201 });
}

// Reads one sample from the Prometheus text exposition. Returns NaN when absent.
function sample(body, metric, queue) {
  for (const line of body.split('\n')) {
    if (!line.startsWith(metric)) continue;
    const rest = line.slice(metric.length);
    if (rest[0] !== ' ' && rest[0] !== '{') continue;
    if (queue && !line.includes(`queue="${queue}"`)) continue;
    const value = Number(line.trim().split(/\s+/).pop());
    return Number.isFinite(value) ? value : NaN;
  }
  return NaN;
}

export function monitor() {
  const started = Date.now();
  let zeroSamples = 0;
  // Keep sampling until the load window has ended and the three depths stay at zero.
  while (Date.now() - started < DRAIN_TIMEOUT_MS) {
    const res = http.get(`${BASE_URL}/actuator/prometheus`, { tags: { name: 'prometheus' } });
    const pending = sample(res.body || '', 'payments_outbox_pending');
    const ready = sample(res.body || '', 'payments_queue_messages', 'payments.created.notifications');
    const dlq = sample(res.body || '', 'payments_queue_messages', 'payments.created.notifications.dlq');
    if (Number.isNaN(pending) || Number.isNaN(ready) || Number.isNaN(dlq)) {
      scrapeMissing.add(1);
    } else {
      outboxPending.add(pending);
      notificationsReady.add(ready);
      dlqReady.add(dlq);
      console.log(JSON.stringify({ t: Date.now() - started, pending, ready, dlq }));
      const drained = pending === 0 && ready === 0 && dlq === 0;
      zeroSamples = drained ? zeroSamples + 1 : 0;
      if (Date.now() - started > 65000 && zeroSamples >= 3) break;
    }
    sleep(POLL_MS / 1000);
  }
}

export function handleSummary(data) {
  return summaryFiles('04-outbox-soak', data);
}
