import { internalPayload, postTransfer, setupFixtures, TRANSACTION_THRESHOLDS } from './common.js';

export const options = {
  noCookiesReset: true,
  scenarios: {
    hot_account: {
      executor: 'constant-arrival-rate',
      rate: 10,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 15,
      maxVUs: 30,
    },
  },
  thresholds: {
    ...TRANSACTION_THRESHOLDS,
    'http_req_duration{name:POST /api/client/transfers}': ['p(95)<2000'],
  },
};

export function setup() {
  return setupFixtures();
}

export default function (data) {
  // All requests compete for the SAME sender account's row lock.
  const key = `${data.runId}-hot-${__VU}-${__ITER}`;
  postTransfer('/api/client/transfers', internalPayload(data, 7), key, 'POST /api/client/transfers');
}
