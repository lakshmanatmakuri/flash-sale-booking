package com.example.booking.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * MANDATORY: an event may only be written inside the business transaction it describes. Calling this without one
     * throws, so "event written, state change not" (or the reverse) is impossible by construction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            jdbc.update("""
                    INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, payload)
                    VALUES (:type, :aggId, :event, CAST(:payload AS jsonb))
                    """, new MapSqlParameterSource().addValue("type", aggregateType).addValue("aggId", aggregateId)
                    .addValue("event", eventType).addValue("payload", mapper.writeValueAsString(payload)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + eventType, e);
        }
    }
}