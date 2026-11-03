import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as authApi from '../auth'
import { loadSession, saveSession, setUnauthorizedHandler } from '../client'
import type { Session } from '../types'

interface AuthState {
    session: Session | null
    login: (email: string, password: string) => Promise<void>
    register: (email: string, password: string, fullName: string) => Promise<void>
    logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
    const [session, setSession] = useState<Session | null>(() => loadSession())

    const logout = useCallback(() => {
        saveSession(null)
        setSession(null)
    }, [])

    // Any 401 from any API call ends the session in one place.
    useEffect(() => setUnauthorizedHandler(logout), [logout])

    // Log out exactly when the token expires, instead of waiting for a failing request.
    useEffect(() => {
        if (!session) return
        const ms = session.expiresAt - Date.now()
        if (ms <= 0) { logout(); return }
        const t = setTimeout(logout, Math.min(ms, 2_147_483_647))   // setTimeout max is ~24.8 days
        return () => clearTimeout(t)
    }, [session, logout])

    const accept = useCallback((s: Session) => {
        saveSession(s)
        setSession(s)
    }, [])

    const value = useMemo<AuthState>(
        () => ({
            session,
            login: async (email, password) => accept(await authApi.login(email, password)),
            register: async (email, password, fullName) => accept(await authApi.register(email, password, fullName)),
            logout,
        }),
        [session, accept, logout],
    )

    return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
    const ctx = useContext(AuthContext)
    if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>')
    return ctx
}