package com.ecommercehub.ingest;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Deliberately thin — reads the raw bytes (Plan §3: HMAC verification must see the
 * exact wire bytes, before Jackson or anything else touches them) and headers, then
 * hands off to {@link WebhookIngestOrchestrator} for everything transactional.
 *
 * <p>Plan v5 Faz 5: {@code @Profile("api")} — the &lt;200ms ACK target is exactly what
 * the api/worker split protects, so this stays in the process that does nothing else.
 */
@RestController
@Profile("api")
public class WebhookController {

    private final WebhookIngestOrchestrator orchestrator;

    public WebhookController(WebhookIngestOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/webhooks/{organizationId}/{channelConnectionId}")
    public ResponseEntity<?> receive(@PathVariable UUID organizationId, @PathVariable UUID channelConnectionId,
                                      HttpServletRequest request) throws IOException {
        byte[] bodyBytes = request.getInputStream().readAllBytes();
        Map<String, String> headers = extractHeaders(request);

        try {
            WebhookIngestResult result = orchestrator.receive(organizationId, channelConnectionId, bodyBytes, headers);
            return ResponseEntity.ok(Map.of("received", true, "new", result.isNew(), "traceId", result.traceId()));
        } catch (UnknownChannelConnectionException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (InvalidWebhookSignatureException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, String> extractHeaders(HttpServletRequest request) {
        // Case-insensitive on purpose — HTTP header names aren't case-sensitive, and
        // PlatformConnector.verifySignature does a plain Map lookup by name.
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return Collections.emptyMap();
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name, request.getHeader(name));
        }
        return headers;
    }
}
