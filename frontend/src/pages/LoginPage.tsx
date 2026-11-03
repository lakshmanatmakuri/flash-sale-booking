import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import ErrorBanner from '../components/ErrorBanner'
import { useAuth } from '../api/auth/AuthContext'

export default function LoginPage() {
    const { login } = useAuth()
    const navigate = useNavigate()
    const location = useLocation()
    const from = (location.state as { from?: string } | null)?.from ?? '/'

    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<unknown>(null)

    async function submit(e: FormEvent) {
        e.preventDefault()
        if (busy) return                       // a double click must not send two logins
        setBusy(true)
        setError(null)
        try {
            await login(email.trim(), password)
            navigate(from, { replace: true })
        } catch (err) {
            setError(err)
        } finally {
            setBusy(false)
        }
    }

    return (
        <form className="card form" onSubmit={submit}>
            <h1>Log in</h1>
            <ErrorBanner error={error} />
            <label>
                Email
                <input type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
            </label>
            <label>
                Password
                <input type="password" autoComplete="current-password" required value={password}
                       onChange={(e) => setPassword(e.target.value)} />
            </label>
            <button className="btn btn-primary" disabled={busy}>{busy ? 'Signing in…' : 'Log in'}</button>
            <p className="muted">No account? <Link to="/register">Sign up</Link></p>
        </form>
    )
}