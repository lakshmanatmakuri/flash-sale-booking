import { ApiClientError } from '../api/client'

export default function ErrorBanner({ error }: { error: unknown }) {
    if (!error) return null
    const e = error instanceof ApiClientError ? error : null
    return (
        <div role="alert" className="banner banner-error">
            <strong>{e ? e.message : 'Something went wrong.'}</strong>
            {e && e.violations.length > 0 && (
                <ul>
                    {e.violations.map((v) => (
                        <li key={v.field + v.message}>
                            {v.field}: {v.message}
                        </li>
                    ))}
                </ul>
            )}
        </div>
    )
}