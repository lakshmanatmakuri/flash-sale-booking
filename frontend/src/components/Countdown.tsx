import { useEffect, useRef, useState } from 'react'
import { serverNow } from '../api/client'
import { formatRemaining } from '../lib/format'

/** Remaining time is recomputed from the absolute deadline on every tick, so drift and tab throttling cannot accumulate. */
export function useRemainingMs(expiresAt: string): number {
    const [now, setNow] = useState(() => serverNow())
    useEffect(() => {
        setNow(serverNow())
        const t = setInterval(() => setNow(serverNow()), 1000)
        return () => clearInterval(t)
    }, [expiresAt])
    return Math.max(0, Date.parse(expiresAt) - now)
}

export default function Countdown({ expiresAt, onExpire }: { expiresAt: string; onExpire?: () => void }) {
    const remaining = useRemainingMs(expiresAt)
    const fired = useRef(false)

    useEffect(() => {
        if (remaining === 0 && !fired.current) {
            fired.current = true
            onExpire?.()
        }
        if (remaining > 0) fired.current = false       // renewed: arm again
    }, [remaining, onExpire])

    if (remaining === 0) return <span className="countdown countdown-expired" role="timer">Expired</span>
    return (
        <span className={'countdown' + (remaining < 60_000 ? ' countdown-low' : '')} role="timer" aria-label="Time left on hold">
      {formatRemaining(remaining)}
    </span>
    )
}