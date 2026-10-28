package com.example.booking;

import com.example.booking.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationSmokeTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void migrationsApplyAndRolesAreSeeded() {

        Integer roles = jdbc.queryForObject(
                "select count(*) from roles",
                Integer.class
        );

        assertThat(roles).isEqualTo(3);

        Integer eventSeatsTable = jdbc.queryForObject(
                "select count(*) from information_schema.tables " +
                        "where table_name = 'event_seats'",
                Integer.class
        );

        assertThat(eventSeatsTable).isEqualTo(1);
    }
}