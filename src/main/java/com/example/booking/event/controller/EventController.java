package com.example.booking.event.controller;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.common.dto.PageResponse;
import com.example.booking.event.dto.CreateEventRequest;
import com.example.booking.event.dto.EventResponse;
import com.example.booking.event.dto.EventSummary;
import com.example.booking.event.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                @Valid @RequestBody CreateEventRequest request) {
        return eventService.create(user, request);
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventResponse publish(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return eventService.publish(user, id);
    }

    // Public endpoint: `user` is null for anonymous callers.
    @GetMapping
    public PageResponse<EventSummary> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return eventService.list(page, size);
    }

    @GetMapping("/{id}")
    public EventResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return eventService.get(user, id);
    }
}