package com.example.booking.seat.controller;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.event.service.EventService;
import com.example.booking.seat.dto.SeatMapItem;
import com.example.booking.seat.entity.SeatStatus;
import com.example.booking.seat.service.EventSeatService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/events/{eventId}/seats")
@RequiredArgsConstructor
public class EventSeatController {

    private final EventService eventService;
    private final EventSeatService eventSeatService;

    /** Authenticated only. In Phase 12 this will also require a waiting-room admission token. */
    @GetMapping
    public List<SeatMapItem> seatMap(@AuthenticationPrincipal AuthenticatedUser user,
                                     @PathVariable UUID eventId,
                                     @RequestParam(required = false) SeatStatus status) {
        eventService.requireVisible(user, eventId);
        return eventSeatService.seatMap(eventId, status);
    }
}
