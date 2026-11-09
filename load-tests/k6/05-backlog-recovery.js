import { getClientAccounts } from './common.js';

export const options = {
  scenarios: {
    recovery_read_latency: {
      executor: 'constant-arrival-rate',
      rate: 2,
      timeUnit: '1s',
      duration: '45s',
      preAllocatedVUs: 5,
      maxVUs: 10,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    checks: ['rate>0.99'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  // Measures client API responsiveness while the worker drains the prior outage backlog.
  // PostgreSQL verification (verify-recovered.sql) decides if recovery actually completed.
  getClientAccounts();
}
