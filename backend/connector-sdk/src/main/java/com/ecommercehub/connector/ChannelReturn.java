package com.ecommercehub.connector;

import java.time.Instant;

public record ChannelReturn(String channelReturnId, String channelOrderId, Instant eventAt, String status) {
}
