const DEFAULT_CURRENCY = (import.meta.env.VITE_CURRENCY as string | undefined) ?? 'INR'

export function formatMoney(amount: number, currency: string = DEFAULT_CURRENCY): string {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(amount)
}

export function formatDateTime(iso: string): string {
    return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}

/** 125000 -> "2:05" */
export function formatRemaining(ms: number): string {
    const total = Math.ceil(ms / 1000)
    const m = Math.floor(total / 60)
    const s = total % 60
    return `${m}:${String(s).padStart(2, '0')}`
}