package com.example.booking.booking.service;

import com.example.booking.booking.dto.BookingResponse;
import com.example.booking.booking.entity.Booking;
import com.example.booking.booking.entity.BookingItem;
import com.example.booking.booking.repository.BookingDao;
import com.example.booking.booking.repository.BookingItemRepository;
import com.example.booking.booking.repository.BookingRepository;
import com.example.booking.common.dto.PageResponse;
import com.example.booking.common.exception.BadRequestException;
import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.hold.dto.ConvertedHold;
import com.example.booking.seat.entity.SeatStatus;
import com.example.booking.seat.service.EventSeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookings;
    private final BookingItemRepository items;
    private final BookingDao dao;
    private final EventSeatService eventSeatService;

    @Transactional(propagation = Propagation.MANDATORY)
    public Booking createPending(UUID userId, List<ConvertedHold> holds) {
        String currency = holds.get(0).currency();
        if (holds.stream().anyMatch(h -> !h.currency().equals(currency))) {
            throw new BadRequestException("Mixed currencies are not supported");
        }
        BigDecimal total = holds.stream().map(ConvertedHold::price).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            throw new BadRequestException("Free bookings are not supported in this version");
        }
        // Flush the booking before its items: they have no JPA association, so Hibernate cannot infer the FK order.
        Booking booking = bookings.saveAndFlush(Booking.pending(userId, holds.get(0).eventId(), total, currency));
        items.saveAllAndFlush(holds.stream()
                .map(h -> BookingItem.pending(booking.getId(), h.eventSeatId(), h.holdId(), h.price())).toList());
        return booking;
    }

    /** Payment succeeded: booking CONFIRMED, items CONFIRMED, seats BOOKED. Returns the event id. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID confirm(UUID bookingId) {
        UUID eventId = dao.confirm(bookingId).orElseThrow(() -> new IllegalStateException(
                "Booking " + bookingId + " was not PENDING_PAYMENT while its payment succeeded"));
        dao.setItemsStatus(bookingId, "PENDING", "CONFIRMED");
        for (UUID seatId : sortedSeatIds(bookingId)) {
            if (!eventSeatService.transition(seatId, SeatStatus.PAYMENT_PENDING, SeatStatus.BOOKED)) {
                throw new IllegalStateException("Seat " + seatId + " was not PAYMENT_PENDING at confirmation");
            }
        }
        return eventId;
    }

    /** Payment failed: booking FAILED, items RELEASED, seats back to AVAILABLE. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fail(UUID bookingId) {
        if (!dao.fail(bookingId)) {
            throw new IllegalStateException("Booking " + bookingId + " was not PENDING_PAYMENT while failing");
        }
        dao.setItemsStatus(bookingId, "PENDING", "RELEASED");
        for (UUID seatId : sortedSeatIds(bookingId)) {
            if (!eventSeatService.transition(seatId, SeatStatus.PAYMENT_PENDING, SeatStatus.AVAILABLE)) {
                throw new IllegalStateException("Seat " + seatId + " was not PAYMENT_PENDING at release");
            }
        }
        // PHASE 8: SeatReleased outbox events (wakes the waitlist).
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID userId, UUID bookingId) {
        Booking b = bookings.findById(bookingId).filter(x -> x.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
        return BookingResponse.from(b, items.findByBookingId(bookingId));
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingResponse> mine(UUID userId, int page, int size) {
        Page<Booking> p = bookings.findByUserId(userId, PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), 50), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, List<BookingItem>> byBooking = items
                .findByBookingIdIn(p.getContent().stream().map(Booking::getId).toList()).stream()
                .collect(Collectors.groupingBy(BookingItem::getBookingId));
        return PageResponse.of(p.map(b -> BookingResponse.from(b, byBooking.getOrDefault(b.getId(), List.of()))));
    }

    private List<UUID> sortedSeatIds(UUID bookingId) {
        return items.findByBookingId(bookingId).stream().map(BookingItem::getEventSeatId).sorted().toList();
    }
}
