import { useCallback, useEffect, useRef, useState } from 'react'

interface State<T> { data: T | null; error: unknown; loading: boolean }

/**
 * Loads data when `deps` change (with a spinner) and re-loads silently on `reload()` (no spinner, keeps old data).
 * An older request is aborted when a newer one starts, so a slow response can never overwrite a fresh one.
 */
export function useQuery<T>(fn: (signal: AbortSignal) => Promise<T>, deps: unknown[]) {
    const fnRef = useRef(fn)
    fnRef.current = fn
    const acRef = useRef<AbortController | null>(null)
    const [state, setState] = useState<State<T>>({ data: null, error: null, loading: true })

    const run = useCallback((silent: boolean) => {
        acRef.current?.abort()
        const ac = new AbortController()
        acRef.current = ac
        if (!silent) setState({ data: null, error: null, loading: true })
        fnRef.current(ac.signal)
            .then((data) => { if (!ac.signal.aborted) setState({ data, error: null, loading: false }) })
            .catch((error) => { if (!ac.signal.aborted) setState((s) => ({ ...s, error, loading: false })) })
    }, [])

    // eslint-disable-next-line react-hooks/exhaustive-deps
    useEffect(() => { run(false); return () => acRef.current?.abort() }, [run, ...deps])

    const reload = useCallback(() => run(true), [run])
    return { ...state, reload }
}