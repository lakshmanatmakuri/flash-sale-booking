package com.example.booking.payment.controller;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.payment.dto.PayRequest;
import com.example.booking.payment.dto.PaymentResponse;
import com.example.booking.payment.dto.PaymentResult;
import com.example.booking.payment.service.PaymentQueryService;
import com.example.booking.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentQueryService queries;

    /** 201 paid, 402 payment failed, 202 outcome not known yet (poll GET /api/payments/{id}). */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@AuthenticationPrincipal AuthenticatedUser user,
                                               @RequestHeader("Idempotency-Key") String idempotencyKey,
                                               @Valid @RequestBody PayRequest request) {
        PaymentResult result = paymentService.pay(user.id(), idempotencyKey, request);
        HttpStatus status = switch (result.payment().status()) {
            case SUCCEEDED -> HttpStatus.CREATED;
            case FAILED -> HttpStatus.PAYMENT_REQUIRED;
            default -> HttpStatus.ACCEPTED;
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.payment());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return queries.getForUser(user.id(), id);
    }
}