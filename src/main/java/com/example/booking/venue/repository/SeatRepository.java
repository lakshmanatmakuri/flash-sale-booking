package com.example.booking.venue.repository;

import com.example.booking.venue.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SeatRepository extends JpaRepository<Seat, UUID> {
    long countByVenueId(UUID venueId);
}