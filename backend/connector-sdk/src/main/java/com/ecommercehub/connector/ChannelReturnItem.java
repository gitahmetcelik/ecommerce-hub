package com.ecommercehub.connector;

/**
 * One line of a customer return, identified by our sku.
 *
 * <p>Returns carry line items because partial returns are the normal case — a customer
 * keeps one of the two shirts they ordered. A return without lines could only ever be
 * read as "all of it", which would credit the customer for goods they still have and
 * put stock back that never came back.
 */
public record ChannelReturnItem(String sku, int quantity) {
}
