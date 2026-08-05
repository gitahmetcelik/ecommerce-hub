package com.ecommercehub.ingest;

import com.ecommercehub.connector.PlatformConnector;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Wires whichever PlatformConnector beans exist (just MockPlatformConnector for now) by channel_type. */
@Component
public class ConnectorRegistry {

    private final Map<String, PlatformConnector> byChannelType;

    public ConnectorRegistry(List<PlatformConnector> connectors) {
        this.byChannelType = connectors.stream()
                .collect(Collectors.toMap(PlatformConnector::channelType, Function.identity()));
    }

    public PlatformConnector require(String channelType) {
        PlatformConnector connector = byChannelType.get(channelType);
        if (connector == null) {
            throw new IllegalStateException("No PlatformConnector registered for channel type " + channelType);
        }
        return connector;
    }
}
