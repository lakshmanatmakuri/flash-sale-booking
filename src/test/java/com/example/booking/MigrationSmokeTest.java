
package com.example.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
class MigrationSmokeTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16");

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