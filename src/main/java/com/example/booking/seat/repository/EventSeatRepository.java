package com.example.booking.seat.repository;

import com.example.booking.seat.dto.SeatMapItem;
import com.example.booking.seat.entity.EventSeat;
import com.example.booking.seat.entity.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {

    long countByEventIdAndStatus(UUID eventId, SeatStatus status);

    // One statement creates the entire inventory. Postgres generates each row's id (column default).
    @Modifying
    @Query(value = """
            INSERT INTO event_seats (event_id, seat_id, base_price, currency, status)
            SELECT :eventId, s.id, :price, :currency, 'AVAILABLE'
            FROM seats s
            WHERE s.venue_id = :venueId
            """, nativeQuery = true)
    int generateForEvent(@Param("eventId") UUID eventId, @Param("venueId") UUID venueId,
                         @Param("price") BigDecimal price, @Param("currency") String currency);

    // JPQL refers to the entity *by name* ("Seat"), so this module has no compile-time dependency on the venue module.
    @Query("""
            select new com.example.booking.seat.dto.SeatMapItem(
                es.id, s.section, s.rowLabel, s.seatNumber, es.status, es.basePrice)
            from EventSeat es join Seat s on s.id = es.seatId
            where es.eventId = :eventId
            order by s.section, s.rowLabel, s.seatNumber
            """)
    List<SeatMapItem> findSeatMap(@Param("eventId") UUID eventId);

    @Query("""
            select new com.example.booking.seat.dto.SeatMapItem(
                es.id, s.section, s.rowLabel, s.seatNumber, es.status, es.basePrice)
            from EventSeat es join Seat s on s.id = es.seatId
            where es.eventId = :eventId and es.status = :status
            order by s.section, s.rowLabel, s.seatNumber
            """)
    List<SeatMapItem> findSeatMapByStatus(@Param("eventId") UUID eventId, @Param("status") SeatStatus status);
}