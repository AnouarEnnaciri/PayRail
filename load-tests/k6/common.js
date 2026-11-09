import http from 'k6/http';
import { check } from 'k6';
import encoding from 'k6/encoding';

// Deliberately refuse remote targets: this suite must not load-test a public demo.
const BASE = __ENV.API_BASE_URL || 'http://backend:8080';
if (BASE !== 'http://backend:8080') {
  throw new Error('M16 is restricted to the isolated Docker Compose backend');
}

const ALPHA_USER = 'client.alpha@example.test';
const BETA_USER = 'client.beta@example.test';
const ALPHA_PASSWORD = __ENV.API_ALPHA_PASSWORD;
const BETA_PASSWORD = __ENV.API_BETA_PASSWORD;

function basic(user, password) {
  if (!password) throw new Error('Required demo credentials are missing');
  return `Basic ${encoding.b64encode(`${user}:${password}`)}`;
}

function authorization(user) {
  return basic(user, user === ALPHA_USER ? ALPHA_PASSWORD : BETA_PASSWORD);
}

function getAccounts(user) {
  const response = http.get(`${BASE}/api/client/accounts`, {
    headers: { Authorization: authorization(user) },
    tags: { name: 'GET /api/client/accounts (setup)' },
    timeout: '10s',
  });
  if (response.status !== 200) {
    throw new Error(`Cannot load isolated demo accounts: HTTP ${response.status}`);
  }
  const accounts = response.json();
  if (!Array.isArray(accounts)) throw new Error('Accounts API must return an array');
  return accounts;
}

export function setupFixtures() {
  const alpha = getAccounts(ALPHA_USER).find((a) => a.reference === 'DEMO-CLIENT-ALPHA');
  const beta = getAccounts(BETA_USER).find((a) => a.reference === 'DEMO-CLIENT-BETA');
  if (!alpha?.id || !beta?.id || alpha.id === beta.id) {
    throw new Error('Isolated demo account fixtures are missing or inconsistent');
  }
  return {
    alphaId: alpha.id,
    betaId: beta.id,
    runId: `m16-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`,
  };
}

// The baseline uses independent account pairs so row-lock contention is measured
// separately in 02-hot-account.js. These fixtures are created by seed-baseline.sql.
export function setupBaselineFixtures() {
  const run = setupFixtures();
  const accounts = getAccounts(ALPHA_USER);
  const byReference = new Map(accounts.map((a) => [a.reference, a]));
  const pairs = [];

  for (let i = 1; i <= 20; i++) {
    const index = String(i).padStart(2, '0');
    const sender = byReference.get(`M16-BASE-S-${index}`);
    const receiver = byReference.get(`M16-BASE-R-${index}`);
    if (!sender?.id || !receiver?.id || sender.id === receiver.id) {
      throw new Error(`Missing or invalid baseline pair ${index}; execute seed-baseline.sql`);
    }
    if (sender.balanceMinor < 1000 || sender.currency !== 'MAD' || receiver.currency !== 'MAD') {
      throw new Error(`Invalid funding or currency for baseline pair ${index}`);
    }
    pairs.push({ senderId: sender.id, receiverId: receiver.id });
  }
  return { ...run, pairs };
}

// Each k6 VU has its own JS runtime and cookie jar, so the session is not shared across VUs.
let clientCsrf = null;

function csrf() {
  if (clientCsrf) return clientCsrf;
  const response = http.get(`${BASE}/api/client/csrf`, {
    headers: { Authorization: authorization(ALPHA_USER) },
    tags: { name: 'GET /api/client/csrf' },
    timeout: '10s',
  });
  if (response.status !== 200) {
    throw new Error(`Cannot obtain CSRF token: HTTP ${response.status}`);
  }
  const value = response.json();
  if (!value?.token || value.headerName !== 'X-CSRF-TOKEN') {
    throw new Error('Unexpected CSRF response');
  }
  clientCsrf = value;
  return value;
}

export function prepareClientSession() {
  csrf();
}

export function postTransfer(path, payload, idempotencyKey, label) {
  const token = csrf();
  const response = http.post(`${BASE}${path}`, JSON.stringify(payload), {
    headers: {
      Authorization: authorization(ALPHA_USER),
      [token.headerName]: token.token,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
    tags: { name: label },
    timeout: '20s',
  });
  check(response, {
    'transfer HTTP 200': (r) => r.status === 200,
    'transfer ID returned': (r) => r.status === 200 && Boolean(r.json('transferId')),
  });
  return response;
}

export function getClientAccounts() {
  const response = http.get(`${BASE}/api/client/accounts`, {
    headers: { Authorization: authorization(ALPHA_USER) },
    tags: { name: 'GET /api/client/accounts (recovery)' },
    timeout: '10s',
  });
  check(response, {
    'account API HTTP 200': (r) => r.status === 200,
    'account response valid': (r) => r.status === 200 && Array.isArray(r.json()),
  });
  return response;
}

export function internalPayload(data, amountMinor) {
  return {
    senderAccountId: data.alphaId,
    receiverAccountId: data.betaId,
    amountMinor,
  };
}

export const TRANSACTION_THRESHOLDS = {
  http_req_failed: ['rate<0.01'],
  checks: ['rate>0.99'],
  dropped_iterations: ['count==0'],
};
