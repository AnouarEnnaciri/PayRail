# PayRail M7 — External Reservation & Transactional Outbox

Base vérifiée: GitHub `origin/main` commit `cde87e8eef287536d4c41c8921bf355f76db8ce6`.

## Périmètre
- `transfer_type=EXTERNAL`, recipient local null, destination externe et `settlement_request_id` unique.
- 1 débit client + 1 crédit `SETTLEMENT_CLEARING`, ledger équilibré.
- Outbox `SETTLEMENT_REQUESTED` persisted via JDBC **dans la même transaction**.
- Idempotence sur la table existante `transfer_idempotency` (actor ID = sender account ID), avec hash contenant le type EXTERNAL.
- API démo `POST /api/client/external-transfers` vérifiant ownership et appliquant les règles de sécurité déjà présentes.
- Aucun appel réseau vers le simulateur dans M7: le worker est réservé à M8.

## Important
Le bundle est construit d'après le code **commit énoncé**, pas les modifications non commitées éventuellement présentes sur votre PC. Vérifier `git status` et le dernier commit AVANT de copier. Ne remplacer aucun fichier local contenant des modifications non sauvegardées.

## Installation PowerShell (à la racine PayRail)

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
git status --short
git rev-parse --short HEAD
# Décompresser l'archive ZIP dans ce dossier, en conservant les chemins.
cd backend
mvn clean verify
```

## API demo
`POST /api/client/external-transfers`, header `Idempotency-Key: <unique key>` et body :

```json
{"senderAccountId":"<uuid>","destinationReference":"BANK-DEMO-001","amountMinor":50000}
```
Réponse: `transferId`, `settlementRequestId` et `status=PENDING_SETTLEMENT`.

## Note future (M8 et M18)
Le payload outbox définit `mode=SUCCESS` par défaut. Les modes d'injection d'échec sont déjà supportés par le simulateur mais le pilotage des scénarios sera intégré explicitement au workflow de fiabilité ultérieur. Ne pas relier l'API client à un mode arbitraire non autorisé.

## Checkpoint avant commit
1. `mvn clean verify` complet (Testcontainers + Docker Desktop requis).
2. Vérifier les effets SQL et la détection des duplicates et des conflits.
3. Vérifier l'absence de débit/ledger/outbox/idempotence partielle après rollback.
4. Vérifier la propriété 1 seul débit et 1 seul outbox en concurrence.
5. Commit seulement après suite verte.
