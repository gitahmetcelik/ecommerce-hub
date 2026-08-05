package com.ecommercehub.connector;

import java.math.BigDecimal;

public record PriceUpdate(String sku, BigDecimal price, BigDecimal discountedPrice) {
}
