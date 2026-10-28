package com.example.booking.seat.service;

import com.example.booking.seat.dto.SeatMapItem;
import com.example.booking.seat.entity.SeatStatus;
import com.example.booking.seat.repository.EventSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EventSeatService {

    private final EventSeatRepository eventSeats;

    /** Joins the caller's transaction (MANDATORY): inventory must never be created without its event. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int generateInventory(UUID eventId, UUID venueId, BigDecimal basePrice, String currency) {
        return eventSeats.generateForEvent(eventId, venueId, basePrice, currency);
    }

    @Transactional(readOnly = true)
    public long countAvailable(UUID eventId) {
        return eventSeats.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
    }

    @Transactional(readOnly = true)
    public List<SeatMapItem> seatMap(UUID eventId, SeatStatus statusFilter) {
        return statusFilter == null ? eventSeats.findSeatMap(eventId)
                : eventSeats.findSeatMapByStatus(eventId, statusFilter);
    }
}
