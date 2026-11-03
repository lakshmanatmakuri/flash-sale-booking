import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

export default function RequireAuth() {
    const { session } = useAuth()
    const location = useLocation()
    if (!session) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
    return <Outlet />
}