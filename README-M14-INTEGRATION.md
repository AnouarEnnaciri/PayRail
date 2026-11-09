# PayRail M14 — Crash & Restart Recovery

## Source de vérité et point de départ

- Base GitHub `main` **de587c2** (M13, CI GitHub réussie).
- Architecture et blueprint PayRail inchangés : modular monolith, PostgreSQL 17, Java 17, Spring Boot 4.1.1, Testcontainers.
- Le simulateur et les effets du ledger ne sont **pas** modifiés par M14.
- Les scénarios automatisés utilisent PostgreSQL réel avec Testcontainers et **des pannes simulées de processus**. Un test ne tue pas effectivement une JVM; la validation par kill/restart du vrai service est un exercice manuel distinct.

## Ajouts et modifications

Nouveaux fichiers :
1. `backend/src/main/resources/db/migration/V12__outbox_lease_recovery_audit.sql`
2. `backend/src/test/java/com/payrail/backend/M14CrashRestartIntegrationTest.java`
3. `apply-m14.ps1`
4. `README-M14-INTEGRATION.md`

Le script met à jour **exactement deux fichiers existants** (avec contrôle d'ancres et du commit) :
1. `backend/src/main/java/com/payrail/backend/outbox/OutboxStore.java`
2. `backend/src/test/java/com/payrail/backend/OutboxWorkerIntegrationTest.java`

Le test M8 existant attendait une ancienne tentative durablement `IN_FLIGHT` après la reprise; M14 enregistre correctement son issue technique `LEASE_EXPIRED`.

## Fonctionnement

Le traitement outbox se déroule en transactions distinctes :

1. Le worker revendique un événement (`PROCESSING`, `locked_by`, `locked_until`, `attempt_count`) et **commit**.
2. Le worker contacte le simulateur **sans garder de transaction PostgreSQL ouverte**.
3. Le worker valide le reçu externe et, dans une seconde transaction, clôt le job et applique l'effet financier atomique unique.

Si le worker meurt après 1 ou 2 :

- Le lease finit par expirer.
- Un autre worker réutilise l'**identique `settlement_request_id`**.
- Dès la récupération, l'ancienne tentative `IN_FLIGHT` est auditée `LEASE_EXPIRED`.
- Si le budget d'essais est épuisé, la dernière tentative est auditée `LEASE_EXPIRED` ; l'outbox devient `DEAD_LETTER` et le transfert devient `RECONCILIATION_REQUIRED`.
- `LEASE_EXPIRED` n'est **ni `REJECTED` ni `FAILED`**. Le simulateur peut avoir traité le règlement pendant le crash.
- Les fences empêchent un ancien worker de clôturer le job après qu'un autre l'a repris.
- La résolution manuelle OPS (M13) requiert de nouveau une preuve externe `SETTLED`/`REJECTED` valide avant de poster le moindre effet financier.

La migration V12 conserve tous les états `settlement_attempts.outcome` de V8, y compris `NOT_FOUND`, et ajoute `LEASE_EXPIRED`.

## Scénarios automatisés (7 tests)

1. Crash après claim avant HTTP ; worker recréé, lease expiré, reprise sans deuxième réservation.
2. Succès externe puis crash avant commit local ; même request ID au retour, une seule finalisation.
3. Ancien worker refusé après reprise par un nouveau ; une seule compensation sur rejet vérifié.
4. Timeout et `NOT_FOUND` ; nouveau worker récupère le même ID et finalise uniquement sur `SETTLED`.
5. Dernier lease expiré ; `DEAD_LETTER` + `RECONCILIATION_REQUIRED`, aucun remboursement supposé.
6. Après un dead letter, OPS re-vérifie `SETTLED` et résout une issue M13 avec audit et idempotence.
7. Après un dead letter, le `404` reste inconnu ; pas de finalisation ni de compensation.

Les tests emploient des réponses de simulateur contrôlées via Mockito. Ils vérifient le **vrai SQL PostgreSQL**, les transactions Spring et le ledger : ce ne sont pas des preuves de disponibilité du réseau ou de redémarrage physique du système.

## Installation — PowerShell

**Depuis une copie propre au commit `de587c2`** :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail

git status --short
git rev-parse --short HEAD

Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\PayRail-M14.zip" -DestinationPath . -Force
.\apply-m14.ps1

git status --short
git diff --check
```

La sortie doit montrer V12, le test M14 et les deux fichiers existants modifiés. Ne pas relancer `apply-m14.ps1` deux fois : il refuse un deuxième passage.

## Checkpoints de validation

**Tests ciblés backend** :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail\backend
.\mvnw.cmd "-Dtest=M14CrashRestartIntegrationTest,OutboxWorkerIntegrationTest,ExternalSettlementFinalizationIntegrationTest,M13FinancialReconciliationIntegrationTest,M12CallbackIntegrationTest" test
```

**Suite complète backend**, après les tests ciblés :

```powershell
.\mvnw.cmd clean test
```

**Suite simulateur** :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail\settlement-simulator
mvn clean test
```

N'effectuer le commit **qu'après** `BUILD SUCCESS` des deux suites complètes.

## Validation manuelle d'un vrai redémarrage backend (distincte)

Le test de redémarrage réel sera effectué dans l'environnement de démonstration **local uniquement**, après validation automatique. Ne pas injecter les pannes dans une base contenant des données de valeur ou appartenant à des tiers.

1. Démarrer PostgreSQL et le simulateur avec la configuration Docker locale déjà opérationnelle ; activer le worker backend `payrail.outbox.worker.enabled=true`.
2. Créer un transfert externe de démonstration avec `settlement_request_id` connu ; noter son état, son outbox et son solde.
3. Interrompre le processus Java du backend (Ctrl+C ou arrêt forcé contrôlé) **après qu'un job a été revendiqué** : suivre `outbox_events.status`, `locked_until` et `settlement_attempts` pour le confirmer. Sinon, l'essai n'a pas réellement injecté la panne souhaitée.
4. Redémarrer le backend sur la **même base PostgreSQL et le même simulateur** ; après expiration du lease, vérifier le rejeu avec le même ID et la clôture éventuelle.
5. Vérifier les invariants : `EXTERNAL_RESERVATION = 1`, effet terminal `<= 1`, compensation jamais sans rejet définitif, somme du ledger par transaction = 0, solde client jamais débité deux fois.
6. Conserver les logs avant/après (sans secrets) et les résultats du run de réconciliation dans un dossier `docs/reliability/` si cette démonstration est exécutée.

**Important :** ce runbook est une procédure, pas une preuve déjà acquise. Si un redémarrage réel n'a pas été réalisé, documenter « simulation automatisée validée ; test processus réel non encore réalisé ».

## État du milestone

**À l'intégration :** aucune réussite Maven M14 annoncée. Les validations doivent venir des sorties de tests, puis de GitHub Actions.

Commit recommandé après validation :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
git add README-M14-INTEGRATION.md apply-m14.ps1 backend/src/main/resources/db/migration/V12__outbox_lease_recovery_audit.sql backend/src/test/java/com/payrail/backend/M14CrashRestartIntegrationTest.java backend/src/main/java/com/payrail/backend/outbox/OutboxStore.java backend/src/test/java/com/payrail/backend/OutboxWorkerIntegrationTest.java
git diff --cached --check
git diff --cached --stat
git commit -m "feat: audit des leases expirés et reprise après crash M14"
git push origin main
```
