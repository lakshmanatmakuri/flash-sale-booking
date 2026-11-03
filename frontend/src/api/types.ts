/** Mirrors the backend's ApiError record. */
export interface ApiErrorBody {
    timestamp?: string
    status: number
    error?: string
    code: string
    message: string
    path?: string
    violations?: { field: string; message: string }[]
}

export interface AuthResponse {
    accessToken: string
    tokenType: string
    expiresInSeconds: number
    userId: string
    email: string
    roles: string[]
}

export interface Session {
    token: string
    userId: string
    email: string
    roles: string[]
    /** epoch ms at which the token expires */
    expiresAt: number
}