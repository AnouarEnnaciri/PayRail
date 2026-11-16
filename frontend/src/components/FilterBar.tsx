import { label } from '../lib/format'
import { Search, SlidersHorizontal } from 'lucide-react'
export function FilterBar({ search, onSearch, status, onStatus, options }: {
  search: string; onSearch: (s: string) => void
  status?: string; onStatus?: (s: string) => void; options?: string[]
}) {
  return <div className="filter-bar"><label className="search-input"><Search size={17}/><input aria-label="Rechercher" placeholder="Rechercher par ID, référence, statut…" value={search} onChange={e => onSearch(e.target.value)}/></label>
    {onStatus && <label className="filter-select"><SlidersHorizontal size={16}/><select aria-label="Filtrer par statut" value={status} onChange={e => onStatus(e.target.value)}><option value="ALL">Tous les statuts</option>{(options || []).map(o => <option value={o} key={o}>{label(o)}</option>)}</select></label>}
  </div>
}
