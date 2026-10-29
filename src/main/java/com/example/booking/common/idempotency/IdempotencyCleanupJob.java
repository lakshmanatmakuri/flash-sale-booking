package com.example.booking.common.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    private final NamedParameterJdbcTemplate jdbc;
    private final IdempotencyProperties props;

    @Scheduled(cron = "0 15 3 * * *")   // 03:15 every night
    public void purge() {
        int n = jdbc.update("DELETE FROM idempotency_keys WHERE created_at < now() - (:hours * interval '1 hour')",
                Map.of("hours", (double) props.retentionHours()));
        if (n > 0) log.info("Purged {} expired idempotency key(s)", n);
    }
}