# PayRail — Reliable Transaction Processing & Settlement

**Java 17 · Spring Boot 4.1.1 · PostgreSQL 17 · React / TypeScript · Docker · GitHub Actions**

PayRail is a student project built with Java and Spring Boot to explore how payment transfers behave when databases, workers, or external services fail. It uses a double-entry ledger, transactional idempotency, concurrency control, an outbox worker, a settlement simulator, signed callbacks, and reconciliation.

**[Explore the interactive engineering guide](https://anouarennaciri.github.io/PayRail/)** · [Backend CI](https://github.com/AnouarEnnaciri/PayRail/actions/workflows/ci.yml) · [Security CI](https://github.com/AnouarEnnaciri/PayRail/actions/workflows/security.yml) · [Frontend CI](https://github.com/AnouarEnnaciri/PayRail/actions/workflows/frontend-ci.yml)

> **Deployment status:** The engineering guide is live on GitHub Pages. The backend and settlement simulator images are published to GitHub Container Registry. Azure infrastructure is managed with committed Terraform configuration, and the Azure resource group has been provisioned. The React/Spring Boot application and PostgreSQL databases are not yet publicly hosted.

## Application preview

![Actual local PayRail React portal](public-demo/images/client-dashboard.png)

*Real screenshot from the local React portal. It is not a hosted application demo.*

## What PayRail solves

An external API timeout is an **unknown outcome**, not proof that the transaction failed. Refunds based on guessed outcomes can create or destroy money. PayRail records uncertainty explicitly, retries or looks up settlement using stable request identifiers, and reconciles external evidence before financial finalization or compensation.

The main engineering guarantees are:

| Guarantee | Implementation |
| --- | --- |
| Double-entry accounting | Balanced ledger postings, financial constraints, and amounts stored in integer minor units |
| Atomic internal transfers | Account locking in deterministic UUID order; debit, credit, transfer and ledger posting in one transaction |
| Idempotent requests | Actor + idempotency key + SHA-256 request hash; repeated identical requests reuse the existing transfer, conflicting payloads are rejected |
| Reliable dispatch | Transactional outbox, worker leases, retry logic, and recovery from interrupted processing |
| Uncertain external outcomes | Timeout / transient failure does not automatically mean rejection; terminal effects follow verified evidence |
| Exactly one terminal ledger effect | PostgreSQL partial unique index for settlement finalization vs compensation |
| Callback authenticity | HMAC-SHA256 verification and callback event deduplication |
| Reconciliation | Detect-only financial checks; separate audited operations for repair |

These properties are supported by the application code, database migrations, and integration tests.

## Architecture

```text
React / TypeScript portal (CLIENT / OPS)
                |
                v
        Spring Boot API
     (modular monolith)
                |
      +---------+---------+
      |                   |
PostgreSQL 17        Outbox worker
accounts, ledger,         |
transfers, outbox         v
                  Settlement simulator
                          |
                   Separate PostgreSQL
                          |
                    Signed callback
                          |
                    Spring Boot API
```

Core backend modules are under `backend/src/main/java/com/payrail/backend/`: `account`, `api`, `ledger`, `transfer`, `outbox`, `settlement`, `reconciliation`, `security` and `observability`.

The simulator is a **separate application with its own database**. The outbox worker runs within the Spring Boot backend when enabled; this is **not** a microservices architecture.

Learn the flows visually in the [Architecture and Reliability Lab](https://anouarennaciri.github.io/PayRail/#architecture).

## Technology and delivery

| Area | Stack |
| --- | --- |
| Backend | Java 17, Spring Boot 4.1.1, Maven, Spring Security, Spring Data JPA |
| Data | PostgreSQL 17, Flyway |
| Frontend | React 19, TypeScript, Vite |
| Testing | JUnit, Spring Boot Test, Testcontainers, PostgreSQL, k6 scripts |
| Containers | Docker multi-stage builds, non-root runtime users, Docker Compose |
| CI | GitHub Actions: backend / simulator Maven verification, container builds, frontend type-check and build |
| DevSecOps | Trivy HIGH/CRITICAL fixable-image vulnerability gates, CodeQL Java / TypeScript, dependency review on pull requests |
| Observability | Structured JSON logging, correlation IDs, Micrometer / Actuator |
| Public documentation | Static HTML/CSS/JS guide deployed with GitHub Pages Actions |
| Cloud / IaC | Terraform AzureRM configuration committed; Azure resource group provisioned; images published to GHCR; app and databases not yet hosted |

**Delivery:** GitHub Actions deploys the static engineering guide and publishes backend/simulator container images to GHCR. Application deployment to Azure Container Apps is in progress.

## Run locally (Windows / PowerShell)

**Prerequisites:** Docker Desktop with Docker Compose, Git, Node.js/npm for the portal, and available ports 5433, 8080, 8081 and 5173. The full Docker setup builds Java services inside their Dockerfiles; a host Java/Maven install is needed only for standalone Maven commands.

1. Clone the repository and open its root:

   ```powershell
   git clone https://github.com/AnouarEnnaciri/PayRail.git
   cd PayRail
   ```

2. Create a **local, untracked** `.env` file at the repository root. Supply your own non-production values for these environment variables (do **not** commit the file or share credentials):

   ```dotenv
   PAYRAIL_DB_NAME=payrail
   PAYRAIL_DB_USER=payrail
   PAYRAIL_DB_PASSWORD=<choose-a-local-database-password>
   PAYRAIL_DEMO_CLIENT_ALPHA_PASSWORD=<choose-a-local-client-password>
   PAYRAIL_DEMO_CLIENT_BETA_PASSWORD=<choose-another-local-client-password>
   PAYRAIL_DEMO_OPS_PASSWORD=<choose-a-local-ops-password>
   ```

   Replace the placeholders **including angle brackets** with unique secret values. Additional environment variables may be required for specific security-sensitive callback and settlement scenarios; check the backend configuration and tests before exercising those paths.

3. Launch the complete demo backend, independent simulator database, and outbox worker:

   ```powershell
   docker compose -f docker-compose.yml -f docker-compose.demo.yml -f docker-compose.simulator.yml -f docker-compose.worker.yml up -d --build
   docker compose -f docker-compose.yml -f docker-compose.demo.yml -f docker-compose.simulator.yml -f docker-compose.worker.yml ps
   ```

   The backend exposes `http://127.0.0.1:8080/actuator/health`. In the demo profile, Swagger UI is available at `http://127.0.0.1:8080/swagger-ui.html`.

4. In a **second PowerShell terminal**, start the React portal:

   ```powershell
   cd frontend
   npm ci
   npm run dev
   ```

   Open `http://127.0.0.1:5173`. Vite proxies `/api` and `/actuator` to the local backend to preserve same-origin browser behavior. Demo users are `client.alpha@example.test`, `client.beta@example.test`, and `ops@example.test`, using the passwords **you defined locally**.

5. Stop the services without deleting database volumes:

   ```powershell
   docker compose -f docker-compose.yml -f docker-compose.demo.yml -f docker-compose.simulator.yml -f docker-compose.worker.yml down
   ```

**Local security boundary:** Demo application ports and the base PostgreSQL port mapping bind to `127.0.0.1`. Keep the settlement simulator's fault-injection endpoints private and use non-production demo credentials.

## Tests and evidence

Backend integration tests run against PostgreSQL through Testcontainers, rather than replacing database semantics with an in-memory substitute. Representative tests include:

- [IdempotentTransferServiceTest](backend/src/test/java/com/payrail/backend/IdempotentTransferServiceTest.java)
- [ConcurrentInternalTransferTest](backend/src/test/java/com/payrail/backend/ConcurrentInternalTransferTest.java)
- [ExternalSettlementFinalizationIntegrationTest](backend/src/test/java/com/payrail/backend/ExternalSettlementFinalizationIntegrationTest.java)
- [OutboxRecoveryIntegrationTest](backend/src/test/java/com/payrail/backend/OutboxRecoveryIntegrationTest.java)
- [SettlementCallbackIntegrationTest](backend/src/test/java/com/payrail/backend/SettlementCallbackIntegrationTest.java)
- [FinancialReconciliationIntegrationTest](backend/src/test/java/com/payrail/backend/FinancialReconciliationIntegrationTest.java)

Run verification locally with Docker available:

```powershell
cd backend
mvn clean verify
cd ../settlement-simulator
mvn clean verify
```

Use `npm run build` in `frontend/` for TypeScript checking and Vite production bundling.

Additional reproducible artifacts: [Flyway migrations](backend/src/main/resources/db/migration), [k6 scripts](load-tests/k6), [Docker/Compose configuration](docker-compose.demo.yml), and [GitHub Actions workflows](.github/workflows).

**Security gates:** `.github/workflows/ci.yml` verifies both Java projects, builds both images, and scans them with Trivy; `.github/workflows/security.yml` runs CodeQL and pull-request dependency review; `.github/workflows/frontend-ci.yml` builds the frontend when relevant files change. Check the **current workflow run** before claiming a specific commit is green.

## Financial behavior and limitations

- Internal transfer: atomically posts a debit and a matching credit, updates balances, and records its transfer status.
- External reservation: posts customer-to-clearing movement and stores an outbox event in the same database transaction.
- Settlement success / confirmed rejection: results in an evidence-driven finalization or compensation effect.
- Unknown or exhausted outcomes: retain uncertainty for reconciliation rather than inventing a refund.
- Reconciliation is designed to detect mismatches separately from any audited repair.
