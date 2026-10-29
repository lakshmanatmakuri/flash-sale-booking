package com.example.booking.hold.repository;

import com.example.booking.hold.entity.HoldStatus;
import com.example.booking.hold.entity.SeatHold;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    int countByUserIdAndEventIdAndStatus(UUID userId, UUID eventId, HoldStatus status);

    List<SeatHold> findByUserIdAndStatusOrderByExpiresAtAsc(UUID userId, HoldStatus status);
}
