import { useRef, useState } from 'react'
import type { FormEvent } from 'react'
import {
  ArrowLeft, ArrowRight, CheckCircle2, CircleHelp, CreditCard,
  LockKeyhole, RefreshCw, ShieldCheck, Wallet,
} from 'lucide-react'
import type { ApiClient } from '../lib/api'
import { money, number, shortId } from '../lib/format'
import { useQuery } from '../lib/useQuery'
import './OpsFundingExperience.css'

type DemoFundingAccount = { reference: string; balanceMinor: number; status: string }
type FundingResult = {
  requestId: string
  ledgerTransactionId: string
  accountReference: string
  amountMinor: number
  balanceAfterMinor: number
  replayed: boolean
}

const LABELS: Record<string, string> = {
  'DEMO-CLIENT-ALPHA': 'Client Alpha',
  'DEMO-CLIENT-BETA': 'Client Beta',
}
const AMOUNTS = [100, 500, 1000, 5000]

export default function OpsFundingExperience({ api, onBack, onFunded }: {
  api: ApiClient
  onBack: () => void
  onFunded: () => void
}) {
  const accounts = useQuery<DemoFundingAccount[]>(api, '/api/ops/demo-funding/accounts', [])
  const [target, setTarget] = useState('DEMO-CLIENT-ALPHA')
  const [amountMad, setAmountMad] = useState('1000')
  const [reviewing, setReviewing] = useState(false)
  const [requestKey, setRequestKey] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [receipt, setReceipt] = useState<FundingResult | null>(null)
  const submitting = useRef(false)
  const selected = accounts.data.find(a => a.reference === target)
  const mad = Number(amountMad)
  const validAmount = Number.isSafeInteger(mad) && mad >= 1 && mad <= 10000
  const ready = !accounts.loading && !accounts.error && selected?.status === 'ACTIVE' && validAmount

  function clearReview() {
    setReviewing(false)
    setRequestKey(null)
    setError(null)
    setReceipt(null)
  }

  function changeTarget(reference: string) {
    if (busy) return
    setTarget(reference)
    clearReview()
  }

  function changeAmount(next: string) {
    if (busy) return
    setAmountMad(next)
    clearReview()
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current || busy) return
    setError(null)
    if (!ready) {
      setError('Choisissez un compte actif et un montant entier de 1 à 10 000 MAD.')
      return
    }
    if (!reviewing) {
      setReviewing(true)
      return
    }

    const key = requestKey ?? crypto.randomUUID()
    // Keep the key after a network error: a retry cannot credit twice.
    setRequestKey(key)
    submitting.current = true
    setBusy(true)
    try {
      const result = await api.post<FundingResult>('/api/ops/demo-funding',
        { accountReference: target, amountMinor: mad * 100 }, key)
      setReceipt(result)
      setRequestKey(null)
      setReviewing(false)
      accounts.refresh()
      onFunded()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Le financement n’a pas pu être confirmé.')
      // Leave reviewing=true and preserve requestKey to allow safe retry.
    } finally {
      submitting.current = false
      setBusy(false)
    }
  }

  return <div className="pr-funding-page">
    <div className="pr-funding-topline">
      <button type="button" className="pr-funding-back" onClick={onBack}>
        <ArrowLeft size={17}/> Vue d’ensemble
      </button>
      <span className="pr-funding-role"><LockKeyhole size={13}/> ACCÈS OPS · SIMULATION</span>
    </div>

    <header className="pr-funding-header">
      <div className="pr-funding-header-copy">
        <div className="pr-funding-eyebrow">EXPLOITATION / FINANCEMENT</div>
        <h1>Alimenter un compte<span>.</span></h1>
        <p>Ajoutez des fonds fictifs aux comptes de démonstration pour tester de nouveaux transferts.</p>
      </div>
      <div className="pr-funding-header-mark" aria-hidden="true"><Wallet size={33}/></div>
    </header>

    <div className="pr-funding-grid">
      <section className="pr-funding-workspace" aria-label="Nouveau financement">
        <div className="pr-funding-section-heading">
          <span className="pr-funding-step">01</span>
          <div><h2>Choisir un compte</h2><p>Sélectionnez le bénéficiaire du financement.</p></div>
          <button className="pr-funding-refresh" type="button" onClick={accounts.refresh} disabled={busy || accounts.loading} title="Actualiser les soldes">
            <RefreshCw size={17}/> <span>Actualiser</span>
          </button>
        </div>

        {accounts.error && <div className="pr-funding-alert" role="alert">Chargement des comptes impossible : {accounts.error}</div>}
        {accounts.loading && <p className="pr-funding-loading" role="status">Chargement des soldes…</p>}
        <div className="pr-funding-accounts" role="group" aria-label="Compte bénéficiaire">
          {accounts.data.map(account => <button
            key={account.reference}
            type="button"
            className={`pr-funding-account ${target===account.reference?'is-selected':''}`}
            aria-pressed={target===account.reference}
            disabled={busy || account.status!=='ACTIVE'}
            onClick={()=>changeTarget(account.reference)}
          >
            <div className="pr-funding-account-top">
              <span className="pr-funding-avatar">{account.reference==='DEMO-CLIENT-ALPHA'?'A':'B'}</span>
              <span className="pr-funding-account-check"><CheckCircle2 size={18}/></span>
            </div>
            <span className="pr-funding-account-name">{LABELS[account.reference] ?? account.reference}</span>
            <span className="pr-funding-account-caption">Solde disponible</span>
            <strong>{money(account.balanceMinor)}</strong>
            <span className="pr-funding-account-status">{account.status==='ACTIVE'?'● Compte actif':`● ${account.status}`}</span>
          </button>)}
        </div>

        <div className="pr-funding-separator"/>
        <form onSubmit={submit}>
          <div className="pr-funding-section-heading">
            <span className="pr-funding-step">02</span>
            <div><h2>Définir le montant</h2><p>Chaque financement est une opération comptable distincte.</p></div>
          </div>
          <label className="pr-funding-input-label" htmlFor="pr-funding-mad">Montant à créditer</label>
          <div className="pr-funding-amount-input">
            <input id="pr-funding-mad" type="number" inputMode="numeric" min="1" max="10000" step="1"
              value={amountMad} onChange={event=>changeAmount(event.target.value)} disabled={busy}
              aria-describedby="pr-funding-amount-hint"/>
            <span>MAD</span>
          </div>
          <div className="pr-funding-presets" role="group" aria-label="Montants suggérés">
            {AMOUNTS.map(value=><button key={value} type="button" className={amountMad===String(value)?'is-active':''}
              onClick={()=>changeAmount(String(value))} disabled={busy}>
              {number(value)} MAD
            </button>)}
          </div>
          <p className="pr-funding-helper" id="pr-funding-amount-hint">Entre 1 et 10 000 MAD · aucune somme réelle n’est transférée.</p>

          {reviewing && !receipt && <div className="pr-funding-review" role="status">
            <div className="pr-funding-review-title"><ShieldCheck size={18}/> Vérification avant financement</div>
            <div className="pr-funding-review-row"><span>Compte</span><strong>{LABELS[target] ?? target}</strong></div>
            <div className="pr-funding-review-row"><span>Montant</span><strong>+ {number(mad)} MAD</strong></div>
            <p>Vous allez créer une écriture réelle dans le ledger de démonstration. Confirmez uniquement si ces informations sont correctes.</p>
          </div>}

          {error && <div className="pr-funding-alert" role="alert">{error} {requestKey && 'Vous pouvez réessayer : la même clé protège contre un double crédit.'}</div>}
          {receipt && <div className="pr-funding-receipt" role="status">
            <div><CheckCircle2 size={22}/><strong>{receipt.replayed?'Financement déjà enregistré':'Financement confirmé'}</strong></div>
            <p>+ {money(receipt.amountMinor)} crédités à {LABELS[receipt.accountReference] ?? receipt.accountReference}.</p>
            <div className="pr-funding-receipt-bottom"><span>Nouveau solde : <strong>{money(receipt.balanceAfterMinor)}</strong></span><small>Transaction {shortId(receipt.ledgerTransactionId)}</small></div>
          </div>}

          <div className="pr-funding-form-actions">
            {reviewing && <button type="button" className="pr-funding-edit" disabled={busy} onClick={()=>setReviewing(false)}>Modifier</button>}
            <button type="submit" className="pr-funding-submit" disabled={busy || !ready}>
              {busy ? 'Financement en cours…' : reviewing ? 'Confirmer le financement' : 'Vérifier le financement'}
              {!busy && <ArrowRight size={17}/>}
            </button>
          </div>
        </form>
      </section>

      <aside className="pr-funding-aside" aria-label="Informations de financement">
        <div className="pr-funding-aside-eyebrow">COMPTE SÉLECTIONNÉ</div>
        <span className="pr-funding-aside-name">{LABELS[target] ?? target}</span>
        <div className="pr-funding-aside-value">{selected ? money(selected.balanceMinor) : '—'}</div>
        <span className="pr-funding-aside-description">Solde actuel observé</span>
        <div className="pr-funding-aside-rule"/>
        <div className="pr-funding-aside-summary">
          <span>Financement prévu</span><strong>{validAmount?`+ ${number(mad)} MAD`:'—'}</strong>
        </div>
        <p className="pr-funding-aside-tip"><CircleHelp size={16}/> Le solde ne changera qu’après confirmation du serveur.</p>
        <div className="pr-funding-assurance">
          <span><ShieldCheck size={17}/> Double écriture comptable</span>
          <span><LockKeyhole size={17}/> Idempotence des demandes</span>
          <span><CreditCard size={17}/> Argent fictif uniquement</span>
        </div>
      </aside>
    </div>
    <div className="pr-funding-footnote">PayRail · Environnement de démonstration · Accès réservé aux opérateurs autorisés.</div>
  </div>
}
