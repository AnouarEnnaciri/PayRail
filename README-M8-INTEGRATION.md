# PayRail — M8: durable outbox worker

**Base examinée sur GitHub : `origin/main` commit `0e67fa324118f86bd032c4c88fa46a309072cd65` (M7).**
Ce ZIP est conçu pour cette version du code ; il ne lit pas tes modifications non commitées sur Windows.

## Ce que M8 fait

1. Claim d'un événement outbox **dans une transaction PostgreSQL courte**, avec `FOR UPDATE SKIP LOCKED` et un token de fencing `locked_by` par claim.
2. Enregistre une tentative `IN_FLIGHT` et fixe un bail `locked_until` **avant l'appel HTTP**.
3. Envoie `POST /settlements` au simulateur **hors transaction PostgreSQL**. Le payload provient de l'outbox M7, et le `settlementRequestId` reste stable.
4. Si le simulateur retourne `PENDING`, demande `GET /settlements/{id}` pour observer l'état (le mode `DELAYED_SUCCESS` est avancé par GET dans le simulateur M6).
5. Persiste le résultat dans `settlement_attempts` et change le statut du job dans **une nouvelle transaction**.
6. `SETTLED` / `REJECTED` confirmés = job `PROCESSED`, résultat durable pour **M9**. Un timeout, une erreur réseau, un HTTP 5xx ou `PENDING` = retry avec backoff.
7. Après épuisement des tentatives, ou erreur HTTP non réessayable, job `DEAD_LETTER` **sans mettre le transfert à `FAILED` et sans rembourser**.
8. Les baux expirés sont récupérés ; une ancienne réponse ne peut pas écraser un nouveau claim (token et attempt count).

**Frontière M8/M9** : M8 n'écrit aucun nouveau ledger, n'émet aucune compensation et ne change pas le statut financier `PENDING_SETTLEMENT`. M9 lira les reçus durables pour finaliser correctement (avec unicité en base). M10/M11 traiteront explicitement l'incertitude terminale / reconciliation et durciront la state machine.

## Fichiers

- `backend/src/main/resources/db/migration/V6__settlement_attempts.sql` — historique des tentatives.
- `backend/src/main/java/com/payrail/backend/outbox/OutboxStore.java` — claim, lease, retry, receipts, fencing.
- `backend/src/main/java/com/payrail/backend/outbox/OutboxProcessor.java` — orchestration *sans* transaction ouverte sur HTTP.
- `backend/src/main/java/com/payrail/backend/outbox/OutboxScheduler.java` — polling opt-in.
- `backend/src/main/java/com/payrail/backend/outbox/OutboxSchedulingConfig.java` — scheduling Spring.
- `backend/src/main/java/com/payrail/backend/settlement/SettlementTransport.java` — contrat externe testable.
- `backend/src/main/java/com/payrail/backend/settlement/SimulatorSettlementClient.java` — client HTTP Spring RestClient, timeouts bornés.
- `backend/src/test/java/com/payrail/backend/OutboxWorkerIntegrationTest.java` — Testcontainers PostgreSQL + transport simulé.
- `backend/src/test/java/com/payrail/backend/SimulatorSettlementClientContractTest.java` — vrai HTTP local et contrôle du payload.
- `docker-compose.worker.yml` — activation du worker uniquement lorsque le simulateur est lancé.

## Vérification PowerShell

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
git status --short
git rev-parse --short HEAD
# Vérifier d'abord la base 0e67fa3 et l'absence de modifications à préserver.
# Extraire ensuite le ZIP directement à la racine PayRail.
cd backend
mvn -B -ntp clean verify
```

La suite M7 comportait 37 tests. M8 en ajoute (le nombre final exact doit venir de Maven).
Ne valider aucun résultat sans logs réels. **Aucun test Maven n'a été exécuté par l'assistant dans cet environnement.**

## Local Docker — un seul checkpoint, après tests

Ne déployer nulle part. Avec le `.env` local déjà utilisé pour M6 :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
docker compose -f docker-compose.yml -f docker-compose.demo.yml -f docker-compose.simulator.yml -f docker-compose.worker.yml up --build -d
docker compose -f docker-compose.yml -f docker-compose.demo.yml -f docker-compose.simulator.yml -f docker-compose.worker.yml ps
```

L'outbox existante a `mode=SUCCESS` par défaut. Les scénarios de défaillance interactifs seront pilotés par le Reliability Lab (M18) ; ne pas exposer de mode arbitraire via une API CLIENT.

**Configuration par défaut : scheduler désactivé.** L'overlay le rend actif. Les tests peuvent appeler `OutboxProcessor.processOne()` directement, ce qui les rend déterministes.

Variables facultatives (mapping Spring Boot relaxed binding) :

- `PAYRAIL_OUTBOX_WORKER_ENABLED=true`
- `PAYRAIL_OUTBOX_WORKER_POLL_MS=1000`
- `PAYRAIL_OUTBOX_WORKER_LEASE_SECONDS=30`
- `PAYRAIL_OUTBOX_WORKER_MAX_ATTEMPTS=8`
- `PAYRAIL_OUTBOX_WORKER_BASE_BACKOFF_SECONDS=2`
- `PAYRAIL_OUTBOX_WORKER_MAX_BACKOFF_SECONDS=60`
- `PAYRAIL_SIMULATOR_BASE_URL=http://simulator:8081` (Docker) ou `http://127.0.0.1:8081` (local)
- `PAYRAIL_SIMULATOR_CONNECT_TIMEOUT_MS=1500`
- `PAYRAIL_SIMULATOR_READ_TIMEOUT_MS=1000`

## Invariants / limites

- **Aucun appel réseau dans une transaction DB.**
- Pas de second débit client : un retry republie seulement le *même* settlement request ID.
- At-least-once : une exécution HTTP peut être répétée après crash, donc la persistance du simulateur M6 fournit l'idempotence externe.
- Les `settlement_attempts` avec `IN_FLIGHT` et un bail expiré sont une preuve d'incertitude, pas nécessairement d'échec.
- Après `DEAD_LETTER`, aucune compensation automatique : investigation M10/M13 et résolution manuelle à prévoir.
- `PROCESSED` signifie **dispatch confirmé**, pas « transfert financier finalisé ». M9 doit consommer les reçus durables.
- Si un délai réseau dépasse le lease, un deuxième worker peut tenter le même règlement ; stable ID + fencing rendent l'opération sûre, pas *exactly-once*.
- Tests externes réels (simulateur M6 via Docker, outage/restart) restent à exécuter ultérieurement ; les tests de transport actuels n'en sont pas un substitut.

## Checkpoint de validation

1. `mvn clean verify` backend : V1–V6, ancien code, tests M8, sécurité, rollback.
2. Vérifier les cas timeout → retry avec même ID ; `SETTLED` → receipt durable ; `REJECTED` → receipt durable mais pas de compensation en M8 ; `DEAD_LETTER` → pas de refund.
3. Vérifier les preuves PostgreSQL de claim/lease/skip-locked et l'audit d'une tentative `IN_FLIGHT` après crash.
4. `git diff --check`, `git status --short`, `git diff --stat`, puis commit uniquement si tout est vert.
5. `git push origin main` et confirmer la CI Actions pour le nouveau commit.
