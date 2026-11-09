# PayRail — M11 State Machine Hardening

**Base obligatoire :** commit `8114110` (M10), CI verte. Le ZIP contient des fichiers à copier dans la racine du dépôt PayRail. N'applique pas ce lot sur une branche ayant des changements locaux non commités.

## Objectif

- Centraliser les transitions dans `TransferState` et `Transfer`.
- Bloquer toute transition depuis `COMPLETED`, `SETTLED` et `FAILED`.
- Autoriser `PENDING_SETTLEMENT` vers `SETTLED`, `FAILED` ou `RECONCILIATION_REQUIRED`.
- Autoriser `RECONCILIATION_REQUIRED` vers `SETTLED` ou `FAILED` **uniquement via une finalisation applicative fondée sur une preuve externe terminale**.
- Empêcher les transitions interdites aussi par SQL direct (`V9` trigger).
- Préserver ledger équilibré, débit initial unique et index d'unicité de l'effet financier terminal.

Aucun changement au simulateur, aux contrôleurs ou aux règles de retry M10.

## Fichiers

| Chemin relatif depuis la racine | Action |
| --- | --- |
| `backend/src/main/java/com/payrail/backend/transfer/TransferState.java` | Nouveau : graphe explicite des transitions |
| `backend/src/main/java/com/payrail/backend/transfer/Transfer.java` | Remplacement ciblé : création/transition centralisées, API existante conservée |
| `backend/src/main/java/com/payrail/backend/settlement/ExternalSettlementFinalizationService.java` | Adaptation : finalisation depuis l'état de réconciliation si preuve terminale |
| `backend/src/main/resources/db/migration/V9__transfer_state_machine.sql` | Nouveau : protection PostgreSQL contre transitions SQL illégales |
| `backend/src/test/java/com/payrail/backend/TransferStateMachineTest.java` | Nouveau : tests unitaires légaux/illégaux/terminaux |
| `backend/src/test/java/com/payrail/backend/M11StateMachineIntegrationTest.java` | Nouveau : tests PostgreSQL, récupération, ledger et SQL illégal |
| `README-M11-INTEGRATION.md` | Nouveau : instructions |

## 1 — Préparation PowerShell

```powershell
cd C:\Users\hp\IdeaProjects\PayRail

git status --short
git rev-parse --short HEAD
```

Le répertoire doit être propre et HEAD doit afficher `8114110`. Si ce n'est pas le cas, **arrête et vérifie les changements**.

## 2 — Intégration PowerShell

Télécharge `PayRail-M11.zip` dans `Downloads`, puis :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\PayRail-M11.zip" -DestinationPath . -Force

git status --short
git diff --check
git diff --stat
```

Les warnings `LF/CRLF` sous Windows ne sont pas des erreurs de compilation.

## 3 — Tests ciblés

Docker Desktop doit fonctionner pour Testcontainers.

```powershell
cd C:\Users\hp\IdeaProjects\PayRail\backend
.\mvnw.cmd "-Dtest=TransferStateMachineTest,M11StateMachineIntegrationTest,M10UncertainOutcomeIntegrationTest,ExternalSettlementFinalizationIntegrationTest,OutboxWorkerIntegrationTest" test
```

Transmets `Tests run`, `Failures`, `Errors` et `BUILD SUCCESS` ou `FAILURE`. **Ne commite pas en cas d'échec.**

## 4 — Suites complètes

Une fois les tests ciblés verts :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail\backend
.\mvnw.cmd clean verify

cd ..\settlement-simulator
mvn clean verify
```

Le module `settlement-simulator` ne contient pas `mvnw.cmd`. Son test utilise Maven installé (`mvn`).

## 5 — Commit puis CI

Uniquement après succès des suites :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail

git status --short
git diff --check
git add README-M11-INTEGRATION.md backend/src/main/java/com/payrail/backend/transfer/TransferState.java backend/src/main/java/com/payrail/backend/transfer/Transfer.java backend/src/main/java/com/payrail/backend/settlement/ExternalSettlementFinalizationService.java backend/src/main/resources/db/migration/V9__transfer_state_machine.sql backend/src/test/java/com/payrail/backend/TransferStateMachineTest.java backend/src/test/java/com/payrail/backend/M11StateMachineIntegrationTest.java
git diff --cached --check
git diff --cached --stat
git commit -m "feat: harden transfer state machine transitions M11"
git push origin main
```

Enfin vérifier le workflow GitHub Actions `PayRail CI` sur le nouveau commit. Ne pas déclarer M11 terminé avant CI verte.

## Décisions et limites

- **Pas de migration de colonnes ni de nouvel état :** les cinq états existent déjà.
- Le trigger `V9` empêche une modification SQL de statut contraire au graphe; il ne peut **pas prouver** qu'un reçu externe existe. Cette preuve relève du service applicatif et du futur workflow OPS de M13.
- `ExternalSettlementFinalizationService.apply` reste transactionnel (`Propagation.MANDATORY`); une preuve terminale doit être validée avant de l'appeler dans un workflow de récupération. Les tests M11 utilisent une preuve *simulée en mémoire*; ils ne constituent pas une procédure opérateur prête pour production.
- Une récupération en M11 peut laisser l'outbox `DEAD_LETTER` : le workflow de résolution/traçabilité cohérente sera développé en M13, pas ici.
- `RECONCILIATION_REQUIRED` est **un état d'incertitude**, jamais un remboursement.
- Les tests nouveaux n'ont **pas** été exécutés dans cet environnement : Maven, Docker et Testcontainers n'y sont pas disponibles. Les résultats doivent venir de ta machine et de GitHub Actions.
