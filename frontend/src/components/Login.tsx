import { useEffect, useState, type FormEvent } from 'react'
import { ArrowRight, CheckCircle2, KeyRound, LockKeyhole, ArrowUpRight, Eye, EyeOff } from 'lucide-react'
import { authenticate } from '../lib/api'
import type { ApiClient } from '../lib/api'
import type { Session } from '../lib/types'
import { Brand, ErrorNote } from './Ui'

const identities = [
  { name: 'Client Alpha', username: 'client.alpha@example.test', role: 'CLIENT' },
  { name: 'Client Beta', username: 'client.beta@example.test', role: 'CLIENT' },
  { name: 'Opérations', username: 'ops@example.test', role: 'OPS' },
]
export default function Login({ onLogin }: { onLogin: (session: Session, client: ApiClient) => void }) {
  const [identity, setIdentity] = useState(0)
  const [username, setUsername] = useState(identities[0].username)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const [health, setHealth] = useState<'checking' | 'up' | 'down'>('checking')
  useEffect(() => { let live = true
    fetch('/actuator/health', { cache: 'no-store' }).then(r => r.ok ? r.json() : null)
      .then((v: { status?: string } | null) => { if (live) setHealth(v?.status === 'UP' ? 'up' : 'down') })
      .catch(() => { if (live) setHealth('down') })
    return () => { live = false }
  }, [])
  async function submit(e: FormEvent) {
    e.preventDefault(); if (busy) return; setError(null); setBusy(true)
    try { const result = await authenticate(username.trim(), password); onLogin(result.session, result.client) }
    catch (err) { setError(err instanceof Error ? err.message : 'Échec de connexion.') }
    finally { setBusy(false) }
  }
  return <div className="entry-screen entry-v2">
    <header className="entry-header"><Brand/><div className="entry-header-meta"><span>PLATEFORME DE TRANSACTIONS</span><span className="entry-demo">Démonstration</span></div></header>
    <main className="entry-main">
      <div className="entry-story">
        <div className="entry-title"><span className="entry-index"><i/> SUIVI DES TRANSACTIONS</span><h1>Transferts et<br/><span>règlements.</span></h1><p>Suivez chaque mouvement, de votre compte<br className="desktop-break"/> à son écriture dans le journal comptable.</p></div>
        <div className="rail-art" aria-hidden="true">
          <div className="rail-orbit orbit-one"/><div className="rail-orbit orbit-two"/>
          <svg className="rail-lines" viewBox="0 0 620 260" fill="none"><defs><linearGradient id="rail-gradient"><stop stopColor="#35cba7" stopOpacity="0"/><stop offset=".48" stopColor="#70f4d0"/><stop offset="1" stopColor="#35cba7" stopOpacity="0"/></linearGradient></defs><path className="rail-track" d="M-20 185 H115 Q155 185 185 155 L235 105 Q265 75 310 75 H640"/><path className="rail-track second" d="M-20 215 H135 Q175 215 205 185 L255 135 Q285 105 330 105 H640"/><path className="rail-travel" d="M-20 185 H115 Q155 185 185 155 L235 105 Q265 75 310 75 H640"/><path className="rail-travel second" d="M-20 215 H135 Q175 215 205 185 L255 135 Q285 105 330 105 H640"/></svg>
          <div className="rail-mark"><svg viewBox="0 0 32 32" fill="none"><path d="M7 28V4h11c7 0 10 3.5 10 9s-3 9-10 9h-5v6H7Zm6-12h5c2.7 0 4-1 4-3s-1.3-3-4-3h-5v6Z" fill="currentColor"/></svg></div>
          <span className="rail-caption caption-start">01 / TRANSFERT</span><span className="rail-caption caption-end">02 / ÉCRITURE</span><span className="rail-caption caption-proof">TRANSFERT · COMPTABILITÉ</span>
        </div>
        <div className="entry-principles"><span>Transferts internes et externes</span><span>Comptabilité en partie double</span><span>Suivi des règlements</span></div>
      </div>
      <section className="entry-card login-form-wrap" aria-labelledby="login-title">
        <div className="entry-access-label"><LockKeyhole size={14}/> VOTRE ESPACE</div><div className="entry-card-heading"><h2 id="login-title">Accédez à<br/>votre espace<span>.</span></h2><ArrowUpRight size={27} aria-hidden="true"/></div>
        <div className="identity-label">Choisissez un profil</div>
        <div className="identity-picker">{identities.map((item, index) => <button key={item.username} type="button" disabled={busy} aria-pressed={identity === index} className={`identity-option ${identity === index ? 'selected' : ''}`} onClick={() => { setIdentity(index); setUsername(item.username); setPassword(''); setShowPassword(false); setError(null) }}><span>{item.name}</span>{identity === index && <CheckCircle2 size={13}/>}</button>)}</div>
        <form onSubmit={submit} aria-busy={busy}>
          <label className="field-label" htmlFor="login-email">Adresse de connexion</label><input id="login-email" value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" required disabled={busy} spellCheck={false}/>
          <div className="field-label-row"><label className="field-label" htmlFor="login-password">Mot de passe</label></div>
          <div className="password-control"><KeyRound size={17}/><input id="login-password" type={showPassword ? 'text' : 'password'} required disabled={busy} autoComplete="current-password" placeholder="Votre mot de passe" value={password} onChange={e => setPassword(e.target.value)}/><button className="password-toggle" type="button" aria-label={showPassword ? 'Masquer le mot de passe' : 'Afficher le mot de passe'} aria-pressed={showPassword} onClick={() => setShowPassword(!showPassword)}>{showPassword ? <EyeOff size={17}/> : <Eye size={17}/>}</button></div>
          {error && <ErrorNote text={error}/>}
          <button className="button button-primary login-submit" type="submit" disabled={busy}>{busy ? 'Connexion en cours…' : 'Ouvrir mon espace'} <ArrowRight size={18}/></button>
        </form>
        <div className="entry-health" role="status"><span className={`health-dot ${health}`}/>{health === 'up' ? 'Serveur disponible' : health === 'checking' ? 'Vérification du serveur…' : 'Serveur indisponible'}</div>
      </section>
    </main>
    <footer className="entry-footer"><span>Environnement de démonstration</span><span>Aucun paiement bancaire réel</span></footer>
  </div>
}
