package com.example.booking.booking.repository;

import com.example.booking.booking.entity.BookingItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BookingItemRepository extends JpaRepository<BookingItem, UUID> {
    List<BookingItem> findByBookingId(UUID bookingId);
    List<BookingItem> findByBookingIdIn(Collection<UUID> bookingIds);   // one query for a whole page: no N+1
}