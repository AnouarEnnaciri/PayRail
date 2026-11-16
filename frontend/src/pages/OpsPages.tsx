import { useState } from 'react'
import { Activity, AlertTriangle, ArrowRight, ArrowUpRight, CheckCircle2, CircleCheck, CircleGauge, Clock3, Database, Wallet, FileCheck2, FileSearch, GitBranch, Layers3, Network, RefreshCw, SearchCheck, Server, ShieldAlert, ShieldCheck, TimerReset, Workflow, Zap } from 'lucide-react'
import type { ApiClient } from '../lib/api'
import type { ActuatorMetric, Attempt, Callback, Issue, Outbox, Overview, Run, Transfer } from '../lib/types'
import { label, moment, money, number, shortId } from '../lib/format'
import { useQuery } from '../lib/useQuery'
import { CopyId, Empty, ErrorNote, Heading, Load, Metric, Panel, Pill, Refresh, TableWrap, TinyNote, ViewLink } from '../components/Ui'
import { FilterBar } from '../components/FilterBar'
import TransferTable from '../components/TransferTable'
import OpsFundingExperience from './OpsFundingExperience'

const zero: Overview = { transfers:0, internalCompleted:0, externalSettled:0, externalPending:0, requiresReconciliation:0, failed:0, outboxPending:0, outboxProcessing:0, deadLetters:0, openIssues:0, ledgerTransactions:0, clearingMinor:0 }

const opsFrLabel = (value: string | null | undefined): string => {
  const raw = value ?? ''
  const translations: Record<string, string> = {
    UNKNOWN_SETTLEMENT_STATE: 'État du règlement externe inconnu',
    'UNKNOWN SETTLEMENT STATE': 'État du règlement externe inconnu',
    'verified external outcome': 'Résultat externe vérifié',
    ResourceAccessException: 'Erreur d’accès au service externe',
    STATUS_LOOKUP_NETWORK_OR_TIMEOUT: 'Erreur réseau ou délai dépassé lors de la vérification du statut',
    'STATUS LOOKUP NETWORK OR TIMEOUT': 'Erreur réseau ou délai dépassé lors de la vérification du statut',
    NETWORK_ERROR: 'Erreur réseau',
    HTTP_ERROR: 'Erreur HTTP',
    INVALID_RESPONSE: 'Réponse invalide',
    NOT_FOUND: 'Introuvable',
    LEASE_EXPIRED: 'Délai de traitement expiré',
    EXTERNAL_SETTLED_INTERNAL_PENDING: 'Règlement externe confirmé, finalisation interne en attente',
    EXTERNAL_REJECTED_INTERNAL_PENDING: 'Règlement externe rejeté, compensation interne en attente',
  }
  return translations[raw] ?? label(raw)
}

const opsFrDetail = (value: string): string => {
  const translations: Record<string, string> = {
    'External result is unavailable or malformed; no financial action is authorized':
      'Le résultat du règlement externe est indisponible ou invalide ; aucune action financière n’est autorisée.',
  }
  return translations[value] ?? value
}


