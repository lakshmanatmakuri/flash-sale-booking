import { api } from './client'
import type { AuthResponse, Session } from './types'

function toSession(r: AuthResponse): Session {
    return {
        token: r.accessToken,
        userId: r.userId,
        email: r.email,
        roles: r.roles,
        expiresAt: Date.now() + r.expiresInSeconds * 1000,
    }
}

export async function login(email: string, password: string): Promise<Session> {
    return toSession(await api<AuthResponse>('/api/auth/login', { method: 'POST', body: { email, password }, auth: false }))
}

export async function register(email: string, password: string, fullName: string): Promise<Session> {
    return toSession(
        await api<AuthResponse>('/api/auth/register', { method: 'POST', body: { email, password, fullName }, auth: false }),
    )
}