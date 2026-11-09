import { postTransfer, setupFixtures, TRANSACTION_THRESHOLDS } from './common.js';

export const options = {
  noCookiesReset: true,
  scenarios: {
    settlement_outage: {
      executor: 'constant-arrival-rate',
      rate: 2,
      timeUnit: '1s',
      duration: '15s',
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
  },
  thresholds: {
    ...TRANSACTION_THRESHOLDS,
    'http_req_duration{name:POST /api/client/external-transfers}': ['p(95)<2000'],
  },
};

export function setup() {
  return setupFixtures();
}

export default function (data) {
  // Simulator MUST have been stopped before this scenario.
  // The reservation must commit despite external unavailability.
  postTransfer('/api/client/external-transfers', {
    senderAccountId: data.alphaId,
    destinationReference: `LOAD-M16-${data.runId}`,
    amountMinor: 19,
  }, `${data.runId}-outage-${__VU}-${__ITER}`,
  'POST /api/client/external-transfers');
}
