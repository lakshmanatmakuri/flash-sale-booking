package com.example.booking.event.dto;

import com.example.booking.event.entity.Event;
import com.example.booking.event.entity.EventStatus;

import java.time.Instant;
import java.util.UUID;

public record EventSummary(UUID id, String name, UUID venueId, Instant startsAt,
                           Instant saleStartsAt, Instant saleEndsAt, EventStatus status) {

    public static EventSummary from(Event e) {
        return new EventSummary(e.getId(), e.getName(), e.getVenueId(), e.getStartsAt(),
                e.getSaleStartsAt(), e.getSaleEndsAt(), e.getStatus());
    }
}