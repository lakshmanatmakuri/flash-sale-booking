import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './api/auth/AuthContext'
import RequireAuth from './api/auth/RequireAuth'
import Layout from './components/Layout'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import RegisterPage from './pages/RegisterPage'

export default function App() {
    return (
        <BrowserRouter>
            <AuthProvider>
                <Routes>
                    <Route element={<Layout />}>
                        <Route path="/login" element={<LoginPage />} />
                        <Route path="/register" element={<RegisterPage />} />
                        {/* Events are public to browse; everything that spends money sits behind RequireAuth (added in 7b/7c). */}
                        <Route path="/" element={<HomePage />} />
                        <Route element={<RequireAuth />}>
                            {/* /events/:id, /bookings, /wallet are added in 7b and 7c */}
                        </Route>
                        <Route path="*" element={<p>Page not found.</p>} />
                    </Route>
                </Routes>
            </AuthProvider>
        </BrowserRouter>
    )
}