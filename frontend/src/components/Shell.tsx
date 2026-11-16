import { useState, type ReactNode } from 'react'
import { Activity, ArrowLeftRight, ArrowUpRight, BookOpenText, ChevronDown, ChevronRight, CircleGauge, ClipboardCheck, CreditCard, ExternalLink, Fingerprint, GitBranch, LayoutDashboard, LogOut, Menu, Moon, Network, SearchCheck, ShieldCheck, Sun, Wallet, X } from 'lucide-react'
import type { Session, View } from '../lib/types'
import { Brand } from './Ui'

type Nav = { title: string; items: { view: View; label: string; icon: ReactNode }[] }
const clientNav: Nav[] = [
  { title: 'ESPACE CLIENT', items: [
    { view: 'overview', label: 'Vue d’ensemble', icon: <LayoutDashboard size={18}/> },
    { view: 'accounts', label: 'Mes comptes', icon: <Wallet size={18}/> },
    { view: 'transfers', label: 'Transferts', icon: <ArrowLeftRight size={18}/> },
    { view: 'ledger', label: 'Journal comptable', icon: <BookOpenText size={18}/> },
  ] },
]
const opsNav: Nav[] = [
  { title: 'EXPLOITATION', items: [
    { view: 'ops-overview', label: 'Vue d’ensemble', icon: <LayoutDashboard size={18}/> },
    { view: 'ops-transfers', label: 'Transactions', icon: <ArrowLeftRight size={18}/> },
    { view: 'outbox', label: 'File de règlements', icon: <Network size={18}/> },
  ] },
  { title: 'FIABILITÉ ET AUDIT', items: [
    { view: 'reconciliation', label: 'Rapprochement', icon: <SearchCheck size={18}/> },
    { view: 'reliability', label: 'Suivi de fiabilité', icon: <GitBranch size={18}/> },
    { view: 'metrics', label: 'Observabilité', icon: <CircleGauge size={18}/> },
  ] },
]
const titles: Record<View, string> = { overview: 'Vue d’ensemble', accounts: 'Mes comptes', transfers: 'Transferts', ledger: 'Journal comptable', activity: 'Activité', 'ops-overview': 'Vue d’ensemble', 'ops-transfers': 'Transactions', outbox: 'File de règlements', reconciliation: 'Rapprochement', reliability: 'Suivi de fiabilité', metrics: 'Observabilité', detail: 'Détail du transfert' }

export default function Shell({ session, view, onNavigate, onLogout, children, dark, onToggleTheme }: {
  session: Session; view: View; onNavigate: (view: View) => void; onLogout: () => void
  children: ReactNode; dark: boolean; onToggleTheme: () => void
}) {
  const [mobile, setMobile] = useState(false)
  const [profile, setProfile] = useState(false)
  const displayName = session.role === 'OPS' ? 'Opérations' : session.username === 'client.alpha@example.test' ? 'Client Alpha' : session.username === 'client.beta@example.test' ? 'Client Beta' : session.username
  const initials = session.role === 'OPS' ? 'OP' : displayName === 'Client Alpha' ? 'AL' : displayName === 'Client Beta' ? 'BE' : session.username.slice(0, 2).toUpperCase()
  const nav = session.role === 'OPS' ? opsNav : clientNav
  const navigate = (target: View) => { onNavigate(target); setMobile(false) }
  return <div className={`app-shell ${dark ? 'theme-dark' : ''}`}>
    {mobile && <button className="sidebar-scrim" aria-label="Fermer le menu" onClick={() => setMobile(false)}/>}
    <aside className={`sidebar ${mobile ? 'sidebar-open' : ''}`}><div className="sidebar-top"><Brand/><button className="mobile-menu-close" onClick={() => setMobile(false)} aria-label="Fermer"><X size={19}/></button></div>
      <div className="sidebar-environment"><span className="environment-icon"><span className="live-indicator"/></span><span><strong>PayRail</strong><small>DÉMONSTRATION</small></span></div>
      <div className="sidebar-nav">{nav.map(group => <div className="nav-group" key={group.title}><div className="nav-group-title">{group.title}</div>{group.items.map(item => <button className={`nav-link ${view === item.view ? 'active' : ''}`} onClick={() => navigate(item.view)} key={item.view}><span>{item.icon}</span>{item.label}{view === item.view && <span className="nav-arrow"><ChevronRight size={15}/></span>}</button>)}</div>)}</div>
      <div className="sidebar-bottom"><div className="sidebar-integrity"><ShieldCheck size={19}/><div><strong>Intégrité comptable</strong><small>Traçabilité · Partie double</small></div></div>
        <button onClick={onLogout} className="sidebar-logout"><LogOut size={17}/> Déconnexion <ArrowUpRight size={15}/></button></div>
    </aside>
    <div className="main-area"><header className="topbar"><div className="topbar-left"><button className="mobile-menu-button" onClick={() => setMobile(true)} aria-label="Ouvrir le menu"><Menu size={22}/></button><div className="breadcrumb">Espace <ChevronRight size={13}/><strong>{titles[view]}</strong></div></div>
      <div className="topbar-actions"><button className="icon-button" title={dark ? 'Thème clair' : 'Thème sombre'} aria-label={dark ? 'Activer le thème clair' : 'Activer le thème sombre'} onClick={onToggleTheme}>{dark ? <Sun size={19}/> : <Moon size={19}/>}</button><div className="topbar-divider"/>
        <div className="profile-control"><button className="user-button" aria-expanded={profile} aria-controls="profile-panel" aria-label="Mon profil" onClick={() => setProfile(!profile)} onKeyDown={e => { if (e.key === 'Escape') setProfile(false) }}><span className="user-avatar">{initials}</span><div><strong>{displayName}</strong><small>{session.role === 'OPS' ? 'Exploitation' : 'Client'}</small></div><ChevronDown size={14}/></button>
        {profile && <section className="profile-panel" id="profile-panel" aria-label="Mon profil" onKeyDown={e => { if (e.key === 'Escape') setProfile(false) }}><div className="profile-panel-title"><strong>Mon profil</strong><button className="icon-button" aria-label="Fermer le profil" onClick={() => setProfile(false)}><X size={16}/></button></div><dl><dt>Adresse de connexion</dt><dd>{session.username}</dd><dt>Rôle</dt><dd>{session.role === 'OPS' ? 'Opérations' : 'Client'}</dd></dl><button className="sidebar-logout" onClick={onLogout}><LogOut size={16}/> Déconnexion</button></section>}</div>
      </div></header>
      <main className="workspace-content">{children}</main><footer className="workspace-footer"><span>PayRail · Plateforme de traitement des transactions</span></footer>
    </div>
  </div>
}
