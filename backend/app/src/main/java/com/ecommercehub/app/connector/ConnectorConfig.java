package com.ecommercehub.app.connector;

import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.mock.MockPlatformConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/** Registers every available PlatformConnector — only Mock exists so far (Faz 1/§14 decision). */
@Configuration
public class ConnectorConfig {

    @Bean
    public PlatformConnector mockPlatformConnector(ObjectMapper objectMapper) {
        return new MockPlatformConnector(HttpClient.newHttpClient(), objectMapper);
    }
}
