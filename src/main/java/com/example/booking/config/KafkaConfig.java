package com.example.booking.config;

import com.example.booking.infrastructure.outbox.OutboxProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic bookingEventsTopic(OutboxProperties props) {
        return TopicBuilder.name(props.topic()).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic bookingEventsDeadLetterTopic(OutboxProperties props) {
        return TopicBuilder.name(props.topic() + ".DLT").partitions(1).replicas(1).build();
    }

    /** A message that keeps failing is retried 5 times, then parked in <topic>.DLT. It is never silently skipped. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(record.topic() + ".DLT", -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 5L));
    }
}