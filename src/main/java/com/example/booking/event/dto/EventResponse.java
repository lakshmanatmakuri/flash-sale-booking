package com.example.booking.event.dto;

import com.example.booking.event.entity.Event;
import com.example.booking.event.entity.EventStatus;

import java.time.Instant;
import java.util.UUID;

public record EventResponse(UUID id, UUID venueId, UUID organizerId, String name, String description,
                            Instant startsAt, Instant saleStartsAt, Instant saleEndsAt,
                            int maxSeatsPerUser, EventStatus status, long availableSeats) {

    public static EventResponse from(Event e, long availableSeats) {
        return new EventResponse(e.getId(), e.getVenueId(), e.getOrganizerId(), e.getName(), e.getDescription(),
                e.getStartsAt(), e.getSaleStartsAt(), e.getSaleEndsAt(), e.getMaxSeatsPerUser(),
                e.getStatus(), availableSeats);
    }
}
