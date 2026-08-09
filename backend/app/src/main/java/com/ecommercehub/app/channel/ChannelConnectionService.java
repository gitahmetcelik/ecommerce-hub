package com.ecommercehub.app.channel;

import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.CredentialStatus;
import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import com.ecommercehub.ingest.ConnectorRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plan §8.2. Today's only way to create a {@code channel_connection} is a test's raw
 * SQL insert (Faz 1 through Faz 7 all read connections, none of them create one) —
 * this is the first real write path, so it is also the first place credentials are
 * ever encrypted on their way *in* rather than only decrypted on their way out.
 *
 * <p>Every credential-accepting method validates against the live channel before
 * persisting anything (Plan §8.5's "invalid credentials cannot be added at all") by
 * calling the connector's own {@code checkCredentials} — the same call the circuit
 * breaker uses to decide whether a channel may come back out of CREDENTIALS_INVALID.
 */
@Service
public class ChannelConnectionService {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ConnectorRegistry connectorRegistry;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public ChannelConnectionService(NamedParameterJdbcTemplate jdbcTemplate,
                                     CredentialEncryptionService credentialEncryptionService,
                                     ConnectorRegistry connectorRegistry, AuditLogService auditLogService,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.credentialEncryptionService = credentialEncryptionService;
        this.connectorRegistry = connectorRegistry;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID create(AuthenticatedUser actor, String channelType, Object credentials) {
        requireAdmin(actor, "connect a channel");
        String plaintext = toPlaintext(credentials);
        UUID id = UUID.randomUUID();

        checkCredentialsOrThrow(channelType, id, actor.organizationId(), plaintext);
        EncryptedCredential encrypted = credentialEncryptionService.encrypt(plaintext);

        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (:id, :org, :channelType, :ciphertext, :keyVersion)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("org", actor.organizationId())
                .addValue("channelType", channelType)
                .addValue("ciphertext", encrypted.ciphertextBase64())
                .addValue("keyVersion", encrypted.keyVersion()));

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.CHANNEL_CONNECTION_CREATED,
                Map.of("channelConnectionId", id.toString(), "channelType", channelType));
        return id;
    }

    /** Plan §8.2 point 2: the way out of CREDENTIALS_INVALID — clears the circuit breaker's state along with the secret itself. */
    @Transactional
    public void rotateCredentials(AuthenticatedUser actor, UUID channelConnectionId, Object credentials) {
        requireAdmin(actor, "rotate channel credentials");
        String channelType = channelTypeOf(actor.organizationId(), channelConnectionId);
        String plaintext = toPlaintext(credentials);

        checkCredentialsOrThrow(channelType, channelConnectionId, actor.organizationId(), plaintext);
        EncryptedCredential encrypted = credentialEncryptionService.encrypt(plaintext);

        jdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET encrypted_credentials = :ciphertext, key_version = :keyVersion,
                    status = CASE WHEN status = 'CREDENTIALS_INVALID' THEN 'ACTIVE' ELSE status END,
                    consecutive_failures = 0, circuit_open_until = NULL, last_failure_reason = NULL,
                    updated_at = now(), version = version + 1
                WHERE id = :id AND organization_id = :org
                """, new MapSqlParameterSource()
                .addValue("id", channelConnectionId)
                .addValue("org", actor.organizationId())
                .addValue("ciphertext", encrypted.ciphertextBase64())
                .addValue("keyVersion", encrypted.keyVersion()));

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.CHANNEL_CONNECTION_CREDENTIALS_ROTATED,
                Map.of("channelConnectionId", channelConnectionId.toString()));
    }

    @Transactional
    public void updateSettings(AuthenticatedUser actor, UUID channelConnectionId, Integer reconcileIntervalMinutes,
                                Integer allocationPriority) {
        requireAdmin(actor, "update channel settings");
        if (reconcileIntervalMinutes != null && reconcileIntervalMinutes < 1) {
            throw new IllegalArgumentException("reconcileIntervalMinutes must be at least 1");
        }

        int updated = jdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET reconcile_interval_minutes = COALESCE(:interval, reconcile_interval_minutes),
                    allocation_priority = COALESCE(:priority, allocation_priority),
                    updated_at = now(), version = version + 1
                WHERE id = :id AND organization_id = :org
                """, new MapSqlParameterSource()
                .addValue("id", channelConnectionId)
                .addValue("org", actor.organizationId())
                .addValue("interval", reconcileIntervalMinutes)
                .addValue("priority", allocationPriority));
        if (updated == 0) {
            throw new NoChannelConnectionException(channelConnectionId);
        }

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.CHANNEL_CONNECTION_SETTINGS_UPDATED,
                Map.of("channelConnectionId", channelConnectionId.toString()));
    }

    /**
     * Resets the backfill cursor to null rather than pushing a task — {@link
     * com.ecommercehub.app.backfill.BackfillScheduler} already polls every connection
     * whose {@code backfill_status} is null or not {@code ordersDone} on a 5s tick, so
     * clearing the column is all a manual trigger needs to do to be picked up.
     */
    @Transactional
    public void triggerBackfill(AuthenticatedUser actor, UUID channelConnectionId) {
        requireAdmin(actor, "trigger backfill");
        int updated = jdbcTemplate.update("""
                UPDATE hub.channel_connection
                SET backfill_status = NULL, updated_at = now(), version = version + 1
                WHERE id = :id AND organization_id = :org
                """, new MapSqlParameterSource().addValue("id", channelConnectionId).addValue("org", actor.organizationId()));
        if (updated == 0) {
            throw new NoChannelConnectionException(channelConnectionId);
        }

        auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.CHANNEL_CONNECTION_BACKFILL_TRIGGERED,
                Map.of("channelConnectionId", channelConnectionId.toString()));
    }

    /**
     * Plan §8.2 point 6. {@code backfill_status} is read back through an explicit
     * {@code ::text} cast: the plain JDBC driver hands a {@code jsonb} column back as a
     * driver-specific wrapper, not a String, so casting in SQL and re-parsing here is
     * what guarantees the client actually receives a JSON object instead of that
     * wrapper's own field names.
     */
    public Map<String, Object> detail(UUID organizationId, UUID channelConnectionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, channel_type, status, consecutive_failures, circuit_open_until, last_failure_reason,
                       reconcile_interval_minutes, next_reconcile_at, allocation_priority,
                       last_order_sync_at, last_return_sync_at, key_version, created_at, updated_at,
                       backfill_status::text AS backfill_status
                FROM hub.channel_connection WHERE id = :id AND organization_id = :org
                """, new MapSqlParameterSource().addValue("id", channelConnectionId).addValue("org", organizationId));
        if (rows.isEmpty()) {
            throw new NoChannelConnectionException(channelConnectionId);
        }

        Map<String, Object> row = new HashMap<>(rows.get(0));
        row.put("backfill_status", parseJsonOrNull((String) row.get("backfill_status")));
        row.put("budgets", jdbcTemplate.queryForList("""
                SELECT budget_class, tokens, refilled_at, backoff_until
                FROM hub.channel_rate_budget WHERE channel_connection_id = :id
                """, new MapSqlParameterSource("id", channelConnectionId)));
        return row;
    }

    private void checkCredentialsOrThrow(String channelType, UUID channelConnectionId, UUID organizationId, String plaintext) {
        CredentialStatus status = connectorRegistry.require(channelType)
                .checkCredentials(new ChannelConnectionRef(channelConnectionId, organizationId, channelType, plaintext));
        if (!status.valid()) {
            throw new InvalidChannelCredentialsException(status.reason());
        }
    }

    private String channelTypeOf(UUID organizationId, UUID channelConnectionId) {
        List<String> found = jdbcTemplate.queryForList("""
                SELECT channel_type FROM hub.channel_connection WHERE id = :id AND organization_id = :org
                """, new MapSqlParameterSource().addValue("id", channelConnectionId).addValue("org", organizationId), String.class);
        if (found.isEmpty()) {
            throw new NoChannelConnectionException(channelConnectionId);
        }
        return found.get(0);
    }

    /** Accepts either a bare string (MOCK/MOCK_BARCODE's base-URL credential) or a JSON object (SHOPIFY's key/secret shape) — the connector decides how to parse it, this service stays connector-agnostic. */
    private String toPlaintext(Object credentials) {
        if (credentials instanceof String s) {
            return s;
        }
        try {
            return objectMapper.writeValueAsString(credentials);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize channel credentials", e);
        }
    }

    private Map<String, Object> parseJsonOrNull(String json) {
        if (json == null) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return parsed;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt backfill_status JSON", e);
        }
    }

    private void requireAdmin(AuthenticatedUser actor, String action) {
        if (!actor.hasAtLeast(HubRole.ADMIN)) {
            auditLogService.record(actor.organizationId(), actor.userId(), AuditLogService.PERMISSION_DENIED,
                    Map.of("action", action, "role", actor.effectiveRole().name(), "required", "ADMIN"));
            throw new InsufficientRoleException(actor.effectiveRole(), HubRole.ADMIN);
        }
    }

    public static final class NoChannelConnectionException extends RuntimeException {
        public NoChannelConnectionException(UUID channelConnectionId) {
            super("No channel connection " + channelConnectionId);
        }
    }

    public static final class InvalidChannelCredentialsException extends RuntimeException {
        public InvalidChannelCredentialsException(String reason) {
            super(reason == null || reason.isBlank() ? "The channel rejected these credentials" : reason);
        }
    }
}
