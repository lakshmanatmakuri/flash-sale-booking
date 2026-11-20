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
export interface PageResponse<T> {
    content: T[]
    page: number
    size: number
    totalElements: number
    totalPages: number
}

export type EventStatus = 'DRAFT' | 'PUBLISHED' | 'ON_SALE' | 'SOLD_OUT' | 'CANCELLED' | 'COMPLETED'

export interface EventSummary {
    id: string
    name: string
    venueId: string
    startsAt: string
    saleStartsAt: string
    saleEndsAt: string
    status: EventStatus
}

export interface EventDetail extends EventSummary {
    organizerId: string
    description: string | null
    maxSeatsPerUser: number
    availableSeats: number
}

export interface Venue {
    id: string
    name: string
    city: string
    totalSeats: number
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'PAYMENT_PENDING' | 'BOOKED' | 'CANCELLED'

export interface SeatMapItem {
    eventSeatId: string
    section: string
    row: string
    seatNumber: number | null
    status: SeatStatus
    price: number
}

export interface Hold {
    id: string
    eventSeatId: string
    eventId: string
    status: 'ACTIVE' | 'CONVERTED' | 'EXPIRED' | 'RELEASED'
    lockedPrice: number
    currency: string
    expiresAt: string
    renewalCount: number
}