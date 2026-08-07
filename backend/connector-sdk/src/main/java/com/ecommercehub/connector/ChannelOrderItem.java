package com.ecommercehub.connector;

import java.math.BigDecimal;

public record ChannelOrderItem(ChannelItemRef item, int quantity, BigDecimal unitPrice) {
}
