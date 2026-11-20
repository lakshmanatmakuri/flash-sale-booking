import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { createHolds, getEvent, getSeatMap, getVenue, myHolds } from '../api/events'
import { useAuth } from '../api/auth/AuthContext'
import ErrorBanner from '../components/ErrorBanner'
import HoldsPanel from '../components/HoldsPanel'
import SeatMap from '../components/SeatMap'
import Spinner from '../components/Spinner'
import { formatDateTime, formatMoney } from '../lib/format'
import { useQuery } from '../lib/useQuery'
import { newIdempotencyKey } from '../lib/idempotency'

const POLL_MS = 10_000
const MAX_PER_REQUEST = 10            // backend: CreateHoldRequest allows at most 10 seats

export default function EventPage() {
    const { id = '' } = useParams()
    const { session } = useAuth()
    const location = useLocation()
    const loggedIn = !!session

    const event = useQuery((s) => getEvent(id, s), [id])
    const venue = useQuery(
        (s) => (loggedIn && event.data ? getVenue(event.data.venueId, s).catch(() => null) : Promise.resolve(null)),
        [loggedIn, event.data?.venueId],
    )
    const seats = useQuery((s) => (loggedIn ? getSeatMap(id, s) : Promise.resolve([])), [id, loggedIn])
    const holds = useQuery((s) => (loggedIn ? myHolds(s) : Promise.resolve([])), [id, loggedIn])

    const [selected, setSelected] = useState<Set<string>>(new Set())
    const [holding, setHolding] = useState(false)
    const [actionError, setActionError] = useState<unknown>(null)

    const seatList = seats.data ?? []
    const seatsById = useMemo(() => new Map(seatList.map((s) => [s.eventSeatId, s])), [seatList])
    const myHere = useMemo(() => (holds.data ?? []).filter((h) => h.eventId === id && h.status === 'ACTIVE'), [holds.data, id])
    const myHeldSeatIds = useMemo(() => new Set(myHere.map((h) => h.eventSeatId)), [myHere])

    // Drop seats from the selection when someone else takes them (the map refreshes underneath the user).
    useEffect(() => {
        setSelected((prev) => {
            const next = new Set([...prev].filter((sid) => seatsById.get(sid)?.status === 'AVAILABLE'))
            return next.size === prev.size ? prev : next
        })
    }, [seatsById])

    // Poll while the tab is visible; refresh immediately when the user comes back to the tab.
    const reloadSeats = seats.reload, reloadHolds = holds.reload
    const refreshAll = useCallback(() => { reloadSeats(); reloadHolds() }, [reloadSeats, reloadHolds])
    useEffect(() => {
        if (!loggedIn) return
        const tick = () => { if (document.visibilityState === 'visible') refreshAll() }
        const t = setInterval(tick, POLL_MS)
        document.addEventListener('visibilitychange', tick)
        return () => { clearInterval(t); document.removeEventListener('visibilitychange', tick) }
    }, [loggedIn, refreshAll])

    // Many holds can expire in the same second: collapse them into one refresh.
    const expireTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
    const onExpire = useCallback(() => {
        if (expireTimer.current) clearTimeout(expireTimer.current)
        expireTimer.current = setTimeout(refreshAll, 300)
    }, [refreshAll])
    useEffect(() => () => { if (expireTimer.current) clearTimeout(expireTimer.current) }, [])

    const e = event.data
    const maxSelectable = Math.max(0, Math.min(MAX_PER_REQUEST, (e?.maxSeatsPerUser ?? 0) - myHere.length))

    const toggle = (seatId: string) =>
        setSelected((prev) => {
            const next = new Set(prev)
            if (next.has(seatId)) next.delete(seatId)
            else if (next.size < maxSelectable) next.add(seatId)
            return next
        })

    const selectedTotal = [...selected].reduce((sum, sid) => sum + (seatsById.get(sid)?.price ?? 0), 0)

    async function hold() {
        if (holding || selected.size === 0) return

        setHolding(true)
        setActionError(null)

        // Generate one key for this hold action
        const idempotencyKey = newIdempotencyKey()

        try {
            await createHolds(id, [...selected], idempotencyKey)
            setSelected(new Set())
        } catch (err) {
            setActionError(err)
        } finally {
            setHolding(false)
            refreshAll()
        }
    }

    if (event.loading) return <Spinner />
    if (event.error || !e) return <ErrorBanner error={event.error ?? new Error('not found')} />

    return (
        <section>
            <p><Link to="/">← All events</Link></p>
            <h1>{e.name}</h1>
            <p className="muted">
                {formatDateTime(e.startsAt)}
                {venue.data && ` · ${venue.data.name}, ${venue.data.city}`}
            </p>
            {e.description && <p>{e.description}</p>}
            <p>
                <span className={`pill pill-${e.status.toLowerCase()}`}>{e.status.replace('_', ' ')}</span>{' '}
                <span className="muted">{e.availableSeats} seats left · up to {e.maxSeatsPerUser} per person</span>
            </p>
            <p className="muted small">Sale: {formatDateTime(e.saleStartsAt)} to {formatDateTime(e.saleEndsAt)}</p>

            {!loggedIn ? (
                <div className="card">
                    <p><Link to="/login" state={{ from: location.pathname }}>Log in</Link> to see the seat map and hold seats.</p>
                </div>
            ) : (
                <>
                    <HoldsPanel holds={myHere} seatsById={seatsById} onChanged={refreshAll} onExpire={onExpire} />
                    <ErrorBanner error={seats.error ?? holds.error ?? actionError} />
                    {seats.loading ? <Spinner label="Loading seats…" /> : (
                        <>
                            <SeatMap seats={seatList} selected={selected} myHeldSeatIds={myHeldSeatIds}
                                     maxSelectable={maxSelectable} onToggle={toggle} />
                            <div className="actionbar" role="region" aria-label="Selection">
                <span>
                  {selected.size === 0
                      ? (maxSelectable === 0 ? 'You have reached the per-person limit.' : 'Pick up to ' + maxSelectable + ' seat(s).')
                      : `${selected.size} selected · ${formatMoney(selectedTotal)}`}
                </span>
                                <button className="btn btn-primary" disabled={holding || selected.size === 0} onClick={hold}>
                                    {holding ? 'Holding…' : 'Hold seats'}
                                </button>
                            </div>
                        </>
                    )}
                </>
            )}
        </section>
    )
}