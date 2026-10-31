package com.example.booking.infrastructure.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxJobs {

    private final OutboxPublisher publisher;
    private final OutboxDao dao;
    private final OutboxProperties props;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:1000}")
    public void publish() {
        int batch;
        do {
            batch = publisher.publishDueBatch();     // a full batch means there may be more
        } while (batch >= props.batchSize());
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void cleanup() {
        int outbox = dao.purgeSent(props.retentionDays());
        int processed = dao.purgeProcessed(props.retentionDays());   // keep longer than any realistic redelivery window
        if (outbox + processed > 0) log.info("Purged {} sent outbox row(s) and {} processed-event marker(s)", outbox, processed);
    }
}