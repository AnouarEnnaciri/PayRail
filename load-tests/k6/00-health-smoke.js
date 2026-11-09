import http from 'k6/http';
import { check, sleep } from 'k6';

// Run only against the isolated M16 Compose backend.
const BASE = __ENV.API_BASE_URL || 'http://backend:8080';
if (BASE !== 'http://backend:8080') {
  throw new Error('M16 smoke test must target the isolated Docker backend');
}

export const options = {
  scenarios: {
    health_smoke: {
      executor: 'constant-vus',
      vus: 5,
      duration: '20s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<750'],
    checks: ['rate>0.99'],
  },
};

export default function () {
  const response = http.get(
    `${BASE}/actuator/health`,
    { timeout: '5s' }
  );

  check(response, {
    'HTTP 200': (r) => r.status === 200,
    'Backend UP': (r) => r.status === 200 && r.json('status') === 'UP',
  });

  sleep(0.1);
}
