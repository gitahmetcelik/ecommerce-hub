package com.ecommercehub.connector;

import java.math.BigDecimal;

public record PriceUpdate(ChannelItemRef item, BigDecimal price, BigDecimal discountedPrice) {
}
