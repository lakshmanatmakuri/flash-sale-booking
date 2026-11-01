package com.example.booking.refund;

import com.example.booking.auth.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
public class CancellationController {

    private final CancellationService cancellationService;

    /** 200 always. `refundStatus` says where the money stands: SUCCEEDED, or REQUESTED/UNKNOWN/FAILED (retried automatically). Idempotent by state. */
    @PostMapping("/{id}/cancel")
    public CancellationResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return cancellationService.cancel(user.id(), id);
    }
}