export function OpsOverview({ api, navigate, openTransfer }: { api: ApiClient; navigate: (s: 'ops-transfers'|'outbox'|'reconciliation'|'reliability'|'metrics') => void; openTransfer: (id: string) => void }) {
  const [fundingOpen, setFundingOpen] = useState(false)
  const overview = useQuery<Overview>(api, '/api/ops/portal/overview', zero)
  const transfers = useQuery<Transfer[]>(api, '/api/ops/portal/transfers?limit=8', [])
  const d = overview.data
  const segments = [
    { label: 'Internes terminés', count: d.internalCompleted, klass: 'mint' },
    { label: 'Externes réglés', count: d.externalSettled, klass: 'blue' },
    { label: 'En attente', count: d.externalPending, klass: 'gold' },
    { label: 'À réconcilier', count: d.requiresReconciliation, klass: 'peach' },
    { label: 'Échoués', count: d.failed, klass: 'dark' },
  ]
  if (fundingOpen) return <OpsFundingExperience api={api} onBack={() => { setFundingOpen(false); overview.refresh(); transfers.refresh() }} onFunded={() => { overview.refresh(); transfers.refresh() }}/>
  if (overview.loading) return <Load/>
  if (overview.error) return <ErrorNote text={overview.error} retry={overview.refresh}/>
  return <><Heading eyebrow="EXPLOITATION" title="Vue d’ensemble." description="Consultez les transferts, règlements et contrôles financiers enregistrés dans PostgreSQL." action={<Refresh onClick={() => { overview.refresh(); transfers.refresh() }} busy={overview.loading}/>}/>
    {overview.error && <ErrorNote text={overview.error} retry={overview.refresh}/>}
    <div className="pr-funding-entry">
      <div className="pr-funding-entry-copy">
        <span className="pr-funding-entry-icon"><Wallet size={22}/></span>
        <div><span className="pr-funding-entry-label">OUTIL OPÉRATEUR</span><strong>Financement de démonstration</strong><p>Rechargez les comptes Alpha ou Beta pour poursuivre vos tests.</p></div>
      </div>
      <button type="button" className="pr-funding-entry-button" onClick={() => { setFundingOpen(true); window.scrollTo({ top: 0, behavior: 'smooth' }) }}>
        Recharger un compte <ArrowRight size={16}/>
      </button>
    </div>
    <div className="metric-grid cols-4"><Metric label="Total des transferts" value={d.transfers} detail="Toutes catégories" icon={<Workflow size={18}/>} tint="blue"/><Metric label="Règlements réussis" value={d.externalSettled} detail="Règlements externes vérifiés" icon={<CircleCheck size={18}/>} tint="mint"/><Metric label="À traiter" value={d.outboxPending + d.outboxProcessing} detail="Tâches de traitement actives" icon={<Clock3 size={18}/>} tint="violet"/><Metric label="Points de vigilance" value={d.deadLetters + d.openIssues + d.requiresReconciliation} detail="Somme de catégories pouvant se recouper" icon={<ShieldAlert size={18}/>} tint="peach"/></div>
    <div className="ops-overview-grid"><Panel title="Répartition des transferts" subtitle="Principaux états calculés depuis PostgreSQL"><div className="distribution-head"><strong>{number(d.transfers)}</strong><small>transferts enregistrés</small></div><div className="distribution-bar" role="img" aria-label="Répartition des transferts par état">{segments.map(s => s.count > 0 ? <span key={s.label} className={s.klass} style={{ width: `${s.count / Math.max(1,d.transfers) * 100}%` }} title={`${s.label}: ${s.count}`}/> : null)}</div><div className="distribution-legend">{segments.map(s => <div key={s.label}><span className={`legend-dot ${s.klass}`}/><span>{s.label}</span><strong>{number(s.count)}</strong></div>)}</div></Panel>
      <Panel title="État des opérations" subtitle="Vues opérationnelles essentielles"><div className="ops-command-list"><button onClick={() => navigate('outbox')}><span className="command-icon mint"><Network size={19}/></span><span><strong>File de règlements</strong><small>{number(d.outboxPending)} en attente · {number(d.deadLetters)} en échec définitif</small></span><ArrowUpRight size={17}/></button><button onClick={() => navigate('reconciliation')}><span className="command-icon blue"><SearchCheck size={19}/></span><span><strong>Rapprochement</strong><small>{number(d.openIssues)} anomalie(s) ouverte(s)</small></span><ArrowUpRight size={17}/></button><button onClick={() => navigate('reliability')}><span className="command-icon peach"><GitBranch size={19}/></span><span><strong>Suivi de fiabilité</strong><small>Explorer les événements de panne réels</small></span><ArrowUpRight size={17}/></button><button onClick={() => navigate('metrics')}><span className="command-icon violet"><Activity size={19}/></span><span><strong>Observabilité</strong><small>Actuator et mesures opérationnelles</small></span><ArrowUpRight size={17}/></button></div></Panel></div>
    <div className="metric-grid cols-3"><Metric label="Transactions comptables" value={d.ledgerTransactions} icon={<Database size={17}/>} tint="blue" detail="Transactions comptables enregistrées"/><Metric label="Solde de compensation" value={money(d.clearingMinor)} icon={<Layers3 size={17}/>} tint="mint" detail="Solde calculé depuis le journal comptable"/><Metric label="Rapprochement nécessaire" value={d.requiresReconciliation} icon={<AlertTriangle size={17}/>} tint="peach" detail="État financier incertain ≠ échec"/></div>

    <Panel title="Derniers mouvements" subtitle="Les dernières transactions, toutes catégories confondues" action={<button onClick={() => navigate('ops-transfers')} className="text-action">Voir toutes les transactions <ArrowRight size={15}/></button>}>
      {transfers.error && <ErrorNote text={transfers.error} retry={transfers.refresh}/>}{transfers.loading ? <Load/> : transfers.error ? null : <TransferTable transfers={transfers.data} onOpen={openTransfer}/>}</Panel>
  </>
}

