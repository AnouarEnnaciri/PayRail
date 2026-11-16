import { useCallback, useEffect, useState } from 'react'
import type { ApiClient } from './api'

export function useQuery<T>(api: ApiClient, path: string, initial: T) {
  const [data, setData] = useState<T>(initial)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  const refresh = useCallback(() => setVersion(v => v + 1), [])
  useEffect(() => {
    let cancelled = false
    if (!path) { setLoading(false); setData(initial); return () => { cancelled = true } }
    setLoading(true)
    setError(null)
    api.get<T>(path).then(value => {
      if (!cancelled) setData(value)
    }).catch((e: Error) => {
      if (!cancelled) setError(e.message)
    }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [api, path, version])
  return { data, loading, error, refresh }
}
