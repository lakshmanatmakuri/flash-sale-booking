import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoginPage from '../pages/LoginPage'
import { AuthProvider } from "../api/auth/AuthContext";

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function renderLogin() {
    return render(
        <MemoryRouter initialEntries={['/login']}>
            <AuthProvider>
                <Routes>
                    <Route path="/login" element={<LoginPage />} />
                    <Route path="/" element={<p>home</p>} />
                </Routes>
            </AuthProvider>
        </MemoryRouter>,
    )
}

describe('LoginPage', () => {
    beforeEach(() => { sessionStorage.clear(); vi.restoreAllMocks() })

    it('shows the server message when credentials are wrong', async () => {
        vi.spyOn(globalThis, 'fetch').mockResolvedValue(
            json(401, { status: 401, code: 'BAD_CREDENTIALS', message: 'Invalid email or password' }))
        renderLogin()
        await userEvent.type(screen.getByLabelText(/email/i), 'a@b.com')
        await userEvent.type(screen.getByLabelText(/password/i), 'wrongpass1')
        await userEvent.click(screen.getByRole('button', { name: /log in/i }))
        expect(await screen.findByRole('alert')).toHaveTextContent('Invalid email or password')
    })

    it('stores the session and navigates home on success', async () => {
        vi.spyOn(globalThis, 'fetch').mockResolvedValue(json(200, {
            accessToken: 't', tokenType: 'Bearer', expiresInSeconds: 3600, userId: 'u1', email: 'a@b.com', roles: ['USER'],
        }))
        renderLogin()
        await userEvent.type(screen.getByLabelText(/email/i), 'a@b.com')
        await userEvent.type(screen.getByLabelText(/password/i), 'correct-horse')
        await userEvent.click(screen.getByRole('button', { name: /log in/i }))
        expect(await screen.findByText('home')).toBeInTheDocument()
        expect(sessionStorage.getItem('booking.session')).toContain('"token":"t"')
    })
})