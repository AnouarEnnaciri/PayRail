export const money = (minor: number, currency = 'MAD') => new Intl.NumberFormat('fr-MA', {
  style: 'currency', currency, minimumFractionDigits: 2, maximumFractionDigits: 2,
}).format(minor / 100)
export const number = (n: number) => new Intl.NumberFormat('fr-MA').format(n)
export const moment = (date?: string | null) => date ? new Intl.DateTimeFormat('fr-FR', {
  dateStyle: 'medium', timeStyle: 'short',
}).format(new Date(date)) : '—'
export const clock = (date?: string | null) => date ? new Intl.DateTimeFormat('fr-FR', {
  hour: '2-digit', minute: '2-digit', second: '2-digit',
}).format(new Date(date)) : '—'
export const shortId = (id?: string | null) => id ? `${id.slice(0, 8)}…${id.slice(-5)}` : '—'
const labels: Record<string, string> = {
  ACTIVE: 'Actif', INTERNAL: 'Interne', EXTERNAL: 'Externe', COMPLETED: 'Terminé', SETTLED: 'Réglé',
  PROCESSED: 'Traité', RESOLVED: 'Résolu', FINALIZED: 'Finalisé', UP: 'Disponible', SUCCESS: 'Réussi',
  FAILED: 'Échoué', DEAD_LETTER: 'Échec définitif', REJECTED: 'Rejeté', ERROR: 'Erreur',
  LEASE_EXPIRED: 'Verrou expiré', NETWORK_ERROR: 'Erreur réseau', INVALID_RESPONSE: 'Réponse invalide', HTTP_ERROR: 'Erreur HTTP',
  PENDING: 'En attente', PENDING_SETTLEMENT: 'Règlement en attente', RECONCILIATION_REQUIRED: 'Rapprochement requis',
  IN_FLIGHT: 'En cours', PROCESSING: 'En traitement', OPEN: 'Ouvert', PARTIAL: 'Partiel', NOT_FOUND: 'Introuvable',
  FUNDING: 'Dotation', INITIAL_FUNDING: 'Dotation initiale', TRANSFER: 'Transfert', RESERVATION: 'Réservation',
  SETTLEMENT: 'Règlement', COMPENSATION: 'Compensation', RELEASE: 'Restitution', DEBIT: 'Débit', CREDIT: 'Crédit',
  IDEMPOTENCY: 'Idempotence', DEMO_FUNDING: 'Financement de démonstration',
  UNKNOWN_SETTLEMENT_STATE: 'Résultat du règlement inconnu',
  STATUS_LOOKUP_NETWORK_OR_TIMEOUT: 'Erreur réseau ou délai dépassé lors de la vérification du règlement',
  EXTERNAL_SETTLEMENT_FINALIZATION: 'Finalisation du règlement externe',
  LEDGER: 'Écriture comptable', ATTEMPT: 'Tentative', CALLBACK: 'Notification', OUTBOX: 'Tâche',
  EXTERNAL_RESERVATION: 'Réservation externe', EXTERNAL_SETTLEMENT: 'Règlement externe', INTERNAL_TRANSFER: 'Transfert interne',
  EXTERNAL_COMPENSATION: 'Compensation externe', APPLIED: 'Appliqué', DUPLICATE: 'Doublon', IGNORED: 'Ignoré',
  EXTERNAL_SETTLED_INTERNAL_PENDING: 'Règlement externe confirmé, traitement interne en attente',
  EXTERNAL_REJECTED_INTERNAL_PENDING: 'Rejet externe confirmé, traitement interne en attente',
}
export const label = (s?: string | null) => s ? labels[s] ?? s.replaceAll('_', ' ') : '—'
export const toMinor = (input: string): number => {
  const normalized = input.trim().replace(',', '.')
  if (!/^(?:\d+)(?:\.\d{1,2})?$/.test(normalized)) throw new Error('Saisis un montant positif avec au maximum deux décimales.')
  const [whole, fraction = ''] = normalized.split('.')
  const cents = BigInt(whole) * 100n + BigInt((fraction + '00').slice(0, 2))
  if (cents < 1n || cents > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('Montant hors limites.')
  return Number(cents)
}

// Traduction de présentation uniquement : les valeurs de l'API restent inchangées.
const timelineTitles: Record<string, string> = {
  'Idempotency record': 'Demande idempotente enregistrée',
  'Outbox event committed': 'Demande de règlement enregistrée',
  'Transfer recorded': 'Transfert enregistré',
  'EXTERNAL RESERVATION': 'Réservation des fonds',
  'EXTERNAL SETTLEMENT FINALIZATION': 'Finalisation du règlement externe',
  'EXTERNAL COMPENSATION': 'Compensation du règlement externe',
  'INTERNAL TRANSFER': 'Transfert interne',
  'Outbox event acknowledged': 'Traitement de la tâche confirmé',
}

const timelineDescriptions: Record<string, string> = {
  'Request key persisted in PostgreSQL': "Clé d'idempotence enregistrée dans PostgreSQL",
  'Durable settlement request': 'Demande de règlement enregistrée durablement',
  'Durable transfer record created': 'Transfert enregistré durablement',
  'Double-entry ledger transaction': 'Transaction comptable en partie double',
  'Dispatch started': "Envoi de la demande démarré",
  'Recorded transport outcome': 'Résultat de la communication réseau enregistré',
  'Processing recorded': 'Traitement enregistré',
}

/** Textes visibles dans la chronologie OPS ; ne modifie jamais les événements reçus. */
export const timelineTitleFr = (value: string): string => {
  const title = value.trim()
  const known = timelineTitles[title]
  if (known) return known

  const started = /^Settlement attempt #(\d+)$/i.exec(title)
  if (started) return `Tentative de règlement n° ${started[1]}`

  const finished = /^Attempt #(\d+)\s*[→:-]\s*([A-Z_]+)$/i.exec(title)
  if (finished) return `Tentative n° ${finished[1]} : ${label(finished[2].toUpperCase())}`

  return value
}

export const timelineDetailFr = (value: string): string =>
  timelineDescriptions[value.trim()] ?? value
