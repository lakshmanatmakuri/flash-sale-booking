import { useState } from 'react'
import { Link } from 'react-router-dom'
import { listEvents } from '../api/events'
import ErrorBanner from '../components/ErrorBanner'
import Spinner from '../components/Spinner'
import { formatDateTime } from '../lib/format'
import { useQuery } from '../lib/useQuery'

const PAGE_SIZE = 12

export default function HomePage() {
    const [page, setPage] = useState(0)
    const { data, error, loading } = useQuery((signal) => listEvents(page, PAGE_SIZE, signal), [page])

    return (
        <section>
            <h1>Events</h1>
            <ErrorBanner error={error} />
            {loading && <Spinner />}
            {data && data.content.length === 0 && <p className="muted">No events yet.</p>}
            {data && (
                <ul className="event-grid">
                    {data.content.map((e) => (
                        <li key={e.id} className="card event-card">
                            <h2><Link to={`/events/${e.id}`}>{e.name}</Link></h2>
                            <p className="muted">{formatDateTime(e.startsAt)}</p>
                            <p><span className={`pill pill-${e.status.toLowerCase()}`}>{e.status.replace('_', ' ')}</span></p>
                            <p className="muted small">Sale: {formatDateTime(e.saleStartsAt)} to {formatDateTime(e.saleEndsAt)}</p>
                        </li>
                    ))}
                </ul>
            )}
            {data && data.totalPages > 1 && (
                <nav className="pager" aria-label="Pagination">
                    <button className="btn" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>Previous</button>
                    <span className="muted">Page {data.page + 1} of {data.totalPages}</span>
                    <button className="btn" disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>Next</button>
                </nav>
            )}
        </section>
    )
}