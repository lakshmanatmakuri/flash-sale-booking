import { Link, NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../api/auth/AuthContext'

export default function Layout() {
    const { session, logout } = useAuth()
    return (
        <div className="app">
            <header className="topbar">
                <Link to="/" className="brand">FlashSale</Link>
                <nav className="nav">
                    <NavLink to="/">Events</NavLink>
                    {session && <NavLink to="/bookings">My bookings</NavLink>}
                    {session && <NavLink to="/wallet">Wallet</NavLink>}
                </nav>
                <div className="who">
                    {session ? (
                        <>
                            <span className="muted">{session.email}</span>
                            <button className="btn btn-ghost" onClick={logout}>Log out</button>
                        </>
                    ) : (
                        <>
                            <Link to="/login">Log in</Link>
                            <Link to="/register" className="btn">Sign up</Link>
                        </>
                    )}
                </div>
            </header>
            <main className="content">
                <Outlet />
            </main>
        </div>
    )
}