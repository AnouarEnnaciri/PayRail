import { postTransfer, setupBaselineFixtures, TRANSACTION_THRESHOLDS } from './common.js';

// Each VU uses its own sender/receiver pair. Hot-account contention has its
// own scenario (02-hot-account.js) and is not mislabeled as baseline latency.
export const options = {
  noCookiesReset: true,
  scenarios: {
    internal_baseline: {
      executor: 'constant-arrival-rate',
      rate: Number(__ENV.M16_RATE || 5),
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      maxVUs: 20,
    },
  },
  thresholds: {
    ...TRANSACTION_THRESHOLDS,
    'http_req_duration{name:POST /api/client/transfers}': ['p(95)<2000'],
  },
};

export function setup() {
  return setupBaselineFixtures();
}

export default function (data) {
  const pair = data.pairs[(__VU - 1) % data.pairs.length];
  const key = `${data.runId}-baseline-${__VU}-${__ITER}`;
  postTransfer('/api/client/transfers', {
    senderAccountId: pair.senderId,
    receiverAccountId: pair.receiverId,
    amountMinor: 11,
  }, key, 'POST /api/client/transfers');
}
