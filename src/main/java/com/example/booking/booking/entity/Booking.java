package com.example.booking.booking.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * No setters on purpose: JPA only INSERTS and READS bookings. Every status change goes through a guarded SQL
 * statement in BookingDao, so nobody can bypass the state rules with a setter. (@Version is kept in step manually.)
 */
@Entity
@Table(name = "bookings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "total_amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "group_booking_id")
    private UUID groupBookingId;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public static Booking pending(UUID userId, UUID eventId, BigDecimal total, String currency) {
        Booking b = new Booking();
        b.userId = userId;
        b.eventId = eventId;
        b.status = BookingStatus.PENDING_PAYMENT;
        b.totalAmount = total;
        b.currency = currency;
        b.createdAt = Instant.now();
        b.updatedAt = b.createdAt;
        return b;
    }
}