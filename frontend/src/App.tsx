import { useEffect, useState } from 'react'
import Login from './components/Login'
import Shell from './components/Shell'
import TransferDetail from './pages/TransferDetail'
import { ClientAccounts, ClientLedger, ClientOverview, ClientTransfers } from './pages/ClientPages'
import { Metrics, OpsOverview, OpsTransfers, Reconciliation, ReliabilityLab, SettlementQueue } from './pages/OpsPages'
import type { ApiClient } from './lib/api'
import type { Session, View } from './lib/types'

type Identity = { session: Session; api: ApiClient }
export default function App() {
  const [identity, setIdentity] = useState<Identity | null>(null)
  const [view, setView] = useState<View>('overview')
  const [previousView, setPreviousView] = useState<View>('overview')
  const [selectedTransfer, setSelectedTransfer] = useState('')
  const [ledgerAccountId, setLedgerAccountId] = useState('')
  const [dark, setDark] = useState(() => localStorage.getItem('payrail-color-scheme') === 'dark')
  useEffect(() => { document.documentElement.dataset.theme = dark ? 'dark' : 'light'; localStorage.setItem('payrail-color-scheme', dark ? 'dark' : 'light') }, [dark])
  const openTransfer = (id: string) => { setPreviousView(view);setSelectedTransfer(id);setView('detail');window.scrollTo({top:0,behavior:'smooth'}) }
  const navigate = (v: View) => { setView(v);window.scrollTo({top:0,behavior:'smooth'}) }
  const login = (session: Session, api: ApiClient) => {setIdentity({ session, api });setView(session.role==='OPS'?'ops-overview':'overview')}
  const logout = () => {setIdentity(null);setSelectedTransfer('');setLedgerAccountId('');setView('overview')}
  if (!identity) return <Login onLogin={login}/>
  const {session,api} = identity
  const child = view==='detail' ? <TransferDetail key={selectedTransfer} api={api} role={session.role} id={selectedTransfer} onBack={()=>navigate(previousView)}/>
    : session.role==='CLIENT' ? (
      view==='accounts' ? <ClientAccounts api={api} onLedger={id=>{setLedgerAccountId(id);navigate('ledger')}}/> :
      view==='transfers' ? <ClientTransfers api={api} onOpen={openTransfer}/> :
      view==='ledger' ? <ClientLedger api={api} initialAccountId={ledgerAccountId} onOpen={openTransfer}/> :
      <ClientOverview api={api} navigate={navigate} openTransfer={openTransfer}/>
    ) : (
      view==='ops-transfers' ? <OpsTransfers api={api} openTransfer={openTransfer}/> :
      view==='outbox' ? <SettlementQueue api={api} openTransfer={openTransfer}/> :
      view==='reconciliation' ? <Reconciliation api={api} openTransfer={openTransfer}/> :
      view==='reliability' ? <ReliabilityLab api={api} openTransfer={openTransfer}/> :
      view==='metrics' ? <Metrics api={api}/> :
      <OpsOverview api={api} navigate={navigate} openTransfer={openTransfer}/>
    )
  return <Shell session={session} view={view} onNavigate={navigate} onLogout={logout} dark={dark} onToggleTheme={()=>setDark(!dark)}>{child}</Shell>
}
