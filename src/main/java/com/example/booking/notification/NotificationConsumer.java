package com.example.booking.notification;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kafka.consumer-enabled", havingValue = "true", matchIfMissing = true)
public class NotificationConsumer {

    private final NotificationService notifications;

    @KafkaListener(topics = "${app.outbox.topic:booking.events}", groupId = "notifications")
    public void onMessage(ConsumerRecord<String, String> record) {
        if (!notifications.handle(record.value())) {          // exceptions propagate: retry, then dead-letter topic
            log.info("Duplicate delivery ignored (key={}, offset={})", record.key(), record.offset());
        }
    }
    @PostConstruct
    public void init() {
        log.info(">>> NOTIFICATION CONSUMER BEAN CREATED <<<");
    }
}