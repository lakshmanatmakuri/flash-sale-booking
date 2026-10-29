package com.example.booking.seat.service;

import com.example.booking.common.exception.BadRequestException;
import com.example.booking.common.exception.SeatNotAvailableException;
import com.example.booking.seat.dto.SeatMapItem;
import com.example.booking.seat.dto.SeatPrice;
import com.example.booking.seat.entity.SeatStatus;
import com.example.booking.seat.repository.EventSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventSeatService {

    private final EventSeatRepository eventSeats;

    /** Joins the caller's transaction (MANDATORY): inventory must never be created without its event. */
    @Transactional(propagation = Propagation.MANDATORY)
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

    /**
     * AVAILABLE -> HELD, atomically. Either this transaction now owns the seat's row lock and the seat is HELD,
     * or it throws. MANDATORY: the hold row must be written in the SAME transaction, so a crash can never leave
     * a seat HELD with no hold record.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void claimForHold(UUID eventId, UUID eventSeatId) {
        SeatStatus.AVAILABLE.requireTransitionTo(SeatStatus.HELD);   // the rule, written down in one place

        if (eventSeats.claimForHold(eventSeatId, eventId) == 1) {
            return;                                                   // happy path: ONE statement, nothing else
        }
        // Slow path (the loser): find out why, so the client gets a useful answer.
        SeatStatus current = eventSeats.findStatus(eventSeatId, eventId)
                .orElseThrow(() -> new BadRequestException(
                        "Seat " + eventSeatId + " does not belong to this event"));
        throw new SeatNotAvailableException(eventSeatId, current);
    }

    /** Guarded transition. Returns false if the seat was not in `from` (someone else changed it first). */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean transition(UUID eventSeatId, SeatStatus from, SeatStatus to) {
        from.requireTransitionTo(to);
        return eventSeats.transition(eventSeatId, from.name(), to.name()) == 1;
    }

    /** Called after claiming: the rows are locked by this transaction, so the prices cannot change underneath us. */
    @Transactional(readOnly = true)
    public Map<UUID, SeatPrice> pricesOf(Collection<UUID> eventSeatIds) {
        return eventSeats.findPrices(eventSeatIds).stream()
                .collect(Collectors.toMap(SeatPrice::eventSeatId, Function.identity()));
    }
}
