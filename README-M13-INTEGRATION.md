# PayRail M13 — Réconciliation financière

## Point de départ

- GitHub `main` au commit M12 `4998d80` (CI M12 verte, 86 tests backend + 11 simulateur en local).
- Intégration M13 **uniquement après vérification d'un working tree propre**.
- Java 17, Spring Boot 4.1.1, PostgreSQL 17, Flyway et Testcontainers inchangés.
- Aucun changement au simulateur. Aucun nouveau composant externe ou coût cloud.

## Fichiers ajoutés

1. `backend/src/main/resources/db/migration/V11__financial_reconciliation.sql`
2. `backend/src/main/java/com/payrail/backend/reconciliation/ReconciliationService.java`
3. `backend/src/main/java/com/payrail/backend/reconciliation/ReconciliationResolutionService.java`
4. `backend/src/main/java/com/payrail/backend/reconciliation/ReconciliationController.java`
5. `backend/src/test/java/com/payrail/backend/M13FinancialReconciliationIntegrationTest.java`
6. `README-M13-INTEGRATION.md`

Aucun fichier existant n'est écrasé ; les invariants M0–M12 restent en place.

## Fonctionnalités

### Détection seule — sans écriture financière

- Soldes clients matérialisés **vs** sommes du ledger.
- `SUM(signed_amount_minor)` et nombre d'entrées de **chaque** transaction ledger.
- `SETTLEMENT_CLEARING` **vs** montants des transferts externes non résolus.
- Effets financiers attendus (réservation, finalisation, compensation) selon l'état de transfert.
- État PayRail **vs** réponse du participant externe, via le `settlement_request_id` stable.
- Issues persistées, regroupées par `reconciliation_run`, avec observations et statut `OPEN`/`RESOLVED`.

Un `404 NOT_FOUND` externe ne prouve **jamais** un rejet. Pour un transfert `PENDING_SETTLEMENT`, un tel résultat n'entraîne pas de changement d'état.

La lecture locale utilise un snapshot PostgreSQL `REPEATABLE_READ` ; les consultations HTTP ont lieu **après** la fin de cette transaction. La réconciliation est une observation ponctuelle : le simulateur peut évoluer entre la lecture locale et la consultation externe.

### Résolution OPS — autorisation par preuve externe

Uniquement pour les issues :
- `EXTERNAL_SETTLED_INTERNAL_PENDING` → reconsultation externe `SETTLED` vérifiée → `SETTLED` + effet ledger final unique.
- `EXTERNAL_REJECTED_INTERNAL_PENDING` → reconsultation externe `REJECTED` vérifiée → `FAILED` + compensation unique.

La résolution :
1. Relit **en direct** le statut externe **avant** de commencer une transaction DB.
2. Vérifie identité, montant, destination, référence externe et résultat terminal.
3. Verrouille l'outbox, puis l'issue, puis le transfert (ordre compatible avec les callbacks M12).
4. Réutilise `ExternalSettlementFinalizationService` M9/M11 et ses invariants.
5. Marque l'outbox `PROCESSED` et l'issue `RESOLVED`, en enregistrant l'acteur, l'action, l'heure et l'identité de la preuve **dans la même transaction financière**.
6. Une résolution répétée renvoie `ALREADY_RESOLVED` et n'écrit jamais deux effets financiers.

Les écarts de soldes, de ledger ou de clearing restent **détectés mais non réparés automatiquement**. Ils nécessitent une investigation manuelle distincte.

### Protection et limites

- Routes `/api/ops/**` déjà protégées par `ROLE_OPS` dans `SecurityConfig`.
- CSRF reste activé pour les POST OPS, aucun assouplissement supplémentaire.
- Le run inspecte au maximum **200** transferts externes par défaut (`payrail.reconciliation.max-external-checks`, de 1 à 10000). Si la limite est dépassée, le run est `PARTIAL` et crée une issue `SCAN_LIMIT_EXCEEDED`. Ne jamais interpréter ce run comme complet.
- En cas d'indisponibilité du simulateur, la détection relève `UNKNOWN_SETTLEMENT_STATE`. **Aucune** finalisation sur base d'un timeout ou d'un 404.
- Les issues sont conservées comme preuves historiques, même si un nouveau run est lancé ; elles ne sont jamais supprimées silencieusement.

## Endpoints (OPS)

| Méthode | Chemin | Fonction |
|---|---|---|
| `POST` | `/api/ops/reconciliation/runs` | Nouveau run ; HTTP 201 |
| `GET` | `/api/ops/reconciliation/runs/{runId}` | Rapport et compteurs |
| `GET` | `/api/ops/reconciliation/runs/{runId}/issues` | Liste des anomalies persistées |
| `POST` | `/api/ops/reconciliation/issues/{issueId}/resolve` | Résolution explicite sur preuve externe actuelle |

L'acteur vient du `Principal` authentifié, jamais d'un champ envoyé par le client. Il n'existe **aucun** endpoint générique « forcer FAILED » ou « rembourser ».

## Intégration PowerShell

```powershell
cd C:\Users\hp\IdeaProjects\PayRail

git status --short
git rev-parse --short HEAD

Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\PayRail-M13.zip" -DestinationPath . -Force

git status --short
git diff --check
```

Le commit de départ attendu est `4998d80`. Après extraction, seuls les 6 **nouveaux fichiers** ci-dessus doivent apparaître.

## Tests ciblés (backend)

```powershell
cd C:\Users\hp\IdeaProjects\PayRail\backend
.\mvnw.cmd "-Dtest=M13FinancialReconciliationIntegrationTest,M12CallbackIntegrationTest,M11StateMachineIntegrationTest,ExternalSettlementFinalizationIntegrationTest" test
```

Tests M13 : détection soldes/ledger/clearing, external SETTLED/REJECTED, revalidation après 404, preuve incohérente, reprise d'un dead-letter, idempotence, audit, OPS/CSRF, absence de transaction DB lors des GET externes.

Puis, si la cible réussit :

```powershell
.\mvnw.cmd clean verify
cd C:\Users\hp\IdeaProjects\PayRail\settlement-simulator
mvn clean verify
```

**Important :** ce lot a été assemblé et son code Java de production vérifié avec un contrôle de types local utilisant des stubs Java. Les vrais tests Spring/Maven/Testcontainers **n'ont pas été exécutés** lors de sa préparation : ils doivent passer sur ta machine avant le commit.

## Après validation uniquement

```powershell
cd C:\Users\hp\IdeaProjects\PayRail

git add README-M13-INTEGRATION.md
git add backend/src/main/resources/db/migration/V11__financial_reconciliation.sql
git add backend/src/main/java/com/payrail/backend/reconciliation/
git add backend/src/test/java/com/payrail/backend/M13FinancialReconciliationIntegrationTest.java

git diff --cached --check
git diff --cached --stat
git commit -m "feat: ajout de la réconciliation financière et des résolutions sécurisées M13"
git push origin main
git status --short
```

Vérifier GitHub Actions `Success` avant de déclarer M13 terminé et de passer à M14.
