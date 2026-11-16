import { useState, type ReactNode } from 'react'
import { Activity, AlertCircle, ArrowDownRight, ArrowRight, ArrowUpRight, Check, ChevronRight, CircleHelp, Copy, Database, ExternalLink, Inbox, LoaderCircle, RefreshCw, ShieldCheck } from 'lucide-react'
import { label, money, number, shortId } from '../lib/format'

export function Brand({ small = false }: { small?: boolean }) {
  return <div className={`brand ${small ? 'brand-small' : ''}`}>
    <div className="brand-logo"><svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><path d="M7 28V4h11c7 0 10 3.5 10 9s-3 9-10 9h-5v6H7Zm6-12h5c2.7 0 4-1 4-3s-1.3-3-4-3h-5v6Z" fill="currentColor"/></svg></div>
    <span>payrail<span className="brand-point">.</span></span>
  </div>
}

export function Pill({ status, children }: { status?: string | null; children?: ReactNode }) {
  const upper = (status || '').toUpperCase()
  const tone = ['SETTLED', 'COMPLETED', 'PROCESSED', 'RESOLVED', 'FINALIZED', 'UP', 'SUCCESS'].includes(upper) ? 'success'
    : ['FAILED', 'DEAD_LETTER', 'REJECTED', 'ERROR', 'LEASE_EXPIRED', 'NETWORK_ERROR', 'INVALID_RESPONSE', 'HTTP_ERROR'].includes(upper) ? 'danger'
      : ['PENDING', 'PENDING_SETTLEMENT', 'RECONCILIATION_REQUIRED', 'IN_FLIGHT', 'PROCESSING', 'OPEN', 'PARTIAL', 'NOT_FOUND'].includes(upper) ? 'warning' : 'neutral'
  return <span className={`pill ${tone}`}><span className="pill-dot" />{children ?? label(status)}</span>
}

export function Panel({ title, subtitle, children, action, className = '' }: {
  title?: string; subtitle?: string; children: ReactNode; action?: ReactNode; className?: string
}) {
  return <section className={`panel ${className}`}>
    {(title || action) && <div className="panel-header"><div><h3>{title}</h3>{subtitle && <p>{subtitle}</p>}</div>{action}</div>}
    {children}
  </section>
}

export function Heading({ eyebrow, title, description, action }: { eyebrow?: string; title: string; description?: string; action?: ReactNode }) {
  return <div className="page-heading"><div>{eyebrow && <div className="eyebrow">{eyebrow}</div>}<h1>{title}</h1>{description && <p>{description}</p>}</div>{action && <div className="heading-action">{action}</div>}</div>
}

export function Metric({ label: caption, value, detail, icon, tint = 'mint', positive }: {
  label: string; value: string | number; detail?: string; icon?: ReactNode; tint?: 'mint' | 'blue' | 'peach' | 'violet'; positive?: boolean
}) {
  return <div className="metric"><div className="metric-top"><span>{caption}</span><div className={`metric-icon ${tint}`}>{icon ?? <Activity size={17}/>}</div></div>
    <div className="metric-value">{typeof value === 'number' ? number(value) : value}</div>
    {detail && <div className="metric-detail">{positive ? <ShieldCheck size={13}/> : <span className="metric-line"/>}{detail}</div>}
  </div>
}

export function Refresh({ onClick, busy }: { onClick: () => void; busy?: boolean }) {
  return <button className="button button-subtle button-small" onClick={onClick} disabled={busy}><RefreshCw size={15} className={busy ? 'spin' : ''}/> Actualiser</button>
}

export function Empty({ title = 'Aucune donnée', detail = 'Aucun élément ne correspond à cette vue.', icon }: { title?: string; detail?: string; icon?: ReactNode }) {
  return <div className="empty"><div className="empty-icon">{icon || <Inbox size={22}/>}</div><strong>{title}</strong><p>{detail}</p></div>
}
export function Load({ label: caption = 'Chargement des données réelles…' }: { label?: string }) {
  return <div className="load"><LoaderCircle className="spin" size={22}/><span>{caption}</span></div>
}
export function ErrorNote({ text, retry }: { text: string; retry?: () => void }) {
  return <div role="alert" className="error-note"><AlertCircle size={18}/><span>{text}</span>{retry && <button className="link-button" onClick={retry}>Réessayer <ArrowRight size={13}/></button>}</div>
}
export function CopyId({ value }: { value?: string | null }) {
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    if (!value) return
    try { await navigator.clipboard.writeText(value); setCopied(true) } catch { setCopied(false) }
  }
  return <button className="copy-id" disabled={!value} title={copied ? 'Identifiant copié' : 'Copier l’identifiant'} onClick={() => { void copy() }}><span>{copied ? 'Copié !' : shortId(value)}</span>{copied ? <Check size={13}/> : <Copy size={13}/>}</button>
}
export function DeltaAmount({ minor, currency = 'MAD' }: { minor: number; currency?: string }) {
  const positive = minor >= 0
  return <span className={`delta ${positive ? 'positive' : 'negative'}`}>{positive ? <ArrowDownRight size={15}/> : <ArrowUpRight size={15}/>} {positive ? '+' : '−'}{money(Math.abs(minor), currency)}</span>
}
export function TableWrap({ children }: { children: ReactNode }) { return <div className="table-wrap"><table>{children}</table></div> }
export function ViewLink({ children, onClick }: { children: ReactNode; onClick: () => void }) { return <button className="table-link" onClick={onClick}>{children}<ChevronRight size={14}/></button> }
export function TinyNote({ children }: { children: ReactNode }) { return <div className="tiny-note"><CircleHelp size={14} />{children}</div> }
export function ExternalReference({ text }: { text: string }) { return <span className="reference"><Database size={14}/>{text}<ExternalLink size={12}/></span> }
export function StepCheck({ text }: { text: string }) { return <div className="step-check"><Check size={14}/>{text}</div> }
