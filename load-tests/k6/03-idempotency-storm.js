import { sleep } from 'k6';
import { internalPayload, postTransfer, prepareClientSession, setupFixtures } from './common.js';

export const options = {
  scenarios: {
    duplicate_requests: {
      executor: 'per-vu-iterations',
      vus: 24,
      iterations: 1,
      maxDuration: '90s',
    },
  },
  thresholds: {
    http_req_failed: ['rate==0'],
    'http_req_duration{name:POST /api/client/transfers (same key)}': ['p(95)<10000'],
    checks: ['rate==1'],
  },
};

export function setup() {
  return { ...setupFixtures(), synchronizedStart: Date.now() + 10000 };
}

export default function (data) {
  // Warm each VU session before the synchronized concurrent write.
  prepareClientSession();
  const remaining = (data.synchronizedStart - Date.now()) / 1000;
  if (remaining > 0) sleep(remaining);
  // Every VU sends exactly the same key and payload; only one ledger effect is valid.
  postTransfer('/api/client/transfers', internalPayload(data, 137),
    `${data.runId}-storm`, 'POST /api/client/transfers (same key)');
}
