package dev.rojae.llmgateway.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rojae.llmgateway.security.AdminAccess;
import dev.rojae.llmgateway.security.GatewaySecurityProperties;
import dev.rojae.llmgateway.security.TenantClaims;
import dev.rojae.llmgateway.security.TenantPrincipal;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.web.reactive.server.WebTestClient;
import static org.assertj.core.api.Assertions.assertThat;

/** Bounded hostile HTTP inputs and retained-record accounting against a disposable PostgreSQL DB. */
@EnabledIfEnvironmentVariable(named = "GW_TEST_DB_URL", matches = ".+")
class MonitoringCostsAdversarialContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String tenant = "cost-hostile-" + UUID.randomUUID();
    private final String foreignTenant = "cost-foreign-" + UUID.randomUUID();
    private AnalyticsDatabase db;
    private AuditService audit;
    private AdminAccess access;
    private MonitoringCostsController controller;
    private WebTestClient client;
    private UUID owner;
    private UUID other;
    private UUID foreignOwner;
    private UUID session;

    @BeforeEach void setup() throws Exception {
        var properties = new AnalyticsProperties();
        properties.setEnabled(true);
        properties.setJdbcUrl(System.getenv("GW_TEST_DB_URL"));
        properties.setUsername(System.getenv("GW_TEST_DB_USER"));
        properties.setPassword(System.getenv("GW_TEST_DB_PASSWORD"));
        db = new AnalyticsDatabase(properties);
        db.initialize();
        audit = new AuditService(db);
        var security = new GatewaySecurityProperties();
        var beans = new StaticListableBeanFactory();
        beans.addBean("audit", audit);
        access = new AdminAccess(security, new TenantClaims(security), beans.getBeanProvider(AuditService.class));
        controller = new MonitoringCostsController(new MonitoringCosts(db, audit), access);
        client = WebTestClient.bindToController(controller).webFilter((exchange, chain) -> {
            String requestTenant = exchange.getRequest().getHeaders().getFirst("Test-Tenant");
            if (requestTenant != null) {
                String role = exchange.getRequest().getHeaders().getFirst("Test-Role");
                exchange.getAttributes().put(AdminAccess.PRINCIPAL_ATTRIBUTE,
                    new TenantPrincipal(requestTenant, "hostile-fixture", Set.of(role == null ? "admin" : role)));
            }
            return chain.filter(exchange);
        }).configureClient().responseTimeout(Duration.ofSeconds(10)).build();
        var queries = new MonitoringQueries(db, audit);
        owner = (UUID) queries.createAccount(tenant, "hostile-fixture", "Owner").get("id");
        other = (UUID) queries.createAccount(tenant, "hostile-fixture", "Other").get("id");
        foreignOwner = (UUID) queries.createAccount(foreignTenant, "hostile-fixture", "Foreign").get("id");
        session = session(owner, "codex");
    }

    @AfterEach void close() {
        if (controller != null) controller.close();
        if (access != null) access.close();
        if (db != null) db.close();
    }

    @Test void utcMonthIncludesStartAndExcludesExactNextMonth() throws Exception {
        assertMonthBoundary("2026-10", "UTC", "2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z");
    }

    @Test void seoulMonthUsesLocalMidnightRatherThanUtcDate() throws Exception {
        assertMonthBoundary("2026-10", "Asia/Seoul", "2026-09-30T15:00:00Z", "2026-10-31T15:00:00Z");
    }

    @Test void springDstMonthUsesDifferentOffsetsAtItsTwoBoundaries() throws Exception {
        assertMonthBoundary("2026-03", "America/New_York", "2026-03-01T05:00:00Z", "2026-04-01T04:00:00Z");
    }

    @Test void autumnDstMonthUsesDifferentOffsetsAtItsTwoBoundaries() throws Exception {
        assertMonthBoundary("2026-11", "America/New_York", "2026-11-01T04:00:00Z", "2026-12-01T05:00:00Z");
    }

    @Test void leapDayIsInFebruaryAndMarchStartIsOutside() throws Exception {
        UUID leapDay = question(session, "2024-02-29T23:59:59.999999Z");
        UUID march = question(session, "2024-03-01T00:00:00Z");
        configure(owner, "2024-02", "9", "USD");
        var leapReport = get(owner, query("2024-02", "UTC", session, leapDay));
        assertThat(leapReport.path("subscription").path("questionCount").asLong()).isEqualTo(1);
        assertThat(leapReport.path("question").path("inMonth").asBoolean()).isTrue();
        assertThat(leapReport.path("question").path("subscriptionAllocation").decimalValue()).isEqualByComparingTo("9");
        var outside = get(owner, query("2024-02", "UTC", session, march)).path("question");
        assertThat(outside.path("inMonth").asBoolean()).isFalse();
        assertThat(outside.path("subscriptionAllocation").isNull()).isTrue();
    }

    @Test void denominatorIncludesBothCliSourcesButOnlyRetainedOwnerQuestions() throws Exception {
        UUID claude = session(owner, "claude_code");
        UUID empty = session(owner, "codex");
        UUID purgedSession = session(owner, "claude_code");
        UUID otherSession = session(other, "codex");
        question(session, "2026-10-02T00:00:00Z");
        question(claude, "2026-10-03T00:00:00Z");
        UUID purgedQuestion = question(session, "2026-10-04T00:00:00Z");
        question(purgedSession, "2026-10-05T00:00:00Z");
        question(otherSession, "2026-10-06T00:00:00Z");
        question(session, "2026-09-30T23:59:59Z");
        purge("monitoring_questions", purgedQuestion);
        purge("monitoring_sessions", purgedSession);
        configure(owner, "2026-10", "10", "USD");
        var report = get(owner, query("2026-10", "UTC", empty, null));
        assertThat(report.path("subscription").path("allocationBasis").asText()).isEqualTo("recorded_questions");
        assertThat(report.path("subscription").path("questionCount").asLong()).isEqualTo(2);
        assertThat(report.path("subscription").path("sessionCount").asLong()).isEqualTo(2);
        assertThat(report.path("session").path("questionCount").asLong()).isZero();
        assertThat(report.path("session").path("subscriptionAllocation").decimalValue()).isEqualByComparingTo("0");
    }

    @Test void selectedEmptySessionHasUnknownAllocationWhenAccountDenominatorIsZero() throws Exception {
        configure(owner, "2026-10", "10", "KRW");
        var report = get(owner, query("2026-10", "UTC", session, null));
        assertThat(report.path("subscription").path("questionCount").asLong()).isZero();
        assertThat(report.path("subscription").path("sessionCount").asLong()).isZero();
        assertThat(report.path("subscription").path("perQuestion").isNull()).isTrue();
        assertThat(report.path("session").path("subscriptionAllocation").isNull()).isTrue();
        assertThat(report.path("session").path("latestObservation").isNull()).isTrue();
    }

    @Test void sixDecimalRoundingAndProvisionalReallocationFollowNewRetainedQuestions() throws Exception {
        UUID second = session(owner, "claude_code");
        UUID selected = question(session, "2026-10-01T00:00:00Z");
        question(session, "2026-10-02T00:00:00Z");
        question(second, "2026-10-03T00:00:00Z");
        configure(owner, "2026-10", "1", "USD");
        var before = get(owner, query("2026-10", "UTC", session, selected));
        assertThat(before.path("subscription").path("perQuestion").decimalValue()).isEqualByComparingTo("0.333333");
        assertThat(before.path("session").path("subscriptionAllocation").decimalValue()).isEqualByComparingTo("0.666667");
        question(second, "2026-10-04T00:00:00Z");
        var after = get(owner, query("2026-10", "UTC", session, selected));
        assertThat(after.path("subscription").path("perQuestion").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(after.path("session").path("subscriptionAllocation").decimalValue()).isEqualByComparingTo("0.5");
        assertThat(after.path("question").path("subscriptionAllocation").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(configurationAudits()).hasSize(1);
    }

    @Test void malformedGetFiltersFailWithoutMutationOrConfigurationAudit() throws Exception {
        configure(owner, "2026-10", "12", "USD");
        List<String> queries = List.of("", "?month=2026-10", "?zone=UTC", "?month=&zone=UTC",
            "?month=2026-10&zone=", "?month=2026-10&zone=UTC&zone=UTC",
            "?month=2026-10&zone=UTC&sessionId=", "?month=2026-10&zone=UTC&sessionId=bad",
            "?month=2026-10&zone=UTC&sessionId=1-1-1-1-1", "?month=2026-10&zone=UTC&questionId=bad",
            "?month=2026-10&zone=UTC&limit=1", "?month=2026-10&zone=UTC&offset=0",
            "?month=2026-10&zone=UTC&sessionId=" + session + "&sessionId=" + session,
            "?month=2026-10&zone=UTC&sessionId=" + session + "&questionId=&questionId=");
        for (String query : queries) {
            client.get().uri(path(owner) + query).header("Test-Tenant", tenant).exchange()
                .expectStatus().isBadRequest().expectHeader().valueEquals("Cache-Control", "no-store");
        }
        assertSetting("12", "USD");
        assertThat(configurationAudits()).hasSize(1);
    }

    @Test void invalidPathIdsFailSafelyAndCompactIdsStillRespectTenantOwnership() {
        for (String id : List.of("bad", "123", "ffffffff-ffff-ffff-ffff-ffffffffffffx")) {
            client.get().uri("/admin/api/monitoring/accounts/" + id + "/costs?month=2026-10&zone=UTC")
                .header("Test-Tenant", tenant).exchange().expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store");
        }
        // Compact groups may be rejected or normalized; neither path can expose an absent owner.
        client.get().uri("/admin/api/monitoring/accounts/1-1-1-1-1/costs?month=2026-10&zone=UTC")
            .header("Test-Tenant", tenant).exchange().expectStatus().value(status -> assertThat(status).isIn(400, 404))
            .expectHeader().valueEquals("Cache-Control", "no-store");
    }

    @Test void extremeNumericLiteralsAreClientErrorsAndCannotOverwriteStoredAmount() throws Exception {
        configure(owner, "2026-10", "23", "KRW");
        for (String literal : List.of("1e10000", "1e2147483647", "1e2147483648", "1e-2147483648",
            "1e-10000", "1000000000.000001", "9999999999999999999999999999999999999999",
            "0.0000001", "-0.000001", "NaN", "Infinity", "nullx", "01", "1.")) {
            put(owner, "admin", body("2026-10", literal, "USD")).expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store");
            assertSetting("23", "KRW");
        }
        assertThat(configurationAudits()).hasSize(1);
    }

    @Test void exactSixDecimalPrecisionAllowsTrailingZerosAndMathematicalNegativeZero() throws Exception {
        for (String literal : List.of("0.1234560", "1.23000000", "-0", "-0.000000", "1e-6", "1e9")) {
            put(owner, "admin", body("2026-10", literal, "USD")).expectStatus().isOk()
                .expectHeader().valueEquals("Cache-Control", "no-store");
            assertThat(get(owner, query("2026-10", "UTC", null, null)).path("subscription").path("amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal(literal));
        }
        assertThat(configurationAudits()).hasSize(6);
    }

    @Test void malformedBodiesAndUnexpectedPutQueryDoNotWriteSettings() throws Exception {
        configure(owner, "2026-10", "17", "USD");
        for (String invalid : List.of("", "[]", "null", "{}", "{\"month\":\"2026-10\",\"currency\":\"USD\"}",
            "{\"month\":202610,\"amount\":1,\"currency\":\"USD\"}",
            "{\"month\":\"2026-10\",\"amount\":1,\"currency\":null}",
            body("2026-10", "\"1\"", "USD"), body("2026-10", "true", "USD"), body("2026-10", "{}", "USD"),
            body("2026-10", "1", "usd"), body("2026-10", "1", "EUR"),
            "{\"month\":\"2026-10\",\"amount\":1,\"amount\":2,\"currency\":\"USD\"}",
            body("2026-10", "1", "USD") + " true")) {
            put(owner, "admin", invalid).expectStatus().isBadRequest()
                .expectHeader().valueEquals("Cache-Control", "no-store");
        }
        client.put().uri(path(owner) + "/subscription?month=2026-10").header("Test-Tenant", tenant)
            .header("Content-Type", "application/json").bodyValue(body("2026-10", "99", "USD"))
            .exchange().expectStatus().isBadRequest().expectHeader().valueEquals("Cache-Control", "no-store");
        assertSetting("17", "USD");
        assertThat(configurationAudits()).hasSize(1);
    }

    @Test void unauthenticatedAndPermissionFailuresAreNoStoreAndDenialsAreAudited() throws Exception {
        client.get().uri(path(owner) + "?month=2026-10&zone=UTC").exchange().expectStatus().isUnauthorized()
            .expectHeader().valueEquals("Cache-Control", "no-store");
        client.put().uri(path(owner) + "/subscription").header("Content-Type", "application/json")
            .bodyValue(body("2026-10", "1", "USD")).exchange().expectStatus().isUnauthorized()
            .expectHeader().valueEquals("Cache-Control", "no-store");
        client.get().uri(path(owner) + "?month=2026-10&zone=UTC").header("Test-Tenant", tenant)
            .header("Test-Role", "unknown").exchange().expectStatus().isForbidden()
            .expectHeader().valueEquals("Cache-Control", "no-store");
        for (String role : List.of("viewer", "operator")) {
            put(owner, role, body("2026-10", "1", "USD")).expectStatus().isForbidden()
                .expectHeader().valueEquals("Cache-Control", "no-store");
            client.get().uri(path(owner) + "?month=2026-10&zone=UTC").header("Test-Tenant", tenant)
                .header("Test-Role", role).exchange().expectStatus().isOk();
        }
        var denials = audit.list(tenant, 100, 0).items().stream().filter(e -> e.action().equals("access.denied")).toList();
        assertThat(denials).hasSize(3).allSatisfy(e -> {
            assertThat(e.actor()).isEqualTo("hostile-fixture");
            assertThat(e.outcome()).isEqualTo("denied");
        });
        assertThat(denials.stream().filter(e -> e.target().equals("usage:read"))).hasSize(1);
        assertThat(denials.stream().filter(e -> e.target().equals("collectors:manage"))).hasSize(2);
        assertThat(configurationAudits()).isEmpty();
    }

    @Test void foreignOwnersAndWrongQuestionSessionsDoNotLeakOrMutateCosts() throws Exception {
        var foreignCosts = new MonitoringCosts(db, audit);
        foreignCosts.configure(foreignTenant, foreignOwner, "foreign-fixture", "2026-10", new BigDecimal("991"), "KRW");
        UUID otherSession = session(other, "codex");
        UUID wrongQuestion = question(otherSession, "2026-10-01T00:00:00Z");
        for (String uri : List.of(path(foreignOwner) + "?month=2026-10&zone=UTC",
            path(owner) + query("2026-10", "UTC", otherSession, null),
            path(owner) + query("2026-10", "UTC", session, wrongQuestion))) {
            byte[] response = client.get().uri(uri).header("Test-Tenant", tenant).exchange().expectStatus().isNotFound()
                .expectHeader().valueEquals("Cache-Control", "no-store").expectBody(byte[].class).returnResult().getResponseBody();
            assertThat(JSON.readTree(response).path("error").path("code").asText()).isEqualTo("not_found");
            assertThat(new String(response, java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("991", "KRW");
        }
        put(foreignOwner, "admin", body("2026-10", "5", "USD")).expectStatus().isNotFound();
        var preserved = foreignCosts.report(foreignTenant, foreignOwner,
            new MonitoringCosts.Filter(YearMonth.of(2026, 10), ZoneId.of("UTC"), null, null));
        assertThat(preserved.subscription().amount()).isEqualByComparingTo("991");
        assertThat(preserved.subscription().currency()).isEqualTo("KRW");
        assertThat(configurationAudits()).isEmpty();
        assertThat(audit.list(foreignTenant, 100, 0).items().stream().filter(e -> e.action().equals("monitoring.cost.configure"))).hasSize(1);
    }

    @Test void bodyLimitIsEnforcedForBothDeclaredAndUnderdeclaredContentLengths() throws Exception {
        configure(owner, "2026-10", "19", "USD");
        String oversized = body("2026-10", "1", "USD") + " ".repeat(8193);
        for (String length : List.of(Integer.toString(oversized.length()), "1")) {
            client.put().uri(path(owner) + "/subscription").header("Test-Tenant", tenant)
                .header("Content-Type", "application/json").header("Content-Length", length).bodyValue(oversized)
                .exchange().expectStatus().isEqualTo(413).expectHeader().valueEquals("Cache-Control", "no-store");
        }
        assertSetting("19", "USD");
        assertThat(configurationAudits()).hasSize(1);
    }

    @Test void historicalMonthSettingsPersistAcrossFreshServiceInstancesAndRemainIndependent() throws Exception {
        configure(owner, "2026-09", "30.123456", "KRW");
        configure(owner, "2026-10", "20", "USD");
        configure(owner, "2026-10", "21", "USD");
        var fresh = new MonitoringCosts(db, new AuditService(db));
        var september = fresh.report(tenant, owner, new MonitoringCosts.Filter(YearMonth.of(2026, 9), ZoneId.of("UTC"), null, null));
        var october = fresh.report(tenant, owner, new MonitoringCosts.Filter(YearMonth.of(2026, 10), ZoneId.of("Asia/Seoul"), null, null));
        var november = fresh.report(tenant, owner, new MonitoringCosts.Filter(YearMonth.of(2026, 11), ZoneId.of("UTC"), null, null));
        assertThat(september.subscription().amount()).isEqualByComparingTo("30.123456");
        assertThat(september.subscription().currency()).isEqualTo("KRW");
        assertThat(october.subscription().amount()).isEqualByComparingTo("21");
        assertThat(november.subscription().amount()).isNull();
        assertThat(configurationAudits()).hasSize(3).allSatisfy(e -> {
            assertThat(e.outcome()).isEqualTo("success");
            assertThat(e.target()).startsWith(owner + ":2026-");
        });
    }

    @Test void boundedConcurrentWritesLeaveOneCompleteSettingAndOneAuditPerCommittedWrite() throws Exception {
        int writers = 4;
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(writers)) {
            var futures = new ArrayList<java.util.concurrent.Future<Void>>();
            for (int i = 1; i <= writers; i++) {
                int value = i;
                futures.add(executor.submit(() -> {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    new MonitoringCosts(db, new AuditService(db)).configure(tenant, owner, "writer-" + value,
                        "2026-10", BigDecimal.valueOf(value), value % 2 == 0 ? "KRW" : "USD");
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        var persisted = get(owner, query("2026-10", "UTC", null, null)).path("subscription");
        int amount = persisted.path("amount").decimalValue().intValueExact();
        assertThat(amount).isBetween(1, writers);
        assertThat(persisted.path("currency").asText()).isEqualTo(amount % 2 == 0 ? "KRW" : "USD");
        var writes = configurationAudits();
        assertThat(writes).hasSize(writers).allSatisfy(e -> {
            assertThat(e.outcome()).isEqualTo("success");
            assertThat(e.target()).isEqualTo(owner + ":2026-10");
        });
        assertThat(writes.stream().map(AuditService.Event::actor)).containsExactlyInAnyOrder("writer-1", "writer-2", "writer-3", "writer-4");
        try (var connection = db.open(); var statement = connection.prepareStatement(
            "SELECT count(*) FROM llm_gateway_analytics.monitoring_subscription_costs WHERE tenant_id=? AND account_id=? AND month='2026-10-01'")) {
            statement.setString(1, tenant); statement.setObject(2, owner);
            try (var result = statement.executeQuery()) { result.next(); assertThat(result.getLong(1)).isEqualTo(1); }
        }
    }

    private void assertMonthBoundary(String month, String zone, String first, String next) throws Exception {
        Instant start = Instant.parse(first), end = Instant.parse(next);
        question(session, start.minusNanos(1000).toString());
        UUID included = question(session, start.toString());
        question(session, end.minusNanos(1000).toString());
        UUID excluded = question(session, end.toString());
        configure(owner, month, "10", "USD");
        var report = get(owner, query(month, zone, session, included));
        assertThat(report.path("month").asText()).isEqualTo(month);
        assertThat(report.path("zone").asText()).isEqualTo(zone);
        assertThat(report.path("subscription").path("questionCount").asLong()).isEqualTo(2);
        assertThat(report.path("subscription").path("sessionCount").asLong()).isEqualTo(1);
        assertThat(report.path("question").path("inMonth").asBoolean()).isTrue();
        assertThat(report.path("question").path("subscriptionAllocation").decimalValue()).isEqualByComparingTo("5");
        var nextMonthQuestion = get(owner, query(month, zone, session, excluded)).path("question");
        assertThat(nextMonthQuestion.path("inMonth").asBoolean()).isFalse();
        assertThat(nextMonthQuestion.path("subscriptionAllocation").isNull()).isTrue();
    }

    private UUID session(UUID account, String source) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = db.open(); var statement = connection.prepareStatement(
            "INSERT INTO llm_gateway_analytics.monitoring_sessions(tenant_id,id,account_id,source,external_session_id,first_seen_at,last_seen_at) VALUES(?,?,?,?,?,now(),now())")) {
            statement.setString(1, tenant); statement.setObject(2, id); statement.setObject(3, account);
            statement.setString(4, source); statement.setString(5, id.toString()); statement.executeUpdate();
        }
        return id;
    }

    private UUID question(UUID sessionId, String time) throws Exception {
        UUID id = UUID.randomUUID();
        try (var connection = db.open(); var statement = connection.prepareStatement(
            "INSERT INTO llm_gateway_analytics.monitoring_questions(tenant_id,id,session_id,question_identity,started_at,source) SELECT tenant_id,?,id,?,?,source FROM llm_gateway_analytics.monitoring_sessions WHERE tenant_id=? AND id=?")) {
            statement.setObject(1, id); statement.setString(2, id.toString()); statement.setTimestamp(3, Timestamp.from(Instant.parse(time)));
            statement.setString(4, tenant); statement.setObject(5, sessionId); assertThat(statement.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private void purge(String table, UUID id) throws Exception {
        try (var connection = db.open(); var statement = connection.prepareStatement(
            "UPDATE llm_gateway_analytics." + table + " SET purged_at=now() WHERE tenant_id=? AND id=?")) {
            statement.setString(1, tenant); statement.setObject(2, id); assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void configure(UUID account, String month, String amount, String currency) {
        put(account, "admin", body(month, amount, currency)).expectStatus().isOk();
    }

    private void assertSetting(String amount, String currency) throws Exception {
        var setting = get(owner, query("2026-10", "UTC", null, null)).path("subscription");
        assertThat(setting.path("amount").decimalValue()).isEqualByComparingTo(amount);
        assertThat(setting.path("currency").asText()).isEqualTo(currency);
    }

    private List<AuditService.Event> configurationAudits() throws Exception {
        return audit.list(tenant, 100, 0).items().stream().filter(e -> e.action().equals("monitoring.cost.configure")).toList();
    }

    private JsonNode get(UUID account, String query) throws Exception {
        return JSON.readTree(client.get().uri(path(account) + query).header("Test-Tenant", tenant).header("Test-Role", "viewer")
            .exchange().expectStatus().isOk().expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody(byte[].class).returnResult().getResponseBody());
    }

    private WebTestClient.ResponseSpec put(UUID account, String role, String requestBody) {
        return client.put().uri(path(account) + "/subscription").header("Test-Tenant", tenant).header("Test-Role", role)
            .header("Content-Type", "application/json").bodyValue(requestBody).exchange();
    }

    private static String path(UUID account) { return "/admin/api/monitoring/accounts/" + account + "/costs"; }
    private static String body(String month, String amount, String currency) {
        return "{\"month\":\"" + month + "\",\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}";
    }
    private static String query(String month, String zone, UUID sessionId, UUID questionId) {
        return "?month=" + month + "&zone=" + zone + (sessionId == null ? "" : "&sessionId=" + sessionId)
            + (questionId == null ? "" : "&questionId=" + questionId);
    }
}
