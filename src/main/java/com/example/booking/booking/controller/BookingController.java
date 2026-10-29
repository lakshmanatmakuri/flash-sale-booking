package com.example.booking.booking.controller;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.booking.dto.BookingResponse;
import com.example.booking.booking.service.BookingService;
import com.example.booking.common.dto.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @GetMapping("/{id}")
    public BookingResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return bookingService.get(user.id(), id);      // someone else's booking is a 404, not a 403
    }

    @GetMapping
    public PageResponse<BookingResponse> mine(@AuthenticationPrincipal AuthenticatedUser user,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return bookingService.mine(user.id(), page, size);
    }
}
