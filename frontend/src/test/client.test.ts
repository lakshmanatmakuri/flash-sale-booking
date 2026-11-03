import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiClientError, saveSession, setUnauthorizedHandler } from '../api/client'

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

describe('api client', () => {
    beforeEach(() => { sessionStorage.clear(); vi.restoreAllMocks() })
    afterEach(() => setUnauthorizedHandler(() => {}))

    it('sends the bearer token and the idempotency key', async () => {
        saveSession({ token: 'abc', userId: 'u', email: 'a@b.c', roles: [], expiresAt: Date.now() + 60_000 })
        const spy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(200, { ok: true }))
        await api('/api/payments', { method: 'POST', body: { x: 1 }, idempotencyKey: 'key-1' })
        const init = spy.mock.calls[0][1] as RequestInit
        const h = init.headers as Record<string, string>
        expect(h.Authorization).toBe('Bearer abc')
        expect(h['Idempotency-Key']).toBe('key-1')
        expect(h['Content-Type']).toBe('application/json')
    })

    it('turns the backend error shape into a typed error', async () => {
        vi.spyOn(globalThis, 'fetch').mockResolvedValue(
            json(409, { status: 409, code: 'SEAT_UNAVAILABLE', message: 'Seat taken' }))
        const err = await api('/x').catch((e) => e)
        expect(err).toBeInstanceOf(ApiClientError)
        expect(err).toMatchObject({ status: 409, code: 'SEAT_UNAVAILABLE', message: 'Seat taken' })
    })

    it('reports an unreachable server as NETWORK_ERROR', async () => {
        vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'))
        await expect(api('/x')).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' })
    })

    it('calls the unauthorized handler on 401 for authenticated calls only', async () => {
        const handler = vi.fn()
        setUnauthorizedHandler(handler)
        vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(401, { status: 401, code: 'UNAUTHENTICATED', message: 'no' }))
        await api('/private').catch(() => {})
        expect(handler).toHaveBeenCalledTimes(1)
        await api('/api/auth/login', { auth: false, method: 'POST', body: {} }).catch(() => {})
        expect(handler).toHaveBeenCalledTimes(1)          // a wrong password must not look like an expired session
    })
})