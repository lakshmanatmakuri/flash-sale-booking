package com.example.booking.booking.dto;

import com.example.booking.booking.entity.Booking;
import com.example.booking.booking.entity.BookingItem;
import com.example.booking.booking.entity.BookingItemStatus;
import com.example.booking.booking.entity.BookingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingResponse(UUID id, UUID eventId, BookingStatus status, BigDecimal totalAmount, String currency,
                              Instant createdAt, Instant confirmedAt, List<Item> items) {

    public record Item(UUID eventSeatId, BigDecimal price, BookingItemStatus status) {}

    public static BookingResponse from(Booking b, List<BookingItem> items) {
        return new BookingResponse(b.getId(), b.getEventId(), b.getStatus(), b.getTotalAmount(), b.getCurrency(),
                b.getCreatedAt(), b.getConfirmedAt(),
                items.stream().map(i -> new Item(i.getEventSeatId(), i.getPrice(), i.getStatus())).toList());
    }
}
