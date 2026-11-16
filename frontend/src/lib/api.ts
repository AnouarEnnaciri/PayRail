import type { Session } from './types'

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); this.name = 'ApiError' }
}

export class ApiClient {
  private authorization: string
  private session: Session

  constructor(username: string, password: string, session: Session) {
    // Stored only in component memory. Never put passwords, tokens or Basic auth in localStorage.
    this.authorization = `Basic ${btoa(Array.from(new TextEncoder().encode(`${username}:${password}`), b => String.fromCharCode(b)).join(''))}`
    this.session = session
  }

  async get<T>(path: string): Promise<T> { return this.request<T>('GET', path) }
  async post<T>(path: string, data?: unknown, idempotencyKey?: string): Promise<T> {
    return this.request<T>('POST', path, data, idempotencyKey)
  }

  private async request<T>(method: 'GET' | 'POST', path: string, data?: unknown, key?: string): Promise<T> {
    const headers: Record<string, string> = { Authorization: this.authorization, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' }
    if (method === 'POST') {
      headers[this.session.csrfHeaderName] = this.session.csrfToken
      if (key) headers['Idempotency-Key'] = key
      if (data !== undefined) headers['Content-Type'] = 'application/json'
    }
    let response: Response
    try {
      response = await fetch(path, {
        method, headers, credentials: 'same-origin', cache: 'no-store',
        body: data === undefined ? undefined : JSON.stringify(data),
      })
    } catch {
      throw new ApiError(0, 'Connexion au serveur indisponible. Réessayez dans quelques instants.')
    }
    const raw = await response.text()
    const json: unknown = raw ? (() => { try { return JSON.parse(raw) } catch { return null } })() : null
    if (!response.ok) {
      const problem = json as { detail?: string; title?: string; message?: string } | null
      const reason = problem?.detail || problem?.message || problem?.title || `Erreur HTTP ${response.status}`
      throw new ApiError(response.status, response.status === 401 ? 'Session non autorisée. Veuillez vous reconnecter.' : reason)
    }
    return json as T
  }
}

export async function authenticate(username: string, password: string): Promise<{ session: Session; client: ApiClient }> {
  const encoded = Array.from(new TextEncoder().encode(`${username}:${password}`), b => String.fromCharCode(b)).join('')
  let response: Response
  try {
    response = await fetch('/api/ui/session', {
      method: 'GET', headers: { Authorization: `Basic ${btoa(encoded)}`, Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' },
      credentials: 'same-origin', cache: 'no-store',
    })
  } catch { throw new ApiError(0, 'Serveur inaccessible. Réessayez dans quelques instants.') }
  if (!response.ok) {
    throw new ApiError(response.status, response.status === 401 ? 'Identifiants incorrects.' : `Connexion refusée (${response.status}).`)
  }
  const session = await response.json() as Session
  if (!['CLIENT', 'OPS'].includes(session.role) || !session.csrfToken || !session.csrfHeaderName) {
    throw new ApiError(0, 'Configuration de session ou de protection CSRF invalide.')
  }
  return { session, client: new ApiClient(username, password, session) }
}
