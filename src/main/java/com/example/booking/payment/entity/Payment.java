package com.example.booking.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Insert + read only. Status changes happen in PaymentDao (guarded SQL). We never store card data or tokens. */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    public static final String WALLET_PROVIDER = "wallet";    // used from Phase 7

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status;

    @Column(nullable = false, updatable = false, length = 30)
    private String provider;

    @Column(name = "provider_ref", length = 100)
    private String providerRef;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Payment pending(UUID bookingId, UUID userId, BigDecimal amount, String currency, String provider) {
        Payment p = new Payment();
        p.bookingId = bookingId;
        p.userId = userId;
        p.amount = amount;
        p.currency = currency;
        p.provider = provider;
        p.status = PaymentStatus.PENDING;
        p.createdAt = Instant.now();
        p.updatedAt = p.createdAt;
        return p;
    }
}