import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Countdown from '../components/Countdown'
import { formatRemaining } from '../lib/format'

describe('formatRemaining', () => {
    it('formats minutes and seconds', () => {
        expect(formatRemaining(125_000)).toBe('2:05')
        expect(formatRemaining(999)).toBe('0:01')       // rounds up: never shows 0:00 while time is left
    })
})

describe('Countdown', () => {
    beforeEach(() => { vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01T10:00:00Z')) })
    afterEach(() => vi.useRealTimers())

    it('counts down from the absolute deadline and fires onExpire once', () => {
        const onExpire = vi.fn()
        render(<Countdown expiresAt="2026-01-01T10:01:05Z" onExpire={onExpire} />)
        expect(screen.getByRole('timer')).toHaveTextContent('1:05')

        act(() => { vi.advanceTimersByTime(5_000) })
        expect(screen.getByRole('timer')).toHaveTextContent('1:00')

        act(() => { vi.advanceTimersByTime(70_000) })
        expect(screen.getByRole('timer')).toHaveTextContent('Expired')
        expect(onExpire).toHaveBeenCalledTimes(1)
    })

    it('is correct even if a background tab skipped many ticks', () => {
        render(<Countdown expiresAt="2026-01-01T10:05:00Z" />)
        act(() => { vi.setSystemTime(new Date('2026-01-01T10:04:00Z')); vi.advanceTimersByTime(1000) })
        expect(screen.getByRole('timer')).toHaveTextContent('0:59')     // not "4:59": it never decrements a counter
    })
})