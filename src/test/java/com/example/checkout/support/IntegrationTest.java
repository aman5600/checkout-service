package com.example.checkout.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against a real PostgreSQL -- the one from docker-compose, in the
 * {@code checkout_test} database. The parts of this service worth testing (the
 * guarded UPDATE, SKIP LOCKED claiming, the CHECK constraints) only exist in the
 * database, so an in-memory substitute would be testing something else.
 * <p>
 * Start it with {@code docker-compose up -d} before running the suite.
 */
@SpringBootTest
@Import(IntegrationTest.StubGatewayConfig.class)
public abstract class IntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected StubPaymentGateway gateway;

    @BeforeEach
    void resetWorld() {
        jdbc.update("TRUNCATE TABLE orders");
        gateway.reset();
    }

    @TestConfiguration
    static class StubGatewayConfig {
        // Declared as the concrete type so tests can inject it directly.
        @Bean
        @Primary
        StubPaymentGateway stubPaymentGateway() {
            return new StubPaymentGateway();
        }
    }
}
