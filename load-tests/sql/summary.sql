\set ON_ERROR_STOP on

SELECT account_type, reference, balance_minor
FROM accounts
WHERE reference LIKE 'DEMO-CLIENT-%'
ORDER BY reference;

SELECT transfer_type, status, count(*) AS transfers
FROM transfers
GROUP BY transfer_type, status
ORDER BY transfer_type, status;

SELECT status, count(*) AS outbox_jobs
FROM outbox_events
GROUP BY status ORDER BY status;

SELECT count(*) AS ledger_transactions,
       (SELECT count(*) FROM ledger_entries) AS ledger_entries
FROM ledger_transactions;