export function OpsTransfers({ api, openTransfer }: { api: ApiClient; openTransfer: (id: string) => void }) {
  const q = useQuery<Transfer[]>(api, '/api/ops/portal/transfers?limit=200', [])
  const [search,setSearch] = useState(''); const [status,setStatus] = useState('ALL')
  const filtered = q.data.filter(t => (status === 'ALL' || t.status === status) && [t.id,t.senderReference,t.receiverReference || '',t.destinationReference || '',t.settlementRequestId || ''].join(' ').toLowerCase().includes(search.toLowerCase()))
  return <><Heading eyebrow="EXPLOITATION / TRANSFERTS" title="Journal des transactions." description="Historique réel des transferts internes et externes. Sélectionnez une opération pour inspecter sa chronologie." action={<Refresh onClick={q.refresh} busy={q.loading}/>}/>
    <Panel title="Flux transactionnel" subtitle="Les 200 transferts les plus récents"><FilterBar search={search} onSearch={setSearch} status={status} onStatus={setStatus} options={['COMPLETED','SETTLED','PENDING_SETTLEMENT','RECONCILIATION_REQUIRED','FAILED']}/>
      {q.error && <ErrorNote text={q.error} retry={q.refresh}/>}{q.loading ? <Load/> : q.error ? null : <TransferTable transfers={filtered} onOpen={openTransfer}/>}</Panel>
  </>
}

