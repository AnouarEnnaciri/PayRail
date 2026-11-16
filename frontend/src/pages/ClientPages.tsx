import { useEffect, useState, type FormEvent } from 'react'
import { ArrowDownLeft, ArrowLeftRight, ArrowRight, ArrowUpRight, BookOpenText, CheckCircle2, CreditCard, Landmark, Layers3, Plus, Send, ShieldCheck, Sparkles, Wallet } from 'lucide-react'
import type { ApiClient } from '../lib/api'
import type { Account, Beneficiary, LedgerEntry, Transfer } from '../lib/types'
import { moment, money, shortId, toMinor, label } from '../lib/format'
import { useQuery } from '../lib/useQuery'
import { CopyId, DeltaAmount, Empty, ErrorNote, Heading, Load, Metric, Panel, Refresh, TableWrap, TinyNote } from '../components/Ui'
import { FilterBar } from '../components/FilterBar'
import TransferTable from '../components/TransferTable'

export function ClientOverview({ api, navigate, openTransfer }: { api: ApiClient; navigate: (page: 'accounts'|'transfers'|'ledger') => void; openTransfer: (id: string) => void }) {
  const accounts = useQuery<Account[]>(api, '/api/client/accounts', [])
  const transfers = useQuery<Transfer[]>(api, '/api/client/portal/transfers?limit=60', [])
  const total = accounts.data.reduce((n, a) => n + a.balanceMinor, 0)
  const completed = transfers.data.filter(t => ['COMPLETED', 'SETTLED'].includes(t.status)).length
  const pending = transfers.data.filter(t => ['PENDING_SETTLEMENT', 'RECONCILIATION_REQUIRED'].includes(t.status)).length
  return <>
    <Heading eyebrow="ESPACE CLIENT" title="Bienvenue dans votre espace." description="Consultez vos comptes et suivez chaque mouvement, du transfert à son enregistrement comptable." action={<button className="button button-primary" onClick={() => navigate('transfers')}><Plus size={17}/> Nouveau transfert</button>}/>
    {(accounts.error || transfers.error) && <ErrorNote text={accounts.error || transfers.error || ''} retry={() => { accounts.refresh(); transfers.refresh() }}/>}
    <div className="dashboard-grid"><div className="balance-hero"><div className="hero-pattern"/><div className="hero-topline"><span><Wallet size={17}/> SOLDE DISPONIBLE</span><div className="hero-shield"><ShieldCheck size={14}/> MAD</div></div>
      <div className="hero-balance">{(accounts.loading || accounts.error) ? '—' : money(total)}</div><p>Solde cumulé de vos comptes</p><div className="hero-divider"/><div className="hero-footer"><span>{accounts.loading || accounts.error ? '—' : accounts.data.length} compte(s) associé(s)</span><button onClick={() => navigate('accounts')}>Voir mes comptes <ArrowRight size={16}/></button></div></div>
      <div className="dashboard-side-metrics"><Metric label="Transferts récents" value={transfers.loading || transfers.error ? '—' : transfers.data.length} icon={<ArrowLeftRight size={18}/>} tint="blue" detail="Fenêtre de lecture : 60 dernières lignes"/><Metric label="Transferts terminés" value={transfers.loading || transfers.error ? '—' : completed} icon={<CheckCircle2 size={18}/>} tint="mint" detail="Dans l’historique récent"/><Metric label="En attente / à vérifier" value={transfers.loading || transfers.error ? '—' : pending} icon={<Layers3 size={18}/>} tint="peach" detail="Dans l’historique récent"/></div></div>
    <div className="content-columns"><Panel title="Activité récente" subtitle="Opérations réellement enregistrées dans PayRail" action={<button className="text-action" onClick={() => navigate('transfers')}>Tout voir <ArrowRight size={15}/></button>}>
      {transfers.loading ? <Load/> : (transfers.error || accounts.error) ? null : <TransferTable transfers={transfers.data.slice(0, 6)} onOpen={openTransfer} ownerAccounts={accounts.data.map(a => a.id)}/>}</Panel>
      <Panel title="Accès rapide" subtitle="Vos opérations principales"><div className="quick-actions"><button onClick={() => navigate('transfers')}><span className="quick-icon mint"><Send size={20}/></span><span><strong>Effectuer un transfert</strong><small>Interne ou externe</small></span><ArrowUpRight size={17}/></button><button onClick={() => navigate('accounts')}><span className="quick-icon blue"><Landmark size={20}/></span><span><strong>Consulter mes comptes</strong><small>Soldes et identifiants</small></span><ArrowUpRight size={17}/></button><button onClick={() => navigate('ledger')}><span className="quick-icon peach"><BookOpenText size={20}/></span><span><strong>Consulter le journal</strong><small>Écritures comptables</small></span><ArrowUpRight size={17}/></button></div><div className="quick-note"><ShieldCheck size={17}/><span>Chaque mouvement financier est conservé dans un journal comptable en partie double.</span></div></Panel></div>
  </>
}

