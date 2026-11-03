import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import ErrorBanner from '../components/ErrorBanner'
import { useAuth } from '../api/auth/AuthContext'

export default function RegisterPage() {
    const { register } = useAuth()
    const navigate = useNavigate()
    const [fullName, setFullName] = useState('')
    const [email, setEmail] = useState('')
    const [password, setPassword] = useState('')
    const [busy, setBusy] = useState(false)
    const [error, setError] = useState<unknown>(null)

    async function submit(e: FormEvent) {
        e.preventDefault()
        if (busy) return
        setBusy(true)
        setError(null)
        try {
            await register(email.trim(), password, fullName.trim())
            navigate('/', { replace: true })
        } catch (err) {
            setError(err)
        } finally {
            setBusy(false)
        }
    }

    return (
        <form className="card form" onSubmit={submit}>
            <h1>Create account</h1>
            <ErrorBanner error={error} />
            <label>
                Full name
                <input required maxLength={150} autoComplete="name" value={fullName} onChange={(e) => setFullName(e.target.value)} />
            </label>
            <label>
                Email
                <input type="email" required autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />
            </label>
            <label>
                Password <span className="muted">(8 to 72 characters)</span>
                <input type="password" required minLength={8} maxLength={72} autoComplete="new-password"
                       value={password} onChange={(e) => setPassword(e.target.value)} />
            </label>
            <button className="btn btn-primary" disabled={busy}>{busy ? 'Creating…' : 'Sign up'}</button>
            <p className="muted">Already registered? <Link to="/login">Log in</Link></p>
        </form>
    )
}