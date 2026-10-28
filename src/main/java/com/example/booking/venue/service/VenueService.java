package com.example.booking.venue.service;

import com.example.booking.common.exception.BadRequestException;
import com.example.booking.common.exception.ResourceNotFoundException;
import com.example.booking.venue.dto.CreateVenueRequest;
import com.example.booking.venue.dto.SectionLayout;
import com.example.booking.venue.dto.VenueResponse;
import com.example.booking.venue.entity.Seat;
import com.example.booking.venue.entity.Venue;
import com.example.booking.venue.repository.SeatRepository;
import com.example.booking.venue.repository.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class VenueService {

    private static final int MAX_SEATS_PER_VENUE = 10_000;

    private final VenueRepository venues;
    private final SeatRepository seats;

    @Transactional
    public VenueResponse create(CreateVenueRequest req) {
        validateLayout(req);

        Venue venue = new Venue();
        venue.setName(req.name().trim());
        venue.setCity(req.city().trim());
        // Flush now: Venue and Seat have no JPA association, so Hibernate's insert ordering
        // could otherwise try to insert seats before their venue (FK violation).
        venues.saveAndFlush(venue);

        List<Seat> generated = new ArrayList<>();
        for (SectionLayout section : req.sections()) {
            for (int r = 0; r < section.rows(); r++) {
                String rowLabel = String.valueOf((char) ('A' + r));
                for (int n = 1; n <= section.seatsPerRow(); n++) {
                    generated.add(Seat.of(venue.getId(), section.name().trim(), rowLabel, n));
                }
            }
        }
        seats.saveAll(generated);   // batched thanks to hibernate.jdbc.batch_size
        return new VenueResponse(venue.getId(), venue.getName(), venue.getCity(), generated.size());
    }

    @Transactional(readOnly = true)
    public VenueResponse get(UUID id) {
        Venue v = venues.findById(id).orElseThrow(() -> new ResourceNotFoundException("Venue", id));
        return new VenueResponse(v.getId(), v.getName(), v.getCity(), (int) seats.countByVenueId(id));
    }

    /** Used by the event module. Exposes only what it needs, not our repositories. */
    @Transactional(readOnly = true)
    public int requireSeatCount(UUID venueId) {
        if (!venues.existsById(venueId)) {
            throw new ResourceNotFoundException("Venue", venueId);
        }
        return (int) seats.countByVenueId(venueId);
    }

    private void validateLayout(CreateVenueRequest req) {
        Set<String> names = new HashSet<>();
        int total = 0;
        for (SectionLayout s : req.sections()) {
            if (!names.add(s.name().trim().toLowerCase(Locale.ROOT))) {
                throw new BadRequestException("Duplicate section name: " + s.name());
            }
            total += s.rows() * s.seatsPerRow();
        }
        if (total > MAX_SEATS_PER_VENUE) {
            throw new BadRequestException("A venue may have at most " + MAX_SEATS_PER_VENUE + " seats");
        }
    }
}