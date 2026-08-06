package com.ecommercehub.connector;

import java.time.Instant;
import java.util.List;

/**
 * @param items the returned lines. Empty when a channel's feed does not break the
 *              return down — see {@link ChannelReturnItem} for why an itemless return
 *              is a real limitation rather than a shorthand for "everything".
 */
public record ChannelReturn(String channelReturnId, String channelOrderId, Instant eventAt, String status,
                             List<ChannelReturnItem> items) {

    public ChannelReturn(String channelReturnId, String channelOrderId, Instant eventAt, String status) {
        this(channelReturnId, channelOrderId, eventAt, status, List.of());
    }
}
