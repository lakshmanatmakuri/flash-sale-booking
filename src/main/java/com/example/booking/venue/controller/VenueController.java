package com.example.booking.venue.controller;

import com.example.booking.venue.dto.CreateVenueRequest;
import com.example.booking.venue.dto.VenueResponse;
import com.example.booking.venue.service.VenueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public VenueResponse create(@Valid @RequestBody CreateVenueRequest request) {
        return venueService.create(request);
    }

    @GetMapping("/{id}")
    public VenueResponse get(@PathVariable UUID id) {
        return venueService.get(id);
    }
}
