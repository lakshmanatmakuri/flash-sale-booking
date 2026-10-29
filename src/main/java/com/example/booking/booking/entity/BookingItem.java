package com.example.booking.booking.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "booking_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BookingItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Plain ids, not @ManyToOne: same module-decoupling rule as Phase 3, and no accidental lazy loading.
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "event_seat_id", nullable = false, updatable = false)
    private UUID eventSeatId;

    @Column(name = "hold_id", nullable = false, updatable = false)
    private UUID holdId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingItemStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static BookingItem pending(UUID bookingId, UUID eventSeatId, UUID holdId, BigDecimal price) {
        BookingItem i = new BookingItem();
        i.bookingId = bookingId;
        i.eventSeatId = eventSeatId;
        i.holdId = holdId;
        i.price = price;
        i.status = BookingItemStatus.PENDING;
        i.createdAt = Instant.now();
        i.updatedAt = i.createdAt;
        return i;
    }
}