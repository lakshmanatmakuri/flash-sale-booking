package com.example.booking.venue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A PHYSICAL seat of a venue. Whether it is sold for a given event lives in EventSeat. */
@Entity
@Table(name = "seats")
@Getter
@NoArgsConstructor
public class Seat {

    // UUID (not IDENTITY) generation lets Hibernate batch inserts; IDENTITY would disable batching.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "venue_id", nullable = false, updatable = false)
    private UUID venueId;

    @Column(nullable = false, length = 30)
    private String section;

    @Column(name = "row_label", nullable = false, length = 10)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    public static Seat of(UUID venueId, String section, String rowLabel, int seatNumber) {
        Seat s = new Seat();
        s.venueId = venueId;
        s.section = section;
        s.rowLabel = rowLabel;
        s.seatNumber = seatNumber;
        return s;
    }
}