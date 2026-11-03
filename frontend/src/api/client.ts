import type { ApiErrorBody, Session } from './types'

const BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined)?.replace(/\/$/, '')
    ?? 'http://localhost:8080'

const SESSION_KEY = 'booking.session'

// ---------- session storage (wrapped: storage can throw in private windows) ----------

export function loadSession(): Session | null {
    try {
        const raw = sessionStorage.getItem(SESSION_KEY)
        if (!raw) return null
        const s = JSON.parse(raw) as Session
        return s.expiresAt > Date.now() ? s : null
    } catch {
        return null
    }
}

export function saveSession(s: Session | null): void {
    try {
        if (s) sessionStorage.setItem(SESSION_KEY, JSON.stringify(s))
        else sessionStorage.removeItem(SESSION_KEY)
    } catch {
        /* storage unavailable: the user simply has to log in again after a refresh */
    }
}

// ---------- errors ----------

export class ApiClientError extends Error {
    readonly status: number
    readonly code: string
    readonly violations: { field: string; message: string }[]

    constructor(status: number, code: string, message: string, violations: ApiErrorBody['violations'] = []) {
        super(message)
        this.name = 'ApiClientError'
        this.status = status
        this.code = code
        this.violations = violations ?? []
    }
}

/** Anything that is not an ApiClientError (network down, CORS, server not running). */
function networkError(): ApiClientError {
    return new ApiClientError(0, 'NETWORK_ERROR', 'Cannot reach the server. Check that the backend is running.')
}

// ---------- unauthorized hook ----------

let onUnauthorized: () => void = () => {}
export function setUnauthorizedHandler(fn: () => void): void {
    onUnauthorized = fn
}

// ---------- the one HTTP function ----------

export interface RequestOptions {
    method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
    body?: unknown
    idempotencyKey?: string
    /** Public endpoints (login/register) must not send a stale token or trigger the 401 redirect. */
    auth?: boolean
    signal?: AbortSignal
}

export async function api<T>(path: string, opts: RequestOptions = {}): Promise<T> {
    const { method = 'GET', body, idempotencyKey, auth = true, signal } = opts
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey
    if (auth) {
        const s = loadSession()
        if (s) headers.Authorization = `Bearer ${s.token}`
    }

    let res: Response
    try {
        res = await fetch(`${BASE_URL}${path}`, {
            method,
            headers,
            body: body === undefined ? undefined : JSON.stringify(body),
            signal,
        })
    } catch (e) {
        if (e instanceof DOMException && e.name === 'AbortError') throw e
        throw networkError()
    }

    if (res.ok) {
        if (res.status === 204) return undefined as T
        const text = await res.text()
        return (text ? JSON.parse(text) : undefined) as T
    }

    let err: ApiErrorBody | null = null
    try {
        err = (await res.json()) as ApiErrorBody
    } catch {
        /* non-JSON error body */
    }
    if (res.status === 401 && auth) onUnauthorized()
    throw new ApiClientError(
        res.status,
        err?.code ?? 'HTTP_' + res.status,
        err?.message ?? `Request failed (${res.status})`,
        err?.violations,
    )
}