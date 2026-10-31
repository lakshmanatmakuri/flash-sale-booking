package com.example.booking.ledger.controller;

import com.example.booking.auth.security.AuthenticatedUser;
import com.example.booking.common.dto.PageResponse;
import com.example.booking.ledger.service.LedgerQueryService;
import com.example.booking.ledger.service.LedgerQueryService.StatementLine;
import com.example.booking.ledger.service.LedgerQueryService.TrialBalance;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerQueryService queries;

    /** The caller's own wallet statement, newest first. A user can only ever see their own entries. */
    @GetMapping("/transactions")
    public PageResponse<StatementLine> myStatement(@AuthenticationPrincipal AuthenticatedUser user,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return queries.walletStatement(user.id(), page, size);
    }

    @GetMapping("/trial-balance")
    @PreAuthorize("hasRole('ADMIN')")
    public TrialBalance trialBalance() {
        return queries.trialBalance();
    }
}