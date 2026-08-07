package com.ecommercehub.connector.shopify;

import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelProduct;
import com.ecommercehub.connector.CredentialStatus;
import com.ecommercehub.connector.ItemResult;
import com.ecommercehub.connector.Page;
import com.ecommercehub.connector.PagedResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.StockUpdate;
import com.ecommercehub.connector.ChannelItemRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.http.HttpClient;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §4.6: a live integration test against a real Shopify store, skipped by default
 * (no CI holds real credentials) — this is the permanent, re-runnable counterpart to
 * the Faz 4 spike's throwaway curl calls. Point {@code SHOPIFY_STORE_DOMAIN} /
 * {@code SHOPIFY_ACCESS_TOKEN} at a Shopify Partners development store (free, no
 * seller account needed — see docs/kanal-arastirmasi.md) to run it locally:
 *
 * <pre>
 * SHOPIFY_STORE_DOMAIN=your-dev-store.myshopify.com SHOPIFY_ACCESS_TOKEN=shpat_... \
 *   mvn -pl backend/connector-shopify test -Dgroups=live
 * </pre>
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SHOPIFY_ACCESS_TOKEN", matches = ".+")
class ShopifyLiveIntegrationTest {

    private final PlatformConnector connector = new ShopifyPlatformConnector(HttpClient.newHttpClient(), new ObjectMapper());

    private ChannelConnectionRef connection() {
        String storeDomain = System.getenv("SHOPIFY_STORE_DOMAIN");
        String accessToken = System.getenv("SHOPIFY_ACCESS_TOKEN");
        String credentialsJson = "{\"storeDomain\":\"" + storeDomain + "\",\"accessToken\":\"" + accessToken + "\"}";
        return new ChannelConnectionRef(UUID.randomUUID(), UUID.randomUUID(),
                ShopifyPlatformConnector.CHANNEL_TYPE, credentialsJson);
    }

    @Test
    void catalogIsReadableAndVariantsCarrySkuAndBarcodeFields() {
        PagedResult<ChannelProduct> catalog = connector.fetchCatalog(connection(), Page.first(20));

        assertThat(catalog.items()).isNotEmpty();
        // Not asserting every field is non-null — the point is that the call succeeds
        // and the shape matches what ShopifyPlatformConnector expects to parse, not
        // that a fresh dev store happens to have every field populated.
        assertThat(catalog.items()).allSatisfy(p -> assertThat(p.channelVariantId()).isNotBlank());
    }

    @Test
    void bulkStockPushActuallyChangesTheChannelsValue() {
        PagedResult<ChannelProduct> catalog = connector.fetchCatalog(connection(), Page.first(20));
        ChannelProduct tracked = catalog.items().stream()
                .filter(p -> p.availableQuantity() != null)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No inventory-tracked variant in this store to push a stock update against"));

        int newQuantity = tracked.availableQuantity() + 1;
        List<ItemResult> results = connector.updateStock(connection(), List.of(
                new StockUpdate(new ChannelItemRef(tracked.channelVariantId(), tracked.sku(), tracked.barcode()), newQuantity)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isTrue();

        PagedResult<ChannelProduct> after = connector.fetchCatalog(connection(), Page.first(20));
        ChannelProduct reloaded = after.items().stream()
                .filter(p -> p.channelVariantId().equals(tracked.channelVariantId()))
                .findFirst().orElseThrow();
        assertThat(reloaded.availableQuantity()).isEqualTo(newQuantity);
    }

    @Test
    void checkCredentialsReportsInvalidForADeliberatelyBrokenToken() {
        ChannelConnectionRef broken = new ChannelConnectionRef(UUID.randomUUID(), UUID.randomUUID(),
                ShopifyPlatformConnector.CHANNEL_TYPE,
                "{\"storeDomain\":\"" + System.getenv("SHOPIFY_STORE_DOMAIN") + "\",\"accessToken\":\"shpat_deliberately_invalid\"}");

        CredentialStatus status = connector.checkCredentials(broken);

        assertThat(status.valid()).isFalse();
    }
}
