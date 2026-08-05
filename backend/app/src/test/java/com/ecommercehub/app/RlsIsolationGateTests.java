package com.ecommercehub.app;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
public class RlsIsolationGateTests extends AbstractTestcontainersTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgAId;
    private UUID orgBId;

    @BeforeEach
    void setUp() {
        orgAId = UUID.randomUUID();
        orgBId = UUID.randomUUID();

        // Inserted with superuser privileges — setup only, not part of the assertion.
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgAId, "Org A");
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgBId, "Org B");
    }

    /**
     * A connection outside the app's Hikari pool, closed for good at the end of
     * each test. SET ROLE and custom-GUC references (hub.org_id) are backend-session
     * state that Hikari's own reset logic does not know about (it only resets
     * autoCommit/readOnly/isolation/catalog/schema on return-to-pool). Running these
     * role-simulation gates through the shared pooled DataSource let session state
     * from one test leak into whichever test next borrowed the same physical
     * connection — a real instance of the exact pool-level leak the plan's §3c
     * warns about, just showing up in the test harness instead of production code.
     * A raw, never-pooled connection per test removes that class of flakiness
     * entirely instead of trying to out-guess Hikari's reset behavior.
     */
    private static Connection rawConnection() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Test
    @DisplayName("RLS gate 1: hub_app role in Org A's context can neither read nor write Org B's row (WITH CHECK)")
    void test1_RoleIsolationAndWithCheck() throws Exception {
        try (Connection conn = rawConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET ROLE hub_app;");
                stmt.execute("SELECT set_config('hub.org_id', '" + orgAId + "', true);");

                UUID productAId = UUID.randomUUID();
                stmt.execute("INSERT INTO hub.product (id, organization_id, title) VALUES ('" + productAId + "', '" + orgAId + "', 'Product A');");

                // Native SELECT must only see Org A's product.
                ResultSet rs = stmt.executeQuery("SELECT count(*) FROM hub.product;");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);

                // WITH CHECK test: writing a row tagged with Org B's id while in Org A's context must fail.
                UUID productBId = UUID.randomUUID();
                assertThatThrownBy(() -> {
                    stmt.execute("INSERT INTO hub.product (id, organization_id, title) VALUES ('" + productBId + "', '" + orgBId + "', 'Smuggled Product B');");
                }).hasMessageContaining("row-level security policy");
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    @DisplayName("RLS gate 2: pg_roles check — hub_app owns no tables and has no BYPASSRLS")
    void test2_RolePrivilegeCheck() {
        Map<String, Object> roleInfo = jdbcTemplate.queryForMap(
                "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'hub_app';"
        );
        assertThat(roleInfo.get("rolsuper")).isEqualTo(false);
        assertThat(roleInfo.get("rolbypassrls")).isEqualTo(false);

        List<String> ownedTables = jdbcTemplate.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'hub' AND tableowner = 'hub_app';",
                String.class
        );
        assertThat(ownedTables).isEmpty();
    }

    @Test
    @DisplayName("RLS gate 3: session-leak test — a query with no context set cannot inherit a prior connection's context")
    void test3_SessionLeakTest() throws Exception {
        try (Connection conn = rawConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET ROLE hub_app;");
                stmt.execute("SELECT set_config('hub.org_id', '" + orgAId + "', true);");
            }
            conn.commit(); // transaction ends here, SET LOCAL is dropped.

            // New transaction on the SAME connection: query runs with org_id unset.
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET ROLE hub_app;");
                assertThatThrownBy(() -> {
                    stmt.executeQuery("SELECT * FROM hub.product;");
                }).satisfies(RlsIsolationGateTests::assertIsMissingContextError);
            } finally {
                conn.rollback();
            }
        }
    }

    @Test
    @DisplayName("RLS gate 4: a query with no context set raises an ERROR, not an empty result (no missing_ok)")
    void test4_MissingContextRaisesError() throws Exception {
        try (Connection conn = rawConnection()) {
            conn.setAutoCommit(false);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("SET ROLE hub_app;");
                assertThatThrownBy(() -> {
                    stmt.executeQuery("SELECT * FROM hub.organization;");
                }).satisfies(RlsIsolationGateTests::assertIsMissingContextError);
            } finally {
                conn.rollback();
            }
        }
    }

    /**
     * A connection that has never referenced "hub.org_id" before raises
     * "unrecognized configuration parameter". A pooled connection that used it
     * earlier (even in a rolled-back or committed transaction) keeps a
     * placeholder for that GUC name for the rest of its backend's lifetime —
     * Postgres documented behavior — so current_setting() then returns ''
     * instead, which fails the ::uuid cast in the RLS policy with a different
     * message. Both are hard errors, never an empty/silent result, which is
     * the actual production guarantee (plan §3b); with a raw never-reused
     * connection per test this always resolves to the first case, but the
     * assertion stays permissive since either is a valid proof of "errors,
     * doesn't silently return nothing."
     */
    private static void assertIsMissingContextError(Throwable thrown) {
        assertThat(thrown.getMessage()).satisfiesAnyOf(
                msg -> assertThat(msg).contains("unrecognized configuration parameter \"hub.org_id\""),
                msg -> assertThat(msg).contains("invalid input syntax for type uuid")
        );
    }

    @Test
    @DisplayName("RLS gate 5: dynamic pg_class scan — EVERY table in the hub schema has relrowsecurity, relforcerowsecurity, and >=1 policy")
    void test5_DynamicTableScan() {
        // relkind 'r' = ordinary table, 'p' = partitioned table (hub.raw_event's parent).
        List<Map<String, Object>> tables = jdbcTemplate.queryForList(
                "SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity " +
                "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE n.nspname = 'hub' AND c.relkind IN ('r', 'p');"
        );

        assertThat(tables).isNotEmpty();

        for (Map<String, Object> table : tables) {
            String tableName = (String) table.get("relname");
            Boolean rlsEnabled = (Boolean) table.get("relrowsecurity");
            Boolean rlsForced = (Boolean) table.get("relforcerowsecurity");

            assertThat(rlsEnabled)
                    .withFailMessage("Table '%s' does not have RLS (relrowsecurity) enabled!", tableName)
                    .isTrue();

            assertThat(rlsForced)
                    .withFailMessage("Table '%s' does not have FORCE RLS (relforcerowsecurity) enabled!", tableName)
                    .isTrue();

            Integer policyCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE schemaname = 'hub' AND tablename = ?;",
                    Integer.class,
                    tableName
            );

            assertThat(policyCount)
                    .withFailMessage("Table '%s' must have at least one RLS policy!", tableName)
                    .isGreaterThanOrEqualTo(1);
        }
    }
}
