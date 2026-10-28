package com.example.booking.seat.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** THE contested row. One per (event, physical seat). Rows are created by set-based SQL, not via JPA. */
@Entity
@Table(name = "event_seats")
@Getter
@NoArgsConstructor
public class EventSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "seat_id", nullable = false, updatable = false)
    private UUID seatId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeatStatus status;

    @Column(name = "base_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal basePrice;

    // The column is CHAR(3). Without this, `ddl-auto: validate` fails because Hibernate expects VARCHAR.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}