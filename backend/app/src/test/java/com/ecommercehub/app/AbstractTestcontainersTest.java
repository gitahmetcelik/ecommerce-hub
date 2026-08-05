package com.ecommercehub.app;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared database infrastructure for the gate tests.
 *
 * <p><b>There is no silent fallback and none will be added.</b> If Docker is
 * unreachable, class loading fails loudly. Falling back to some other connection
 * when the container doesn't start would let a test "pass" against a database
 * without RLS or with the wrong schema — exactly what the plan's §12 gates exist
 * to rule out.
 */
public abstract class AbstractTestcontainersTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("ecommerce_hub_db")
                    .withUsername("postgres")
                    .withPassword("postgres");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
