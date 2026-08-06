package com.ecommercehub.connector;

import java.time.Instant;
import java.util.List;

/**
 * channelEventSequence is null when the channel doesn't provide one — the tie-break
 * fallback (Plan §6) then has to rely on status priority instead of sequence.
 */
public record ChannelOrder(String channelOrderId, String customerOrderNumber, Instant eventAt,
                            Long channelEventSequence, List<ChannelOrderItem> items) {
}
