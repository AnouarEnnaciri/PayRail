# PayRail M12 — Callbacks signés & doublons

Base vérifiée : commit **2eb08ea** (M11). Ce lot ne doit pas être appliqué à un autre HEAD.

## Périmètre

- Endpoint backend `POST /api/settlement/callbacks`, accessible sans Basic auth **mais exclusivement avec une signature HMAC-SHA256 valide**.
- Endpoint désactivé de façon sûre sans la variable `PAYRAIL_CALLBACK_HMAC_SECRET` (HTTP 503).
- Un `eventId` durable identifie les livraisons dupliquées. Même `eventId` + autre payload : HTTP 409.
- Transaction PostgreSQL unique : acquisition du verrou outbox, vérification du transfert, finalisation ledger M9, acquittement outbox, et insertion de la preuve callback.
- Déduplication des événements avec ids identiques **et différents** : au plus une finalisation financière par transfert.
- Si callback arrive pendant un `submit` worker, le worker n'exécute pas un second effet financier ; son attempt est conservé pour audit.
- Mode `DUPLICATE_CALLBACK` du simulateur : **deux notifications identiques après le commit externe**, avec le même eventId et la même signature, seulement si configuré.

## Déploiement / secrets

**Ne jamais commiter un vrai secret.** Pour utiliser réellement le mode, fournir les mêmes valeurs via variables d'environnement/secrets à chaque conteneur :

- Backend : `PAYRAIL_CALLBACK_HMAC_SECRET` (clé longue et aléatoire).
- Simulateur : `SIMULATOR_CALLBACK_HMAC_SECRET` (même valeur) et `SIMULATOR_CALLBACK_URL` (URL interne joignable par le conteneur simulateur, par exemple `http://backend:8080/api/settlement/callbacks` selon le réseau Compose effectivement utilisé).

Sans ces variables, le mode normal M6–M11 reste inchangé ; le simulateur n'envoie aucun callback. Le backend ne traite aucun callback non authentifié.

Le JSON contient `eventId`, `settlementRequestId`, `amountMinor`, `destinationReference`, `status` (`SETTLED`/`REJECTED`) et `externalReference` (obligatoire pour `SETTLED`). La signature `X-PayRail-Signature` est `sha256=<hex HMAC-SHA256>` calculée sur les champs canoniques, pas sur la représentation textuelle arbitraire du JSON. La canonicalisation est implémentée des deux côtés et couverte par les tests.

**Limite explicite :** le simulateur émet ces callbacks en best effort, sans file de retry de callbacks persistants. Son API GET et la récupération du worker continuent de fournir la source de vérité. M12 ne prétend pas fournir du *exactly-once delivery* réseau ni un système de webhooks commercial.

## Installation (PowerShell)

Depuis la racine `C:\Users\hp\IdeaProjects\PayRail`, sur `main` propre au commit `2eb08ea` :

```powershell
cd C:\Users\hp\IdeaProjects\PayRail
git status --short
git rev-parse --short HEAD
Expand-Archive -LiteralPath "$env:USERPROFILE\Downloads\PayRail-M12.zip" -DestinationPath . -Force
.\apply-m12.ps1
git diff --check
git status --short
```

## Checkpoints de validation

Docker Desktop doit tourner pour Testcontainers.

```powershell
cd C:\Users\hp\IdeaProjects\PayRail\backend
.\mvnw.cmd "-Dtest=M12CallbackIntegrationTest,M11StateMachineIntegrationTest,M10UncertainOutcomeIntegrationTest,ExternalSettlementFinalizationIntegrationTest,OutboxWorkerIntegrationTest" test
```

```powershell
cd C:\Users\hp\IdeaProjects\PayRail\settlement-simulator
mvn "-Dtest=SettlementCallbackPublisherTest,SettlementSimulatorIntegrationTest" test
```

**Après** ces tests ciblés, lancer les deux suites complètes `clean verify`. Ne commit/push qu'après la validation réelle.

## Invariants et risques

- Seul un statut terminal signé et cohérent avec `settlement_request_id`, montant et destination peut provoquer un effet financier.
- 404, timeout et duplications ne deviennent jamais un `FAILED` sans rejet définitif.
- `uq_external_terminal_effect_per_transfer` (V7) protège l'unicité des effets terminaux.
- Le trigger M11 continue de protéger les transitions d'état ; M12 ne le contourne pas.
- Les callbacks ne doivent jamais lancer d'appel réseau dans une transaction de ledger.
- Les réponses callback répétées `DUPLICATE` / `ALREADY_FINALIZED` ne publient pas d'autre ledger.
- Le CSRF reste actif pour les endpoints client ; seule l'URL HMAC du callback en est exemptée.

M13 ajoutera les contrôles systématiques de réconciliation et le workflow OPS audité. PayRail Mastery reste après M19.
