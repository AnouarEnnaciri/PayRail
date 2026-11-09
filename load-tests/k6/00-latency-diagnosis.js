import http from 'k6/http';
import encoding from 'k6/encoding';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { postTransfer, setupBaselineFixtures } from './common.js';

// This probe is intentionally READ-ONLY for the first two phases.
// The write phase uses the already seeded, isolated M16 database.
const API = 'http://backend:8080';
const healthLatency = new Trend('m16_health_ms', true);
const authLatency = new Trend('m16_auth_ms', true);
const transferLatency = new Trend('m16_transfer_ms', true);

const alphaPassword = __ENV.API_ALPHA_PASSWORD;
if (!alphaPassword) {
  throw new Error('Missing M16 isolated-demo credentials');
}
const authHeader = `Basic ${encoding.b64encode(`client.alpha@example.test:${alphaPassword}`)}`;

export const options = {
  noCookiesReset: true,
  scenarios: {
    health_only: {
      executor: 'constant-arrival-rate',
      exec: 'measureHealth',
      startTime: '0s',
      rate: 5,
      timeUnit: '1s',
      duration: '12s',
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
    basic_auth_only: {
      executor: 'constant-arrival-rate',
      exec: 'measureAuth',
      startTime: '17s',
      rate: 5,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 10,
      maxVUs: 25,
    },
    financial_write: {
      executor: 'constant-arrival-rate',
      exec: 'measureTransfer',
      startTime: '42s',
      rate: 5,
      timeUnit: '1s',
      duration: '20s',
      preAllocatedVUs: 15,
      maxVUs: 35,
    },
  },
};

export function setup() {
  return setupBaselineFixtures();
}

export function measureHealth() {
  const r = http.get(`${API}/actuator/health`, {
    tags: { name: 'DIAG health' },
    timeout: '15s',
  });
  healthLatency.add(r.timings.duration);
  check(r, { 'DIAG health HTTP 200': (x) => x.status === 200 });
}

export function measureAuth() {
  const r = http.get(`${API}/api/client/me`, {
    headers: { Authorization: authHeader },
    tags: { name: 'DIAG auth only' },
    timeout: '15s',
  });
  authLatency.add(r.timings.duration);
  check(r, { 'DIAG auth HTTP 200': (x) => x.status === 200 });
}

export function measureTransfer(data) {
  const pair = data.pairs[(__VU - 1) % data.pairs.length];
  const r = postTransfer('/api/client/transfers', {
    senderAccountId: pair.senderId,
    receiverAccountId: pair.receiverId,
    amountMinor: 11,
  }, `${data.runId}-diag-${__VU}-${__ITER}`,
  'DIAG financial write');
  transferLatency.add(r.timings.duration);
}
