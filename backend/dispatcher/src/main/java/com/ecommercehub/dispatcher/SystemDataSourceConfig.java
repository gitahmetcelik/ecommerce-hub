package com.ecommercehub.dispatcher;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * A second connection pool, authenticated as hub_system (BYPASSRLS), separate from the
 * app's primary pool. This is the one place org-spanning queries are allowed to exist
 * (Plan §3(a)) — the dispatcher has to see every organization's pending work at once to
 * round-robin fairly, which RLS would otherwise block entirely. Never share this bean
 * with request-handling code.
 *
 * <p>Once a second {@link DataSource} bean exists, Spring Boot's own datasource
 * autoconfiguration can no longer tell which one is "the" datasource — components that
 * autowire {@code DataSource} by type (Flyway, JPA) resolve ambiguously instead of
 * predictably picking the app's primary pool. The primary bean is redeclared here,
 * explicitly marked {@link Primary}, which suppresses Spring Boot's own
 * {@code @ConditionalOnMissingBean}-guarded datasource autoconfiguration in favor of
 * this one — every unqualified {@code DataSource} injection point keeps resolving to
 * the primary pool, and only {@code @Qualifier("systemDataSource")} gets this one.
 */
@Configuration
@EnableConfigurationProperties(DispatcherProperties.class)
public class SystemDataSourceConfig {

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties dataSourceProperties) {
        return dataSourceProperties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    @ConfigurationProperties("hub.dispatcher.system-datasource")
    public DataSourceProperties systemDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public DataSource systemDataSource(@Qualifier("systemDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean
    public NamedParameterJdbcTemplate systemJdbcTemplate(@Qualifier("systemDataSource") DataSource systemDataSource) {
        return new NamedParameterJdbcTemplate(systemDataSource);
    }
}
