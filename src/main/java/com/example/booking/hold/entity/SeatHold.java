package com.example.booking.hold.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * READ-ONLY view of a hold. All writes go through guarded SQL in SeatHoldDao, so nobody can bypass the
 * guards by calling a setter. @Immutable tells Hibernate never to issue UPDATEs for this entity.
 */
@Entity
@Immutable
@Table(name = "seat_holds")
@Getter
@NoArgsConstructor
public class SeatHold {

    @Id
    private UUID id;

    @Column(name = "event_seat_id", nullable = false)
    private UUID eventSeatId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private HoldStatus status;

    @Column(name = "locked_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal lockedPrice;

    @JdbcTypeCode(SqlTypes.CHAR)       // column is CHAR(3), see the same note on EventSeat
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "renewal_count", nullable = false)
    private int renewalCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}