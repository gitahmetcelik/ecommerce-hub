package com.ecommercehub.ingest;

import com.ecommercehub.connector.Capability;
import com.ecommercehub.domain.push.ChannelPushCapabilityChecker;
import com.ecommercehub.domain.push.ChannelPushService;
import org.springframework.stereotype.Component;

/**
 * The only place that translates a push {@code type} string into the connector-sdk
 * {@link Capability} it corresponds to — hub-domain cannot do this itself (it has no
 * dependency on connector-sdk), but this module already depends on both.
 */
@Component
public class ConnectorPushCapabilityChecker implements ChannelPushCapabilityChecker {

    private final ConnectorRegistry connectorRegistry;

    public ConnectorPushCapabilityChecker(ConnectorRegistry connectorRegistry) {
        this.connectorRegistry = connectorRegistry;
    }

    @Override
    public boolean supports(String channelType, String pushType) {
        return connectorRegistry.require(channelType).capabilities().contains(toCapability(pushType));
    }

    private static Capability toCapability(String pushType) {
        return switch (pushType) {
            case ChannelPushService.TYPE_STOCK -> Capability.STOCK_PUSH;
            case ChannelPushService.TYPE_PRICE -> Capability.PRICE_PUSH;
            default -> throw new IllegalArgumentException("No capability mapping for push type " + pushType);
        };
    }
}
