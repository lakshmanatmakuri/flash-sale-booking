package com.example.booking.seat.repository;

import com.example.booking.seat.dto.SeatMapItem;
import com.example.booking.seat.dto.SeatPrice;
import com.example.booking.seat.entity.EventSeat;
import com.example.booking.seat.entity.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    // Atomically claim a seat only if it is currently AVAILABLE.
    @Modifying
    @Query(value = """
            UPDATE event_seats
               SET status = 'HELD',
                   version = version + 1,
                   updated_at = now()
             WHERE id = :id
               AND event_id = :eventId
               AND status = 'AVAILABLE'
            """, nativeQuery = true)
    int holdAvailableSeat(
            @Param("id") UUID id,
            @Param("eventId") UUID eventId
    );

    // Atomically claims an available seat for a hold.
    // 1 row updated = success; 0 rows updated = claim failed.
    @Modifying
    @Query(value = """
            UPDATE event_seats
               SET status = 'HELD', version = version + 1, updated_at = now()
             WHERE id = :id AND event_id = :eventId AND status = 'AVAILABLE'
            """, nativeQuery = true)
    int claimForHold(@Param("id") UUID id, @Param("eventId") UUID eventId);

    // Changes state only if the seat is currently in the expected state.
    @Modifying
    @Query(value = """
            UPDATE event_seats
               SET status = :toStatus, version = version + 1, updated_at = now()
             WHERE id = :id AND status = :fromStatus
            """, nativeQuery = true)
    int transition(@Param("id") UUID id,
                   @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus);

    // Retrieves the current status for failure diagnostics.
    @Query("select es.status from EventSeat es where es.id = :id and es.eventId = :eventId")
    Optional<SeatStatus> findStatus(@Param("id") UUID id,
                                    @Param("eventId") UUID eventId);

    // Retrieves prices for the requested seats.
    @Query("""
            select new com.example.booking.seat.dto.SeatPrice(es.id, es.basePrice, es.currency)
            from EventSeat es where es.id in :ids
            """)
    List<SeatPrice> findPrices(@Param("ids") Collection<UUID> ids);


}