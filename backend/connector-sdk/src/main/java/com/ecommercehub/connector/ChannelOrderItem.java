package com.ecommercehub.connector;

import java.math.BigDecimal;

public record ChannelOrderItem(String sku, int quantity, BigDecimal unitPrice) {
}