export function SettlementQueue({ api, openTransfer }: { api: ApiClient; openTransfer: (id: string) => void }) {
  const jobs = useQuery<Outbox[]>(api, '/api/ops/portal/outbox?limit=200', [])
  const attempts = useQuery<Attempt[]>(api, '/api/ops/portal/attempts?limit=200', [])
  const callbacks = useQuery<Callback[]>(api, '/api/ops/portal/callbacks?limit=200', [])
  const [tab,setTab] = useState<'jobs'|'attempts'|'callbacks'>('jobs')
  const [status,setStatus] = useState('ALL')
  const statuses = tab === 'jobs' ? [...new Set(jobs.data.map(j => j.status))] : tab === 'attempts' ? [...new Set(attempts.data.map(a => a.outcome))] : [...new Set(callbacks.data.map(c => c.externalStatus))]
  return <><Heading eyebrow="EXPLOITATION / RÈGLEMENTS" title="File de règlements." description="Tâches de traitement, tentatives réseau et notifications signées du simulateur." action={<Refresh onClick={() => { jobs.refresh(); attempts.refresh(); callbacks.refresh() }} busy={jobs.loading || attempts.loading}/>}/>
    <div className="metric-grid cols-3"><Metric label="Tâches de traitement" value={jobs.loading || jobs.error ? '—' : jobs.data.length} icon={<Network size={19}/>} tint="blue" detail="Dans les 200 dernières tâches"/><Metric label="Tentatives de règlement" value={attempts.loading || attempts.error ? '—' : attempts.data.length} icon={<TimerReset size={19}/>} tint="peach" detail="Dans les 200 dernières tentatives"/><Metric label="Notifications reçues" value={callbacks.loading || callbacks.error ? '—' : callbacks.data.length} icon={<ShieldCheck size={19}/>} tint="mint" detail="Dans les 200 derniers événements"/></div>
    <Panel title="Registre des règlements" subtitle="Une tentative réseau n’est pas un second mouvement d’argent."><div className="panel-control-row"><div className="segment-tabs compact"><button className={tab==='jobs'?'active':''} onClick={() => {setTab('jobs');setStatus('ALL')}}>Tâches</button><button className={tab==='attempts'?'active':''} onClick={() => {setTab('attempts');setStatus('ALL')}}>Tentatives</button><button className={tab==='callbacks'?'active':''} onClick={() => {setTab('callbacks');setStatus('ALL')}}>Notifications</button></div><select aria-label="Statut" className="select-compact" value={status} onChange={e => setStatus(e.target.value)}><option value="ALL">Tous les états</option>{statuses.map(x => <option key={x} value={x}>{label(x)}</option>)}</select></div>
      {tab==='jobs' ? jobs.error ? <ErrorNote text={jobs.error} retry={jobs.refresh}/> : jobs.loading ? <Load/> : jobs.data.filter(x=>status==='ALL'||x.status===status).length ? <TableWrap><thead><tr><th>Identifiant de tâche</th><th>État</th><th>Essais</th><th>Planifié / traité</th><th>Transfert</th></tr></thead><tbody>{jobs.data.filter(x=>status==='ALL'||x.status===status).map(j=><tr key={j.id}><td><CopyId value={j.id}/></td><td><Pill status={j.status}/></td><td>{j.attemptCount}</td><td className="cell-muted">{moment(j.processedAt || j.availableAt)}</td><td><ViewLink onClick={()=>openTransfer(j.transferId)}>{shortId(j.transferId)}</ViewLink></td></tr>)}</tbody></TableWrap> : <Empty title="Aucune tâche dans cette catégorie"/>
        : tab==='attempts' ? attempts.error ? <ErrorNote text={attempts.error} retry={attempts.refresh}/> : attempts.loading ? <Load/> : attempts.data.filter(x=>status==='ALL'||x.outcome===status).length ? <TableWrap><thead><tr><th>Tentative</th><th>Résultat réseau</th><th>HTTP</th><th>Latence</th><th>Heure</th><th>Transfert</th></tr></thead><tbody>{attempts.data.filter(x=>status==='ALL'||x.outcome===status).map(a=><tr key={a.id}><td><div className="table-stack"><strong>#{a.attemptNumber}</strong><CopyId value={a.id}/></div></td><td><Pill status={a.outcome}/>{a.errorCategory && <div className="micro-detail">{label(a.errorCategory)}</div>}</td><td>{a.httpStatus ?? '—'}</td><td>{a.latencyMs == null ? '—' : `${number(a.latencyMs)} ms`}</td><td className="cell-muted">{moment(a.startedAt)}</td><td><ViewLink onClick={()=>openTransfer(a.transferId)}>{shortId(a.transferId)}</ViewLink></td></tr>)}</tbody></TableWrap> : <Empty title="Aucune tentative correspondante"/>
        : callbacks.error ? <ErrorNote text={callbacks.error} retry={callbacks.refresh}/> : callbacks.loading ? <Load/> : callbacks.data.filter(x=>status==='ALL'||x.externalStatus===status).length ? <TableWrap><thead><tr><th>Événement</th><th>État externe</th><th>Disposition</th><th>Reçu le</th><th>Transfert</th></tr></thead><tbody>{callbacks.data.filter(x=>status==='ALL'||x.externalStatus===status).map(c=><tr key={c.eventId}><td><CopyId value={c.eventId}/></td><td><Pill status={c.externalStatus}/></td><td>{label(c.disposition)}</td><td className="cell-muted">{moment(c.receivedAt)}</td><td><ViewLink onClick={()=>openTransfer(c.transferId)}>{shortId(c.transferId)}</ViewLink></td></tr>)}</tbody></TableWrap> : <Empty title="Aucune notification correspondante"/>}
    </Panel><TinyNote>Les tâches en échec définitif nécessitent une investigation. Aucune action de remboursement ou de finalisation n’est déclenchée depuis ce tableau.</TinyNote>
  </>
}

export function Reconciliation({ api, openTransfer }: { api: ApiClient; openTransfer: (id: string) => void }) {
  const runs = useQuery<Run[]>(api, '/api/ops/portal/reconciliation/runs?limit=40', [])
  const issues = useQuery<Issue[]>(api, '/api/ops/portal/reconciliation/issues?limit=200', [])
  const [selected,setSelected] = useState<string>('ALL')
  const [busy,setBusy] = useState(false)
  const [resolving,setResolving] = useState<string | null>(null)
  const [error,setError] = useState<string | null>(null)
  const [notice,setNotice] = useState<string | null>(null)
  const currentIssues = issues.data.filter(i => selected==='ALL'||i.runId===selected)
  async function scan() {
    setBusy(true);setError(null);setNotice(null)
    try { const run = await api.post<Run>('/api/ops/reconciliation/runs'); setSelected(run.id); setNotice(`Analyse ${shortId(run.id)} terminée : ${run.issuesFound} anomalie(s).`); runs.refresh();issues.refresh() }
    catch(e) { setError(e instanceof Error ? e.message : 'Analyse indisponible.') }
    finally {setBusy(false)}
  }
  async function resolve(issue: Issue) {
    if (!window.confirm('Résoudre cette anomalie ? Le serveur revalidera la preuve externe avant tout effet financier.')) return
    setResolving(issue.id);setError(null);setNotice(null)
    try { const response = await api.post<{disposition:string}>('/api/ops/reconciliation/issues/'+issue.id+'/resolve'); setNotice(`Action confirmée : ${label(response.disposition)}.`);issues.refresh();runs.refresh() }
    catch(e){setError(e instanceof Error?e.message:'Résolution impossible.')}finally{setResolving(null)}
  }
  return <><Heading eyebrow="EXPLOITATION / RAPPROCHEMENT" title="Rapprochement." description="Détecter les incohérences, vérifier les preuves et corriger uniquement les états financièrement autorisés." action={<button className="button button-primary" onClick={scan} disabled={busy}><SearchCheck size={17}/>{busy?'Analyse en cours…':'Lancer un rapprochement'}</button>}/>
    {error&&<ErrorNote text={error}/>} {notice&&<div className="success-inline"><CheckCircle2 size={17}/>{notice}</div>}
    <div className="metric-grid cols-3"><Metric label="Analyses enregistrées" value={runs.loading || runs.error ? '—' : runs.data.length} detail="Dernières 40 analyses" icon={<FileSearch size={17}/>} tint="blue"/><Metric label="Anomalies ouvertes" value={issues.loading || issues.error ? '—' : issues.data.filter(i=>i.status==='OPEN').length} detail="Dans les 200 dernières anomalies" icon={<AlertTriangle size={17}/>} tint="peach"/><Metric label="Anomalies résolues" value={issues.loading || issues.error ? '—' : issues.data.filter(i=>i.status==='RESOLVED').length} detail="Résolution auditée" icon={<FileCheck2 size={17}/>} tint="mint"/></div>
    <Panel title="Historique des analyses" subtitle="Chaque analyse conserve son résultat de manière durable" action={<Refresh onClick={()=>{runs.refresh();issues.refresh()}} busy={runs.loading}/>}>{runs.error&&<ErrorNote text={runs.error} retry={runs.refresh}/>} {runs.loading?<Load/>:runs.error?null:!runs.data.length?<Empty title="Aucune analyse disponible" detail="Lancez un rapprochement pour obtenir un rapport réel."/>:<TableWrap><thead><tr><th>Analyse</th><th>Résultat</th><th>Contrôles</th><th>Anomalies</th><th>Exécutée le</th><th></th></tr></thead><tbody>{runs.data.map(run=><tr key={run.id}><td><CopyId value={run.id}/></td><td><Pill status={run.status}/></td><td>{number(run.checksPerformed)}</td><td>{number(run.issuesFound)}</td><td className="cell-muted">{moment(run.completedAt)}</td><td><button className="text-action" onClick={()=>setSelected(run.id)}>Inspecter <ArrowRight size={14}/></button></td></tr>)}</tbody></TableWrap>}</Panel>
    <Panel title="Anomalies détectées" subtitle="La détection reste distincte de la réparation — une preuve externe est requise." action={<select className="select-compact" value={selected} onChange={e=>setSelected(e.target.value)} aria-label="Filtrer par analyse"><option value="ALL">Toutes les analyses</option>{runs.data.map(r=><option key={r.id} value={r.id}>{shortId(r.id)} · {r.issuesFound} anomalies</option>)}</select>}>{issues.error&&<ErrorNote text={issues.error} retry={issues.refresh}/>} {issues.loading?<Load/>:issues.error?null:!currentIssues.length?<Empty title="Aucune anomalie dans cette sélection" detail="Aucune anomalie parmi les 200 dernières anomalies chargées."/>:<div className="issue-list">{currentIssues.map(i=>{const repairable=i.status==='OPEN' && ['EXTERNAL_SETTLED_INTERNAL_PENDING','EXTERNAL_REJECTED_INTERNAL_PENDING'].includes(i.issueType)
        return <div className="issue-card" key={i.id}><div className="issue-top"><div><span className="issue-type" title={i.issueType}>{opsFrLabel(i.issueType)}</span><CopyId value={i.id}/></div><Pill status={i.status}/></div><p title={i.detail}>{opsFrDetail(i.detail)}</p><div className="issue-states"><span><strong>Attendu</strong>{opsFrLabel(i.expectedState)}</span><ArrowRight size={15}/><span><strong>Observé</strong>{opsFrLabel(i.observedState)}</span></div><div className="issue-foot"><span>Détectée {moment(i.detectedAt)}</span><div>{i.resourceType==='TRANSFER'&&<button className="text-action" onClick={()=>openTransfer(i.resourceId)}>Voir le transfert <ArrowRight size={13}/></button>}{repairable&&<button className="button button-dark button-small" disabled={resolving===i.id} onClick={()=>resolve(i)}>{resolving===i.id?'Vérification…':'Résoudre avec preuve'} <ShieldCheck size={14}/></button>}{!repairable&&i.status==='OPEN'&&<span className="investigation-tag">Investigation requise</span>}</div></div></div>
      })}</div>}</Panel><TinyNote>Un délai dépassé ou une réponse « introuvable » ne constituent jamais une preuve d’échec financier. L’API de résolution refuse toute action non autorisée.</TinyNote>
  </>
}

export function ReliabilityLab({ api, openTransfer }: { api: ApiClient; openTransfer: (id: string) => void }) {
  const attempts = useQuery<Attempt[]>(api, '/api/ops/portal/attempts?limit=200', [])
  const jobs = useQuery<Outbox[]>(api, '/api/ops/portal/outbox?limit=200', [])
  const [filter,setFilter] = useState('ALL')
  const failures = ['NETWORK_ERROR','HTTP_ERROR','INVALID_RESPONSE','NOT_FOUND','LEASE_EXPIRED']
  const focused = attempts.data.filter(a=>filter==='ALL'||(filter==='UNCERTAIN'&&failures.includes(a.outcome))||a.outcome===filter)
  const hasUncertainty = attempts.data.filter(a=>failures.includes(a.outcome)).length
  return <><Heading eyebrow="EXPLOITATION / FIABILITÉ" title="Suivi de fiabilité." description="Explorez les vrais scénarios de défaillance présents dans l’historique du simulateur et des tâches de traitement." action={<Refresh onClick={()=>{attempts.refresh();jobs.refresh()}} busy={attempts.loading}/>}/>
    <div className="reliability-banner"><div className="reliability-icon"><GitBranch size={24}/></div><div><div className="eyebrow">HISTORIQUE DES INCIDENTS</div><h3>Historique des incidents techniques</h3><p>Consultez les tentatives et les notifications enregistrées pour comprendre le résultat de chaque opération.</p></div><ShieldCheck size={35} className="reliability-side-mark"/></div>
    <div className="metric-grid cols-3"><Metric label="Tentatives réseau" value={attempts.loading || attempts.error ? '—' : attempts.data.length} icon={<Workflow size={19}/>} tint="blue" detail="Sur les 200 dernières"/><Metric label="Résultats incertains / techniques" value={attempts.loading || attempts.error ? '—' : hasUncertainty} icon={<AlertTriangle size={19}/>} tint="peach" detail="N’implique pas un débit supplémentaire"/><Metric label="Tâches en échec définitif" value={jobs.loading || jobs.error ? '—' : jobs.data.filter(x=>x.status==='DEAD_LETTER').length} icon={<ShieldAlert size={19}/>} tint="violet" detail="Dans les 200 dernières tâches"/></div>
    <Panel title="Explorer les événements de fiabilité" subtitle="Sélectionnez un résultat pour afficher les tentatives correspondantes."><div className="scenario-chips">{[{value:'ALL',label:'Tous les événements'},{value:'UNCERTAIN',label:'Incidents / incertitudes'},{value:'SETTLED',label:'Réglé'},{value:'REJECTED',label:'Rejeté'},{value:'PENDING',label:'En attente'},{value:'NOT_FOUND',label:'Introuvable'},{value:'LEASE_EXPIRED',label:'Verrou expiré'}].map(x=><button key={x.value} className={filter===x.value?'active':''} onClick={()=>setFilter(x.value)}>{x.label}</button>)}</div>
      {attempts.error&&<ErrorNote text={attempts.error} retry={attempts.refresh}/>} {attempts.loading?<Load/>:attempts.error?null:!focused.length?<Empty title="Aucun événement pour ce filtre" detail="Les résultats reflètent uniquement les scénarios réellement exécutés."/>:<div className="event-list">{focused.map(a=><div className="event-row" key={a.id}><div className="event-track"><span className="event-node"><Activity size={16}/></span></div><div className="event-main"><div className="event-row-head"><strong>Tentative n° {a.attemptNumber}</strong><Pill status={a.outcome}/></div><p>{a.errorCategory ? `Catégorie : ${opsFrLabel(a.errorCategory)} · ` : ''}{a.latencyMs===null?'Latence non enregistrée':`${number(a.latencyMs)} ms`}</p><small>{moment(a.startedAt)}</small></div><button className="button button-subtle button-small" onClick={()=>openTransfer(a.transferId)}>Chronologie <ArrowRight size={14}/></button></div>)}</div>}</Panel><TinyNote>Cette vue permet de consulter les incidents enregistrés. Elle ne déclenche pas de panne.</TinyNote>
  </>
}

export function Metrics({ api }: { api: ApiClient }) {
  const overview = useQuery<Overview>(api, '/api/ops/portal/overview', zero)
  const http = useQuery<ActuatorMetric>(api, '/actuator/metrics/http.server.requests', {name:'http.server.requests',measurements:[]})
  const count = http.data.measurements.find(x=>x.statistic==='COUNT')?.value
  const duration = http.data.measurements.find(x=>x.statistic==='TOTAL_TIME')?.value
  const avg = count && duration != null ? duration / count * 1000 : null
  const d = overview.data
  if (overview.loading) return <Load/>
  if (overview.error) return <ErrorNote text={overview.error} retry={overview.refresh}/>
  return <><Heading eyebrow="EXPLOITATION / OBSERVABILITÉ" title="Observabilité." description="Suivi des performances HTTP et des opérations financières à partir des mesures réelles du backend." action={<Refresh onClick={()=>{overview.refresh();http.refresh()}} busy={overview.loading||http.loading}/>}/>
    <div className="metric-grid cols-4"><Metric label="Requêtes HTTP observées" value={count===undefined?'—':Math.round(count)} icon={<Activity size={18}/>} tint="blue" detail="Depuis le lancement du backend"/><Metric label="Latence HTTP moyenne" value={avg===null?'—':`${avg.toFixed(1)} ms`} icon={<Clock3 size={18}/>} tint="mint" detail="Temps moyen mesuré (pas le p95)"/><Metric label="Transactions comptables" value={d.ledgerTransactions} icon={<Database size={18}/>} tint="violet" detail="Depuis PostgreSQL"/><Metric label="Anomalies ouvertes" value={d.openIssues} icon={<ShieldAlert size={18}/>} tint="peach" detail="Depuis les rapports de rapprochement"/></div>
    <div className="content-columns"><Panel title="Santé des flux" subtitle="Photographie actuelle du moteur financier"><div className="metrics-list"><div><span><CheckCircle2 size={17}/> Internes terminés</span><strong>{number(d.internalCompleted)}</strong></div><div><span><CheckCircle2 size={17}/> Externes réglés</span><strong>{number(d.externalSettled)}</strong></div><div><span><Clock3 size={17}/> Externes en attente</span><strong>{number(d.externalPending)}</strong></div><div><span><AlertTriangle size={17}/> Rapprochement requis</span><strong>{number(d.requiresReconciliation)}</strong></div><div><span><ShieldAlert size={17}/> Tâches en échec définitif</span><strong>{number(d.deadLetters)}</strong></div></div></Panel>
      <Panel title="Métriques HTTP du serveur" subtitle="Surveillance des requêtes et des performances du backend.">
        {http.error && <ErrorNote text={http.error} retry={http.refresh}/>}
        <div className="metrics-source">
          <span className="source-icon"><Server size={23}/></span>
          <div>
            <strong>Collecte des métriques HTTP</strong>
            <small>Compteurs et temps de réponse mesurés sur l’instance actuelle</small>
          </div>
          {http.loading ? <span>Chargement…</span> : http.error || http.data.measurements.length === 0 ? <Pill status="ERROR">Mesures indisponibles</Pill> : <Pill status="UP">Mesures disponibles</Pill>}
        </div>
        <div className="metrics-info">
          <p>Les métriques proviennent de l’instance backend actuelle. Aucun historique ni p95 n’est calculé ici.</p>
          <div><span>Périmètre</span><strong>Instance backend actuelle</strong></div>

        </div>
        <details style={{ marginTop: '1rem' }}>
          <summary style={{ cursor: 'pointer', fontWeight: 600 }}>Détails techniques · Micrometer / Actuator</summary>
          <div className="metrics-info" style={{ marginTop: '0.75rem' }}>
            <div><span>Endpoint Actuator</span><strong>/actuator/metrics/http.server.requests</strong></div>
            <div><span>Nom technique</span><strong>{http.data.name}</strong></div>
            <div><span>Unité native</span><strong>{http.data.baseUnit === 'seconds' ? 'secondes' : http.data.baseUnit || '—'}</strong></div>
            <div><span>Mesures retournées</span><strong>{http.data.measurements.length}</strong></div>
          </div>
        </details>
      </Panel></div>
    <TinyNote>La latence moyenne ne représente pas le 95e percentile. Les mesures concernent l’instance actuelle.</TinyNote>
  </>
}
