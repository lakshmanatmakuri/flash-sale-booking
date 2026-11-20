import { useMemo } from 'react'
import type { SeatMapItem } from '../api/types'
import { formatMoney } from '../lib/format'

interface Props {
    seats: SeatMapItem[]
    selected: ReadonlySet<string>
    myHeldSeatIds: ReadonlySet<string>
    maxSelectable: number
    onToggle: (eventSeatId: string) => void
}

const STATUS_LABEL: Record<string, string> = {
    AVAILABLE: 'available', HELD: 'held by someone', PAYMENT_PENDING: 'payment in progress',
    BOOKED: 'sold', CANCELLED: 'unavailable',
}

export default function SeatMap({ seats, selected, myHeldSeatIds, maxSelectable, onToggle }: Props) {
    // section -> row -> seats, sorted for a stable layout
    const grouped = useMemo(() => {
        const sections = new Map<string, Map<string, SeatMapItem[]>>()
        for (const s of seats) {
            const rows = sections.get(s.section) ?? new Map<string, SeatMapItem[]>()
            const list = rows.get(s.row) ?? []
            list.push(s)
            rows.set(s.row, list)
            sections.set(s.section, rows)
        }
        return [...sections.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([section, rows]) => ({
            section,
            rows: [...rows.entries()]
                .sort(([a], [b]) => a.localeCompare(b, undefined, { numeric: true }))
                .map(([row, list]) => ({ row, seats: list.sort((x, y) => (x.seatNumber ?? 0) - (y.seatNumber ?? 0)) })),
        }))
    }, [seats])

    const atLimit = selected.size >= maxSelectable

    return (
        <div className="seatmap">
            <ul className="legend" aria-label="Legend">
                <li><span className="seat seat-available" aria-hidden /> Available</li>
                <li><span className="seat seat-selected" aria-hidden /> Selected</li>
                <li><span className="seat seat-mine" aria-hidden /> Yours (held)</li>
                <li><span className="seat seat-held" aria-hidden /> Held / pending</li>
                <li><span className="seat seat-booked" aria-hidden /> Sold</li>
            </ul>

            {grouped.map(({ section, rows }) => (
                <section key={section} className="seat-section" aria-label={`Section ${section}`}>
                    <h3>Section {section}</h3>
                    {rows.map(({ row, seats: rowSeats }) => (
                        <div key={row} className="seat-row">
                            <span className="row-label">{row}</span>
                            <div className="seat-row-seats">
                                {rowSeats.map((s) => {
                                    const mine = myHeldSeatIds.has(s.eventSeatId)
                                    const isSelected = selected.has(s.eventSeatId)
                                    const selectable = s.status === 'AVAILABLE' && !mine
                                    const blockedByLimit = selectable && !isSelected && atLimit
                                    const cls = mine ? 'seat-mine' : isSelected ? 'seat-selected'
                                        : s.status === 'AVAILABLE' ? 'seat-available'
                                            : s.status === 'BOOKED' ? 'seat-booked' : 'seat-held'
                                    const state = mine ? 'held by you' : isSelected ? 'selected' : STATUS_LABEL[s.status]
                                    return (
                                        <button
                                            key={s.eventSeatId}
                                            type="button"
                                            className={`seat ${cls}`}
                                            disabled={!selectable || blockedByLimit}
                                            aria-pressed={selectable ? isSelected : undefined}
                                            aria-label={`Section ${s.section}, row ${s.row}, seat ${s.seatNumber ?? '?'}, ${state}, ${formatMoney(s.price)}`}
                                            title={`${s.row}${s.seatNumber ?? ''} · ${formatMoney(s.price)} · ${state}`}
                                            onClick={() => onToggle(s.eventSeatId)}
                                        >
                                            {s.seatNumber ?? ''}
                                        </button>
                                    )
                                })}
                            </div>
                        </div>
                    ))}
                </section>
            ))}
        </div>
    )
}