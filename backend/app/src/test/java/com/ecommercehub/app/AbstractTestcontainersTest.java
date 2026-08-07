package com.ecommercehub.app;

import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared infrastructure for the gate tests: a Postgres and a RabbitMQ of their own.
 *
 * <p><b>There is no silent fallback and none will be added.</b> If Docker is
 * unreachable, class loading fails loudly. Falling back to some other connection
 * would let a test "pass" against a database without RLS or with the wrong schema —
 * exactly what the plan's §12 gates exist to rule out.
 *
 * <p><b>Why the broker is containerised too.</b> It was not, at first: the tests used
 * whatever RabbitMQ was on localhost, which on a development machine is the one
 * docker-compose starts for running the application by hand. Both then consume from the
 * same three queues. A task message published by a test would regularly be picked up by
 * the locally running application, which looked the task id up in <em>its</em> database,
 * found nothing, and acknowledged the message anyway — so the test's task sat at
 * KUYRUKTA forever while its queue read empty. It presented as flakiness and timeouts,
 * never as an error, and it cost real time to find. Tests own their broker now.
 *
 * <p><b>Plan v5 Faz 5: both "api" and "worker" active.</b> Splitting the two into
 * separate processes was for production; the gate tests still want everything in one
 * context, the same way they did before the split — sweepers autowired and called
 * directly (never relying on {@code @Scheduled} actually firing, {@code
 * hub.scheduling.enabled=false} sees to that) alongside the REST controllers. A test
 * that wants to prove the split itself — that "api" alone has no sweeper beans, that
 * "worker" alone has no controller beans — activates a single profile on its own
 * {@code @SpringBootTest}, not here.
 */
@ActiveProfiles({"api", "worker"})
public abstract class AbstractTestcontainersTest {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("ecommerce_hub_db")
                    .withUsername("postgres")
                    .withPassword("postgres");

    /**
     * Not the plain rabbitmq image: the engine's scheduled/delayed tasks are built on the
     * x-delayed-message exchange type, which is a plugin. A stock broker rejects the
     * exchange declaration and the application fails to start.
     */
    static final GenericContainer<?> rabbitmq =
            new GenericContainer<>(DockerImageName.parse("heidiks/rabbitmq-delayed-message-exchange:3.13.0-management"))
                    .withExposedPorts(5672);

    static {
        postgres.start();
        rabbitmq.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", () -> rabbitmq.getMappedPort(5672));
    }
}
