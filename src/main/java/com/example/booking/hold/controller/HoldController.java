package com.example.booking.hold.controller;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.hold.dto.CreateHoldRequest;
import com.example.booking.hold.dto.HoldResponse;
import com.example.booking.hold.service.HoldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/holds")
@RequiredArgsConstructor
public class HoldController {

    private final HoldService holdService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public List<HoldResponse> create(@AuthenticationPrincipal AuthenticatedUser user,
                                     @Valid @RequestBody CreateHoldRequest request) {
        return holdService.createHolds(user.id(), request);
    }

    @GetMapping
    public List<HoldResponse> mine(@AuthenticationPrincipal AuthenticatedUser user) {
        return holdService.myActiveHolds(user.id());
    }

    @PostMapping("/{id}/renew")
    public HoldResponse renew(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return holdService.renew(user.id(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        holdService.release(user.id(), id);
    }
}