export function ClientAccounts({ api, onLedger }: { api: ApiClient; onLedger: (accountId: string) => void }) {
  const accounts = useQuery<Account[]>(api, '/api/client/accounts', [])
  return <><Heading eyebrow="COMPTES" title="Vos comptes." description="Solde comptable matérialisé, référence et accès à l’historique de chaque compte." action={<Refresh onClick={accounts.refresh} busy={accounts.loading}/>}/>
    {accounts.error && <ErrorNote text={accounts.error} retry={accounts.refresh}/>}
    {accounts.loading ? <Load/> : accounts.error ? null : accounts.data.length ? <div className="account-grid">{accounts.data.map(a => <div className="account-card" key={a.id}><div className="account-card-top"><span className="account-type"><Landmark size={16}/> COMPTE CLIENT</span></div><div className="account-reference">{a.reference}</div><div className="account-label">Solde actuel</div><div className="account-balance">{money(a.balanceMinor, a.currency)}</div><div className="account-card-footer"><CopyId value={a.id}/><button className="button button-dark button-small" onClick={() => onLedger(a.id)}>Voir le journal <ArrowRight size={15}/></button></div></div>)}</div> : <Empty title="Aucun compte associé"/>}
    <Panel className="explain-panel"><div className="explain-content"><div className="explain-icon"><ShieldCheck size={23}/></div><div><h3>Les transferts respectent des règles de cohérence comptable.</h3><p>Chaque opération débite et crédite des comptes dans une même transaction PostgreSQL. Le solde client et son journal comptable sont réconciliables. Cette application est une démonstration, pas un compte bancaire réel.</p></div></div></Panel>
  </>
}

export function ClientLedger({ api, initialAccountId, onOpen }: { api: ApiClient; initialAccountId?: string; onOpen: (id: string) => void }) {
  const accounts = useQuery<Account[]>(api, '/api/client/accounts', [])
  const [selected, setSelected] = useState(initialAccountId || '')
  useEffect(() => { if (!selected && accounts.data.length) setSelected(accounts.data[0].id) }, [accounts.data, selected])
  const entries = useQuery<LedgerEntry[]>(api, selected ? `/api/client/portal/accounts/${selected}/ledger?limit=200` : '', [])
  const chosen = accounts.data.find(a => a.id === selected)
  return <><Heading eyebrow="COMPTABILITÉ" title="Votre journal comptable." description="Historique des écritures conservées sans suppression. Les montants sont affichés en dirhams." action={selected ? <Refresh onClick={entries.refresh} busy={entries.loading}/> : undefined}/>
    {accounts.error && <ErrorNote text={accounts.error} retry={accounts.refresh}/>}
    <Panel title="Compte à consulter" subtitle="Les écritures sont limitées à vos comptes autorisés."><div className="ledger-account-selector"><label className="field-label" htmlFor="ledger-account">Compte client</label><select id="ledger-account" value={selected} onChange={e => setSelected(e.target.value)}>{accounts.data.map(a => <option key={a.id} value={a.id}>{a.reference}</option>)}</select>{chosen && <div className="ledger-balance-side">Solde actuel <strong>{money(chosen.balanceMinor)}</strong></div>}</div></Panel>
    <Panel title="Écritures comptables" subtitle="Les 200 écritures les plus récentes pour le compte sélectionné">
      {entries.error && <ErrorNote text={entries.error} retry={entries.refresh}/>}
      {accounts.error ? null : !selected ? (accounts.loading ? <Load/> : <Empty title="Aucun compte disponible"/>) : entries.loading ? <Load/> : entries.error ? null : !entries.data.length ? <Empty title="Aucune écriture comptable"/> : <TableWrap><thead><tr><th>Écriture</th><th>Effet financier</th><th>Montant signé</th><th>Date</th><th>Transaction</th></tr></thead><tbody>{entries.data.map(e => <tr key={e.id}><td><CopyId value={e.ledgerTransactionId}/></td><td><span className="effect-label">{label(e.effectType)}</span></td><td><DeltaAmount minor={e.signedAmountMinor}/></td><td className="cell-muted">{moment(e.createdAt)}</td><td>{e.transferId ? <button className="text-action" onClick={() => onOpen(e.transferId!)}>{shortId(e.transferId)} <ArrowRight size={14}/></button> : <span className="cell-muted">Dotation initiale</span>}</td></tr>)}</tbody></TableWrap>}
    </Panel><TinyNote>Chaque écriture est issue du journal comptable réel. Une écriture d’un compte représente une partie d’une transaction comptable équilibrée.</TinyNote>
  </>
}

