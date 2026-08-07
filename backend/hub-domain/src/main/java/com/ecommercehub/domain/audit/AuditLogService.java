package com.ecommercehub.domain.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Plan §10: "audit: sign-in, sign-out and permission changes are recorded"
 *
 * <p>Writes on the hub_system pool and therefore outside the caller's transaction, on
 * purpose. An audit entry that rolls back with the operation it describes cannot
 * record a rejected login, a failed permission check, or anything else that ends in an
 * exception — which is precisely the set of events an audit trail exists for.
 */
@Service
public class AuditLogService {

    public static final String LOGIN_SUCCEEDED = "LOGIN_SUCCEEDED";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String SESSIONS_REVOKED = "SESSIONS_REVOKED";
    public static final String USER_INVITED = "USER_INVITED";
    public static final String INVITATION_ACCEPTED = "INVITATION_ACCEPTED";
    public static final String PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED";
    public static final String PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED";
    public static final String RETURN_APPROVED = "RETURN_APPROVED";
    public static final String RETURN_REJECTED = "RETURN_REJECTED";
    public static final String REFUND_AUTHORIZED = "REFUND_AUTHORIZED";
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String PRICE_LIST_SET = "PRICE_LIST_SET";
    public static final String PRICE_CHANNEL_SET = "PRICE_CHANNEL_SET";
    public static final String PRICE_CHANNEL_CLEARED = "PRICE_CHANNEL_CLEARED";

    private final NamedParameterJdbcTemplate systemJdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuditLogService(
            @org.springframework.beans.factory.annotation.Qualifier("systemJdbcTemplate")
            NamedParameterJdbcTemplate systemJdbcTemplate,
            ObjectMapper objectMapper) {
        this.systemJdbcTemplate = systemJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** @param userId null when the actor could not be identified — a failed login on an unknown email, say. */
    public void record(UUID organizationId, UUID userId, String action, Map<String, Object> details) {
        systemJdbcTemplate.update("""
                INSERT INTO hub.audit_log (id, organization_id, user_id, action, details)
                VALUES (gen_random_uuid(), :org, :user, :action, CAST(:details AS jsonb))
                """, new MapSqlParameterSource()
                .addValue("org", organizationId)
                .addValue("user", userId)
                .addValue("action", action)
                .addValue("details", toJson(details)));
    }

    private String toJson(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit details", e);
        }
    }
}
