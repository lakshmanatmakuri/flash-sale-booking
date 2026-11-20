import { useState } from 'react'
import { Link } from 'react-router-dom'
import { releaseHold, renewHold } from '../api/events'
import type { Hold, SeatMapItem } from '../api/types'
import { formatMoney } from '../lib/format'
import Countdown from './Countdown'
import ErrorBanner from './ErrorBanner'

interface Props {
    holds: Hold[]
    seatsById: ReadonlyMap<string, SeatMapItem>
    onChanged: () => void
    onExpire: () => void
}

export default function HoldsPanel({ holds, seatsById, onChanged, onExpire }: Props) {
    const [busyId, setBusyId] = useState<string | null>(null)
    const [error, setError] = useState<unknown>(null)
    if (holds.length === 0) return null

    const currency = holds[0].currency
    const total = holds.reduce((sum, h) => sum + h.lockedPrice, 0)

    async function act(id: string, fn: (id: string) => Promise<unknown>) {
        if (busyId) return
        setBusyId(id)
        setError(null)
        try { await fn(id) } catch (e) { setError(e) } finally { setBusyId(null); onChanged() }   // always re-sync with the server
    }

    return (
        <aside className="card holds" aria-label="Your held seats">
            <h2>Your held seats</h2>
            <ErrorBanner error={error} />
            <ul className="hold-list">
                {holds.map((h) => {
                    const s = seatsById.get(h.eventSeatId)
                    return (
                        <li key={h.id}>
                            <span>{s ? `${s.section} · ${s.row}${s.seatNumber ?? ''}` : 'Seat'}</span>
                            <span>{formatMoney(h.lockedPrice, h.currency)}</span>
                            <Countdown expiresAt={h.expiresAt} onExpire={onExpire} />
                            <span className="hold-actions">
                <button className="btn" disabled={busyId !== null} onClick={() => act(h.id, renewHold)}>Extend</button>
                <button className="btn btn-ghost" disabled={busyId !== null} onClick={() => act(h.id, releaseHold)}>Release</button>
              </span>
                        </li>
                    )
                })}
            </ul>
            <p className="hold-total"><strong>Total {formatMoney(total, currency)}</strong></p>
            <Link className="btn btn-primary" to="/checkout" state={{ holdIds: holds.map((h) => h.id) }}>
                Continue to payment
            </Link>
        </aside>
    )
}