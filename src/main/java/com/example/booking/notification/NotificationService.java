package com.example.booking.notification;

import com.example.booking.infrastructure.messaging.IdempotentConsumerService;
import com.example.booking.infrastructure.outbox.DomainEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** V1 consumer: a log line stands in for the e-mail/push a real notification would send. */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    public static final String CONSUMER = "notification";

    private final IdempotentConsumerService idempotent;
    private final ObjectMapper mapper;

    /** @return true if processed, false if it was a duplicate delivery */
    public boolean handle(String envelopeJson) {
        JsonNode root;
        try {
            root = mapper.readTree(envelopeJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Unreadable event envelope", e);   // error handler retries, then DLT
        }
        UUID messageId = UUID.fromString(root.path("messageId").asText());
        String type = root.path("type").asText();
        JsonNode payload = root.path("payload");
        return idempotent.process(CONSUMER, messageId, () -> deliver(type, payload));
    }

    private void deliver(String type, JsonNode p) {
        switch (type) {
            case DomainEvents.BOOKING_CONFIRMED ->
                    log.info("[notify] booking {} confirmed for user {}", p.path("bookingId").asText(), p.path("userId").asText());
            case DomainEvents.PAYMENT_FAILED ->
                    log.info("[notify] payment {} failed for user {} ({})", p.path("paymentId").asText(),
                            p.path("userId").asText(), p.path("failureCode").asText());
            default -> log.debug("[notify] no notification for {}", type);
        }
    }
}