export function ClientTransfers({ api, onOpen }: { api: ApiClient; onOpen: (id: string) => void }) {
  const accounts = useQuery<Account[]>(api, '/api/client/accounts', [])
  const beneficiaries = useQuery<Beneficiary[]>(api, '/api/client/portal/beneficiaries', [])
  const history = useQuery<Transfer[]>(api, '/api/client/portal/transfers?limit=200', [])
  const [tab, setTab] = useState<'history'|'new'>('history')
  const [mode, setMode] = useState<'INTERNAL'|'EXTERNAL'>('INTERNAL')
  const [sender, setSender] = useState('')
  const [beneficiary, setBeneficiary] = useState('')
  const [destination, setDestination] = useState('')
  const [amount, setAmount] = useState('')
  const [confirm, setConfirm] = useState(false)
  const [idempotencyKey, setIdempotencyKey] = useState(() => crypto.randomUUID())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<{ transferId: string; status?: string } | null>(null)
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState('ALL')
  useEffect(() => { if (!sender && accounts.data.length) setSender(accounts.data[0].id) }, [sender, accounts.data])
  useEffect(() => { if (!beneficiary && beneficiaries.data.length) setBeneficiary(beneficiaries.data[0].id) }, [beneficiary, beneficiaries.data])
  const resetKey = () => { setIdempotencyKey(crypto.randomUUID()); setConfirm(false); setResult(null) }
  const filtered = history.data.filter(t => (status === 'ALL' || t.status === status) && (
    [t.id, t.senderReference, t.receiverReference || '', t.destinationReference || '', t.status, t.settlementRequestId || ''].join(' ').toLowerCase().includes(search.toLowerCase())
  ))
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy) return
    setError(null); setResult(null)
    if (!confirm) { setError('Confirme le récapitulatif avant de lancer le transfert.'); return }
    let amountMinor: number
    try { amountMinor = toMinor(amount) } catch (e) { setError(e instanceof Error ? e.message : 'Montant invalide.'); return }
    if (!sender) { setError('Compte émetteur requis.'); return }
    if (mode === 'INTERNAL' && (!beneficiary || sender === beneficiary)) { setError('Sélectionne un bénéficiaire distinct.'); return }
    if (mode === 'EXTERNAL' && (!destination.trim() || destination.length > 128)) { setError('Référence externe requise (128 caractères maximum).'); return }
    setBusy(true)
    try {
      const path = mode === 'INTERNAL' ? '/api/client/transfers' : '/api/client/external-transfers'
      const payload = mode === 'INTERNAL' ? { senderAccountId: sender, receiverAccountId: beneficiary, amountMinor } : { senderAccountId: sender, destinationReference: destination.trim(), amountMinor }
      const created = await api.post<{ transferId: string; status?: string }>(path, payload, idempotencyKey)
      setResult(created); history.refresh(); accounts.refresh()
      setIdempotencyKey(crypto.randomUUID()); setConfirm(false)
    } catch (e) { setError(e instanceof Error ? e.message : 'Transfert non confirmé.'); /* Retain key for a safe retry */ }
    finally { setBusy(false) }
  }
  return <><Heading eyebrow="TRANSFERTS" title="Transferts." description="Effectuez un virement interne ou réservez un transfert externe avec une clé d’idempotence transactionnelle." action={<button className="button button-primary" onClick={() => setTab('new')}><Plus size={17}/> Nouveau transfert</button>}/>
    <div className="segment-tabs"><button className={tab === 'history' ? 'active' : ''} onClick={() => setTab('history')}>Historique des transferts</button><button className={tab === 'new' ? 'active' : ''} onClick={() => setTab('new')}>Nouveau transfert</button></div>
    {tab === 'history' ? <Panel title="Opérations enregistrées" subtitle="Historique des 200 dernières opérations visibles par votre compte" action={<Refresh onClick={history.refresh} busy={history.loading}/>}>
      <FilterBar search={search} onSearch={setSearch} status={status} onStatus={setStatus} options={['COMPLETED','SETTLED','PENDING_SETTLEMENT','RECONCILIATION_REQUIRED','FAILED']}/>
      {history.error && <ErrorNote text={history.error} retry={history.refresh}/>}
      {history.loading ? <Load/> : history.error ? null : <TransferTable transfers={filtered} onOpen={onOpen} ownerAccounts={accounts.data.map(a => a.id)}/>}</Panel>
      : <div className="transfer-create-grid"><Panel title="Créer un transfert" subtitle="L’invariant financier est vérifié côté serveur.">
          <div className="transfer-type-switch"><button type="button" className={mode === 'INTERNAL' ? 'selected' : ''} onClick={() => { setMode('INTERNAL'); resetKey() }}><ArrowLeftRight size={18}/> Interne</button><button type="button" className={mode === 'EXTERNAL' ? 'selected' : ''} onClick={() => { setMode('EXTERNAL'); resetKey() }}><ArrowUpRight size={18}/> Externe</button></div>
          <form className="transfer-form" onSubmit={submit}><label className="field-label" htmlFor="transfer-sender">Compte émetteur</label><select id="transfer-sender" value={sender} onChange={e => { setSender(e.target.value); resetKey() }} required><option value="">Sélectionner</option>{accounts.data.map(a => <option value={a.id} key={a.id}>{a.reference} · {money(a.balanceMinor)}</option>)}</select>
            {mode === 'INTERNAL' ? <><label className="field-label" htmlFor="transfer-beneficiary">Bénéficiaire (comptes de démonstration)</label><select id="transfer-beneficiary" value={beneficiary} onChange={e => { setBeneficiary(e.target.value); resetKey() }} required><option value="">Sélectionner</option>{beneficiaries.data.map(b => <option key={b.id} value={b.id}>{b.reference}</option>)}</select><small className="form-help">Seuls les bénéficiaires synthétiques de démonstration sont affichés.</small></> : <><label className="field-label" htmlFor="transfer-destination">Référence externe</label><input id="transfer-destination" maxLength={128} required placeholder="Ex. BANK-DEMO-001" value={destination} onChange={e => { setDestination(e.target.value); resetKey() }}/><small className="form-help">Référence du règlement simulé, pas un vrai compte bancaire.</small></>}
            <label className="field-label" htmlFor="transfer-amount">Montant (MAD)</label><div className="amount-input"><input id="transfer-amount" inputMode="decimal" required placeholder="0,00" value={amount} onChange={e => { setAmount(e.target.value); resetKey() }}/><span>MAD</span></div>
            <label className="confirm-row"><input type="checkbox" checked={confirm} onChange={e => setConfirm(e.target.checked)}/><span>Je confirme les informations et autorise cette opération de démonstration.</span></label>
            {error && <ErrorNote text={error}/>}
            {result && <div className="success-note"><CheckCircle2 size={20}/><div><strong>Opération enregistrée</strong><p>{result.status ? label(result.status) : 'Transfert créé'} · <CopyId value={result.transferId}/></p><button className="text-action" type="button" onClick={() => onOpen(result.transferId)}>Voir le transfert <ArrowRight size={14}/></button></div></div>}
            <button className="button button-primary full" disabled={busy || !confirm || !accounts.data.length} type="submit"><Send size={17}/>{busy ? 'Traitement en cours…' : 'Confirmer le transfert'}<ArrowRight size={16}/></button>
          </form></Panel>
          <div className="transfer-info-side"><div className="transfer-info-card"><div className="transfer-info-head"><Sparkles size={19}/> À savoir</div><h3>Un transfert, une trace comptable.</h3><p>Chaque transfert est enregistré avec ses écritures comptables. Un transfert externe passe d’abord par une réservation du montant.</p><div className="info-separator"/><div className="info-feature"><ShieldCheck size={16}/> Contrôle du compte émetteur</div><div className="info-feature"><Layers3 size={16}/> Comptabilité en partie double</div><div className="info-feature"><CreditCard size={16}/> Protection contre les envois en double</div></div>
          <Panel title="Clé d’idempotence" subtitle="Réutilisée pour une nouvelle tentative identique après une erreur réseau."><div className="idempotency-preview"><CopyId value={idempotencyKey}/></div><TinyNote>Modifier le montant ou le bénéficiaire crée une nouvelle clé. Une réponse incertaine peut être retentée sans dupliquer le débit.</TinyNote></Panel></div>
        </div>}
  </>
}
