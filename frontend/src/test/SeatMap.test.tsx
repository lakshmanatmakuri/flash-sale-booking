import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import SeatMap from '../components/SeatMap'
import type { SeatMapItem } from '../api/types'

const seat = (n: number, status: SeatMapItem['status'] = 'AVAILABLE'): SeatMapItem => ({
    eventSeatId: `s${n}`, section: 'A', row: '1', seatNumber: n, status, price: 500,
})

describe('SeatMap', () => {
    it('lets the user toggle available seats only', async () => {
        const onToggle = vi.fn()
        render(<SeatMap seats={[seat(1), seat(2, 'BOOKED'), seat(3, 'HELD')]} selected={new Set()}
                        myHeldSeatIds={new Set()} maxSelectable={4} onToggle={onToggle} />)
        await userEvent.click(screen.getByRole('button', { name: /seat 1, available/i }))
        expect(onToggle).toHaveBeenCalledWith('s1')
        expect(screen.getByRole('button', { name: /seat 2, sold/i })).toBeDisabled()
        expect(screen.getByRole('button', { name: /seat 3, held by someone/i })).toBeDisabled()
    })

    it('blocks further selection at the limit but keeps selected seats clickable to undo', () => {
        render(<SeatMap seats={[seat(1), seat(2)]} selected={new Set(['s1'])} myHeldSeatIds={new Set()}
                        maxSelectable={1} onToggle={() => {}} />)
        expect(screen.getByRole('button', { name: /seat 2/i })).toBeDisabled()
        expect(screen.getByRole('button', { name: /seat 1, selected/i })).toBeEnabled()
    })

    it('shows seats you hold as yours and not selectable', () => {
        render(<SeatMap seats={[seat(1, 'HELD')]} selected={new Set()} myHeldSeatIds={new Set(['s1'])}
                        maxSelectable={4} onToggle={() => {}} />)
        expect(screen.getByRole('button', { name: /held by you/i })).toBeDisabled()
    })
})