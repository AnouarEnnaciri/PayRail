(() => {
'use strict';
const TESTS = 'https://github.com/AnouarEnnaciri/PayRail/blob/main/backend/src/test/java/com/payrail/backend/';
const $ = (s, r = document) => r.querySelector(s);
const $$ = (s, r = document) => [...r.querySelectorAll(s)];
const el = (tag, props = {}, ...kids) => { const n = Object.assign(document.createElement(tag), props); kids.forEach(k => n.append(k)); return n; };
const mad = n => (n / 100).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/* nav highlight */
const links = $$('.top nav a[href^="#"]');
if ('IntersectionObserver' in window) {
  const io = new IntersectionObserver(es => es.filter(e => e.isIntersecting).forEach(e =>
    links.forEach(a => a.setAttribute('aria-current', String(a.getAttribute('href') === '#' + e.target.id)))), { rootMargin: '-35% 0px -60% 0px' });
  $$('main section').forEach(s => io.observe(s));
}

/* architecture flows */
const FLOWS = {
  internal: ['Internal transfer', [
    'The portal calls <code>POST /api/client/transfers</code> with an idempotency key (CLIENT role, CSRF token).',
    'One transaction: idempotency row, both accounts locked in UUID order, debit and credit, transfer <code>COMPLETED</code>, one balanced ledger posting.']],
  external: ['External settlement', [
    'One transaction debits the customer, credits <code>SETTLEMENT_CLEARING</code>, stores <code>PENDING_SETTLEMENT</code> and writes an outbox event.',
    'The worker claims the event (lease, <code>SKIP LOCKED</code>), commits, then calls the simulator with a stable request ID.',
    'A verified SETTLED or REJECTED receipt finalizes the transfer in a second, fenced transaction. Timeouts trigger a lookup on the same ID, never a refund.']],
  callback: ['Signed callback', [
    'The simulator can push a terminal result to <code>/api/settlement/callbacks</code> with an HMAC signature header.',
    'The backend verifies the signature, locks the outbox event first, checks amount and destination, and stores the event ID so a duplicate posts nothing.']]
};
const diagram = $('#diagram'), flowtext = $('#flowtext');
function showFlow(name) {
  diagram.dataset.flow = name;
  $$('.seg button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.flow === name)));
  const [title, steps] = FLOWS[name];
  flowtext.innerHTML = '<b>' + title + '</b>' + steps.map(s => '<span>' + s + '</span>').join('');
}
$$('.seg button').forEach(b => b.addEventListener('click', () => showFlow(b.dataset.flow)));
showFlow('internal');

/* Reliability Lab: guided explanation, computed in the browser, no backend */
const A = 50000, PEND = 'PENDING_SETTLEMENT';
const E = (tx, acct, amt) => ({ tx, acct, amt });
const EXT = { Customer: 200000, SETTLEMENT_CLEARING: 0, EXTERNAL_SETTLED: 0 };
const RES = [E('reserve', 'Customer', -A), E('reserve', 'SETTLEMENT_CLEARING', A)];
const FIN = [E('finalize', 'SETTLEMENT_CLEARING', -A), E('finalize', 'EXTERNAL_SETTLED', A)];
const COMP = [E('compensate', 'SETTLEMENT_CLEARING', -A), E('compensate', 'Customer', A)];
const S = (title, text, transfer, outbox, entries = []) => ({ title, text, transfer, outbox, entries });
const U = 'UncertainSettlementOutcomeIntegrationTest', FZ = 'ExternalSettlementFinalizationIntegrationTest', OR = 'OutboxRecoveryIntegrationTest';

const SCENARIOS = [
  { id: 'idem', label: 'Duplicate request', accounts: { 'Customer A': 200000, 'Customer B': 100000 }, tests: ['IdempotentTransferServiceTest', 'ConcurrentInternalTransferTest'],
    steps: [
      S('First request', 'Both accounts are locked in UUID order, the transfer commits, and the ledger posts one balanced transaction.', 'COMPLETED', 'n/a', [E('transfer', 'Customer A', -A), E('transfer', 'Customer B', A)]),
      S('Same key, same payload', 'The stored request hash matches, so the original transfer is returned. No new entries.', 'COMPLETED', 'n/a'),
      S('Same key, other amount', 'The hash differs, so the API answers 409 Conflict and writes nothing.', 'COMPLETED', 'n/a')] },
  { id: 'lost', label: 'Response lost', accounts: EXT, tests: [U, FZ],
    steps: [
      S('Reserve', 'Debit, transfer, ledger posting and outbox event commit in one transaction.', PEND, 'PENDING', RES),
      S('Timeout', 'The worker had claimed the event in a committed transaction. A timeout does not say whether the simulator processed it.', PEND, 'PROCESSING'),
      S('Look up same ID', 'The lookup returns SETTLED with an external reference. Amount and destination are checked against the transfer.', PEND, 'PROCESSING'),
      S('Finalize once', 'Receipt and finalization commit together. A unique partial index forbids any second terminal effect.', 'SETTLED', 'PROCESSED', FIN)] },
  { id: 'nf', label: '404, then retry', accounts: EXT, tests: [U],
    steps: [
      S('Reserve', 'Customer funds move to the clearing account.', PEND, 'PENDING', RES),
      S('Lookup: not found', 'NOT_FOUND is evidence of absence right now, not a rejection, so no refund. The event returns to PENDING with backoff.', PEND, 'PENDING'),
      S('Retry, same ID', 'The retry reuses the settlement request ID, so a second settlement cannot be created. It settles and is finalized.', 'SETTLED', 'PROCESSED', FIN)] },
  { id: 'rej', label: 'Rejection', accounts: EXT, tests: [FZ, 'TransferStateMachineIntegrationTest'],
    steps: [
      S('Reserve', 'Customer funds move to the clearing account.', PEND, 'PENDING', RES),
      S('Verified rejection', 'A matching REJECTED receipt is terminal evidence.', PEND, 'PROCESSING'),
      S('Compensate once', 'Clearing returns the funds to the customer. The unique index blocks a settlement afterwards and the status trigger blocks reopening.', 'FAILED', 'PROCESSED', COMP)] },
  { id: 'crash', label: 'Worker crash', accounts: EXT, tests: [OR],
    steps: [
      S('Reserve', 'Reservation commits with its outbox event.', PEND, 'PENDING', RES),
      S('Worker dies', 'The lease (30 seconds by default) expires with no durable completion.', PEND, 'PROCESSING'),
      S('Another worker reclaims', 'Attempt 1 is closed as LEASE_EXPIRED, a technical outcome. Attempt 2 reuses the request ID; a stale worker can no longer complete the event.', PEND, 'PROCESSING'),
      S('Finalize once', 'A verified SETTLED receipt finalizes the transfer. There is no second reservation.', 'SETTLED', 'PROCESSED', FIN)] },
  { id: 'cb', label: 'Duplicate callback', accounts: EXT, tests: ['SettlementCallbackIntegrationTest'],
    steps: [
      S('Reserve', 'Reservation commits with its outbox event.', PEND, 'PENDING', RES),
      S('Signed callback', 'HMAC-SHA256 is verified in constant time; amount and destination must match. The outbox event is acknowledged in the same transaction.', 'SETTLED', 'PROCESSED', FIN),
      S('Delivered again', 'The event ID is already stored, so the duplicate is acknowledged and nothing is posted. A missing or invalid signature is rejected and changes nothing.', 'SETTLED', 'PROCESSED')] },
  { id: 'dead', label: 'Retries exhausted', accounts: EXT, tests: [OR, 'FinancialReconciliationIntegrationTest'],
    steps: [
      S('Reserve', 'Reservation commits with its outbox event.', PEND, 'PENDING', RES),
      S('Attempts fail', 'Defaults: 8 attempts, backoff from 2 to 60 seconds. Every attempt is audited.', PEND, 'PENDING'),
      S('Dead letter', 'The event is DEAD_LETTER and the transfer RECONCILIATION_REQUIRED. The outcome is unknown, so money neither settles nor returns.', 'RECONCILIATION_REQUIRED', 'DEAD_LETTER'),
      S('OPS resolves', 'An OPS user can resolve it only if a fresh lookup shows a verified terminal receipt (SETTLED here). A 404 or timeout authorizes nothing.', 'SETTLED', 'PROCESSED', FIN)] }
];

const root = $('#lab-root');
let cur = 0, step = 0;
const tone = v => (v === 'RECONCILIATION_REQUIRED' || v === 'DEAD_LETTER') ? 'bad' : (v === PEND || v === 'PROCESSING' || v === 'PENDING') ? 'warn' : '';
const barClass = k => k === 'SETTLEMENT_CLEARING' ? 'amber' : k === 'EXTERNAL_SETTLED' ? 'sky' : '';

function render() {
  const sc = SCENARIOS[cur], st = sc.steps[step];
  const rows = [];
  sc.steps.slice(0, step + 1).forEach((s, i) => s.entries.forEach(e => rows.push({ ...e, at: i })));
  const bal = { ...sc.accounts };
  rows.forEach(r => { bal[r.acct] += r.amt; });
  const byTx = {};
  rows.forEach(r => { byTx[r.tx] = (byTx[r.tx] || 0) + r.amt; });
  const total0 = Object.values(sc.accounts).reduce((a, b) => a + b, 0);
  const total1 = Object.values(bal).reduce((a, b) => a + b, 0);

  const tabs = el('div', { className: 'tabs', role: 'tablist', ariaLabel: 'Failure scenarios' });
  SCENARIOS.forEach((s, i) => {
    const b = el('button', { className: 'btn sm', id: 'tab-' + s.id, type: 'button', role: 'tab', textContent: s.label, tabIndex: i === cur ? 0 : -1 });
    b.setAttribute('aria-selected', String(i === cur));
    b.setAttribute('aria-controls', 'lab-panel');
    const go = n => { cur = n; step = 0; render(); $('#tab-' + SCENARIOS[n].id).focus(); };
    b.addEventListener('click', () => go(i));
    b.addEventListener('keydown', e => { const d = { ArrowRight: 1, ArrowLeft: -1 }[e.key]; if (d) { e.preventDefault(); go((cur + d + SCENARIOS.length) % SCENARIOS.length); } });
    tabs.append(b);
  });

  const panel = el('div', { id: 'lab-panel', role: 'tabpanel' });
  panel.setAttribute('aria-labelledby', 'tab-' + sc.id);
  const tl = el('ol', { className: 'tl' });
  sc.steps.forEach((s, i) => {
    const b = el('button', { type: 'button', textContent: s.title, className: i < step ? 'done' : '' });
    if (i === step) b.setAttribute('aria-current', 'step');
    b.addEventListener('click', () => { step = i; render(); $('#lab-panel [aria-current=step]').focus(); });
    tl.append(el('li', {}, b));
  });

  const chips = el('div', { className: 'chips' });
  [['Transfer', st.transfer], ['Outbox event', st.outbox]].forEach(([k, v]) =>
    chips.append(el('div', { className: 'chip' }, el('small', { textContent: k }), el('b', { textContent: v, className: tone(v) }))));
  const explain = el('p', { className: 'explain', textContent: st.text });
  explain.setAttribute('aria-live', 'polite');
  const prev = el('button', { className: 'btn sm', type: 'button', textContent: 'Previous', disabled: step === 0 });
  const next = el('button', { className: 'btn sm main', type: 'button', textContent: 'Next step', disabled: step === sc.steps.length - 1 });
  prev.addEventListener('click', () => { step--; render(); });
  next.addEventListener('click', () => { step++; render(); });
  const ctrl = el('div', { className: 'seg', style: 'margin-top:1rem' }, prev, next);
  const evid = el('p', { className: 'note evid' }, 'Backed by: ');
  sc.tests.forEach(t => evid.append(el('a', { href: TESTS + t + '.java', textContent: t })));

  const bars = el('div', { className: 'bars' });
  Object.keys(bal).forEach(k => {
    const w = total1 ? Math.max(0, bal[k]) / total1 * 100 : 0;
    const u = el('u'); u.style.width = w + '%';
    bars.append(el('div', { className: 'bar ' + barClass(k) }, el('small', {}, el('span', { textContent: k }), el('span', { textContent: mad(bal[k]) + ' MAD' })), el('i', {}, u)));
  });
  const ok1 = Object.values(byTx).every(v => v === 0), ok2 = total0 === total1;
  const checks = el('ul', { className: 'checks' },
    el('li', { className: ok1 ? '' : 'fail', textContent: 'Every ledger transaction sums to zero' }),
    el('li', { className: ok2 ? '' : 'fail', textContent: 'Total across accounts unchanged: ' + mad(total1) + ' MAD' }));

  const tb = el('tbody');
  if (!rows.length) tb.append(el('tr', {}, el('td', { colSpan: 3, textContent: 'No ledger entries yet.' })));
  rows.forEach(r => tb.append(el('tr', { className: r.at === step ? 'new' : '' }, el('td', { textContent: r.tx }), el('td', { textContent: r.acct }),
    el('td', { className: 'n ' + (r.amt < 0 ? 'neg' : 'pos'), textContent: (r.amt > 0 ? '+' : '') + mad(r.amt) }))));
  const tbl = el('table', {}, el('thead', {}, el('tr', {}, el('th', { textContent: 'Posting' }), el('th', { textContent: 'Account' }), el('th', { className: 'n', textContent: 'Entry' }))), tb);
  const ledger = el('details', { className: 'more' }, el('summary', { textContent: 'Ledger entries' }), el('div', { className: 'body' }, el('div', { className: 'tw' }, tbl)));

  const left = el('div', {}, chips, explain, ctrl, evid);
  const right = el('div', {}, bars, checks, ledger);
  panel.append(tl, el('div', { className: 'view' }, left, right));
  const open = $('#lab-panel .more[open]');
  root.replaceChildren(tabs, panel);
  if (open) ledger.open = true;
}
render();

/* Progressive reveal, with no animation requirement for accessing content. */
if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches && 'IntersectionObserver' in window) {
  const blocks = $$('.delivery-feature, .delivery-flow, .cloud-spotlight');
  const observer = new IntersectionObserver((entries) => {
    entries.forEach(entry => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    });
  }, { threshold: 0.06, rootMargin: '0px 0px 35px 0px' });
  blocks.forEach((block, index) => {
    block.style.setProperty('--reveal-delay', `${(index % 3) * 85}ms`);
    block.classList.add('reveal');
    observer.observe(block);
  });
}
})();
