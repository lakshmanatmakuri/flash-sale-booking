package com.example.booking.wallet;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.wallet.dto.TopUpRequest;
import com.example.booking.wallet.dto.TopUpResponse;
import com.example.booking.wallet.dto.WalletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    @GetMapping
    public WalletResponse wallet(@AuthenticationPrincipal AuthenticatedUser user) {
        return walletService.wallet(user.id());
    }

    /** 201 credited, 402 declined, 202 outcome unknown: retry with the SAME Idempotency-Key. */
    @PostMapping("/topups")
    public ResponseEntity<TopUpResponse> topUp(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestHeader("Idempotency-Key") String key,
                                               @Valid @RequestBody TopUpRequest request) {
        TopUpResponse body = walletService.topUp(user.id(), key, request);
        HttpStatus status = switch (body.status()) {
            case CREDITED -> HttpStatus.CREATED;
            case DECLINED -> HttpStatus.PAYMENT_REQUIRED;
            case PENDING -> HttpStatus.ACCEPTED;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (body.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(body);
    }
}