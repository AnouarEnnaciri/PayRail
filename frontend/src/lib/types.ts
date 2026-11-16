export type Role = 'CLIENT' | 'OPS'
export interface Session { username: string; role: Role; csrfHeaderName: string; csrfToken: string }
export interface Account { id: string; reference: string; balanceMinor: number; currency: string }
export interface Beneficiary { id: string; reference: string }
export interface Transfer {
  id: string; senderAccountId: string; receiverAccountId: string | null
  senderReference: string; receiverReference: string | null; destinationReference: string | null
  settlementRequestId: string | null; amountMinor: number; transferType: 'INTERNAL' | 'EXTERNAL'
  status: string; createdAt: string
}
export interface LedgerEntry {
  id: string; ledgerTransactionId: string; transferId: string | null; effectType: string
  signedAmountMinor: number; currency: string; createdAt: string; fundingReference: string | null
}
export interface Overview {
  transfers: number; internalCompleted: number; externalSettled: number; externalPending: number
  requiresReconciliation: number; failed: number; outboxPending: number; outboxProcessing: number
  deadLetters: number; openIssues: number; ledgerTransactions: number; clearingMinor: number
}
export interface Outbox {
  id: string; transferId: string; status: string; attemptCount: number
  availableAt: string; createdAt: string; processedAt: string | null; lockedUntil: string | null; hasError: boolean
}
export interface Attempt {
  id: string; transferId: string; settlementRequestId: string; attemptNumber: number
  outcome: string; httpStatus: number | null; errorCategory: string | null
  latencyMs: number | null; startedAt: string; completedAt: string | null
}
export interface Callback {
  eventId: string; transferId: string; settlementRequestId: string
  externalStatus: string; disposition: string; receivedAt: string
}
export interface Run {
  id: string; startedAt: string; completedAt: string; status: string
  triggeredBy: string; checksPerformed: number; externalChecked: number; issuesFound: number
}
export interface Issue {
  id: string; runId: string; issueType: string; resourceType: string; resourceId: string
  expectedState: string; observedState: string; detail: string; status: string
  detectedAt: string; resolvedAt: string | null; resolutionAction: string | null
  resolutionActor: string | null; evidenceReference: string | null
}
export interface TimelineEvent { id: string; type: string; title: string; detail: string; at: string }
export interface ActuatorMetric {
  name: string; description?: string; baseUnit?: string
  measurements: { statistic: string; value: number }[]
  availableTags?: { tag: string; values: string[] }[]
}
export type View = 'overview' | 'accounts' | 'transfers' | 'ledger' | 'activity' |
  'ops-overview' | 'ops-transfers' | 'outbox' | 'reconciliation' | 'reliability' | 'metrics' | 'detail'
