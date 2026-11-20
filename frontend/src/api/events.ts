import { api } from './client'
import type { EventDetail, EventSummary, Hold, PageResponse, SeatMapItem, Venue } from './types'

export const listEvents = (page: number, size: number, signal?: AbortSignal) =>
    api<PageResponse<EventSummary>>(`/api/events?page=${page}&size=${size}`, { auth: false, signal })

export const getEvent = (id: string, signal?: AbortSignal) =>
    api<EventDetail>(`/api/events/${id}`, { signal })

export const getVenue = (id: string, signal?: AbortSignal) =>
    api<Venue>(`/api/venues/${id}`, { signal })

export const getSeatMap = (eventId: string, signal?: AbortSignal) =>
    api<SeatMapItem[]>(`/api/events/${eventId}/seats`, { signal })

export const createHolds = (
    eventId: string,
    eventSeatIds: string[],
    idempotencyKey: string
) =>
    api<Hold[]>('/api/holds', {
        method: 'POST',
        idempotencyKey,
        body: { eventId, eventSeatIds }
    })

export const myHolds = (signal?: AbortSignal) => api<Hold[]>('/api/holds', { signal })

export const renewHold = (id: string) => api<Hold>(`/api/holds/${id}/renew`, { method: 'POST' })

export const releaseHold = (id: string) => api<void>(`/api/holds/${id}`, { method: 'DELETE' })