import { ArrowDownLeft, ArrowUpRight, ExternalLink, FileClock } from 'lucide-react'
import type { Transfer } from '../lib/types'
import { moment, money } from '../lib/format'
import { CopyId, Empty, Pill, TableWrap, ViewLink } from './Ui'

export default function TransferTable({ transfers, onOpen, ownerAccounts }: {
  transfers: Transfer[]; onOpen: (id: string) => void; ownerAccounts?: string[]
}) {
  if (!transfers.length) return <Empty title="Aucun transfert trouvé" detail="Les opérations réelles apparaîtront ici après leur enregistrement." icon={<FileClock size={22}/>}/>
  return <TableWrap><thead><tr><th>Opération</th><th>De / vers</th><th>Montant du transfert</th><th>État</th><th>Date</th><th></th></tr></thead>
    <tbody>{transfers.map(t => {
      const incoming = ownerAccounts?.includes(t.receiverAccountId || '')
      return <tr key={t.id}><td><div className="cell-main"><span className={`transaction-icon ${incoming ? 'incoming' : ''}`}>{t.transferType === 'EXTERNAL' ? <ExternalLink size={17}/> : incoming ? <ArrowDownLeft size={17}/> : <ArrowUpRight size={17}/>}</span>
        <span><strong>{t.transferType === 'EXTERNAL' ? 'Transfert externe' : 'Transfert interne'}</strong><small><CopyId value={t.id}/></small></span></div></td>
        <td className="cell-reference">{incoming ? t.senderReference : t.transferType === 'EXTERNAL' ? t.destinationReference : t.receiverReference}</td>
        <td className="cell-money">{money(t.amountMinor)}</td><td><Pill status={t.status}/></td>
        <td className="cell-muted">{moment(t.createdAt)}</td><td><ViewLink onClick={() => onOpen(t.id)}>Détail</ViewLink></td></tr>
    })}</tbody></TableWrap>
}
