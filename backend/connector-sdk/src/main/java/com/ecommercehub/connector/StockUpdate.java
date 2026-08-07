package com.ecommercehub.connector;

public record StockUpdate(ChannelItemRef item, int availableQuantity) {
}
