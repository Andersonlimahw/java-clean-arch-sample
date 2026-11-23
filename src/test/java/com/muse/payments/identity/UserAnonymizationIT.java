package com.muse.payments.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RefreshTokenRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.identity.domain.UserRegisteredEvent;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.shared.application.EventStore;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 015: right to erasure through crypto-shredding, against real PostgreSQL and Redis. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserAnonymizationIT extends TestcontainersBase {

    private static final String PASSWORD = "SecurePass123!";
    private static final String REDACTED = "[REDACTED_LGPD]";
    private static final String EVENT_SNAPSHOT = """
            SELECT global_position, event_id, md5(payload::text) AS payload_hash,
                   md5(metadata::text) AS metadata_hash, md5(event_type) AS type_hash, stream_version
            FROM event_store ORDER BY global_position
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private EventStore eventStore;
    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    private record Account(UUID id, String email, String accessToken, String refreshToken) {
    }

    private Account registerAndLogin(String email, String displayName) throws Exception {
        MvcResult registered = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, PASSWORD, displayName))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = UUID.fromString(objectMapper.readTree(registered.getResponse().getContentAsString()).get("id").asText());
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tokens = objectMapper.readTree(login.getResponse().getContentAsString());
        return new Account(id, email, tokens.get("accessToken").asText(), tokens.get("refreshToken").asText());
    }

    private UUID createPayment(Account account, String description) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + account.accessToken())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(new BigDecimal("42.00"), "BRL", description))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private int eventCountFor(UUID streamId, String eventType) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM event_store WHERE stream_id = ? AND event_type = ?",
                Integer.class, streamId, eventType);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("Erasure shreds personal data, keeps event_store rows byte-identical plus exactly one new row")
    void erasureCryptoShredsWithoutTouchingEventStore() throws Exception {
        String email = "erase.me@example.com";
        String displayName = "Alice Erasable";
        String secretDescription = "Rent for the flat of Alice Erasable";
        Account alice = registerAndLogin(email, displayName);
        UUID paymentId = createPayment(alice, secretDescription);
        // Populate the Redis cache for the payment so eviction can be observed.
        mockMvc.perform(get("/api/v1/payments/" + paymentId)
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isOk());
        String cacheKey = "payment:cache:" + paymentId;
        assertThat(redis.hasKey(cacheKey)).isTrue();
        jdbc.update("INSERT INTO notifications (id, user_id, payment_id, type, title, message) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), alice.id(), paymentId, "PAYMENT_CREATED", "Payment", "Payment " + secretDescription);

        // Ciphertext at rest: the plaintext never reached the immutable log.
        List<String> streamPayloads = jdbc.queryForList(
                "SELECT payload::text FROM event_store WHERE stream_id = ? OR payload ->> 'userId' = ?",
                String.class, alice.id(), alice.id().toString());
        assertThat(streamPayloads).isNotEmpty().allSatisfy(payload ->
                assertThat(payload).doesNotContain(email).doesNotContain(displayName).doesNotContain(secretDescription));
        assertThat(String.join("", streamPayloads)).contains("lgpd:v1:" + alice.id());
        assertThat(jdbc.queryForObject("SELECT destroyed_at IS NULL FROM user_crypto_keys WHERE user_id = ?",
                Boolean.class, alice.id())).isTrue();

        // Before erasure the history rehydrates in clear.
        assertThat(registeredEmail(alice.id())).isEqualTo(email);

        List<Map<String, Object>> before = jdbc.queryForList(EVENT_SNAPSHOT);

        mockMvc.perform(delete("/api/v1/users/" + alice.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());

        // event_store: pre-existing rows byte-identical, exactly one new row, no trigger fired.
        List<Map<String, Object>> after = jdbc.queryForList(EVENT_SNAPSHOT);
        assertThat(after).hasSize(before.size() + 1);
        assertThat(after.subList(0, before.size())).isEqualTo(before);
        Map<String, Object> added = jdbc.queryForMap(
                "SELECT stream_id, event_type, payload::text AS payload FROM event_store ORDER BY global_position DESC LIMIT 1");
        assertThat(added.get("stream_id")).isEqualTo(alice.id());
        assertThat(added.get("event_type")).isEqualTo("identity.user-anonymization-requested");
        assertThat((String) added.get("payload")).doesNotContain(email).doesNotContain(displayName);

        // Key destroyed; rehydration is redacted instead of failing.
        Map<String, Object> key = jdbc.queryForMap(
                "SELECT octet_length(encrypted_data_key) AS len, destroyed_at FROM user_crypto_keys WHERE user_id = ?",
                alice.id());
        assertThat(key.get("len")).isEqualTo(0);
        assertThat(key.get("destroyed_at")).isNotNull();
        assertThat(registeredEmail(alice.id())).isEqualTo(REDACTED);

        // Read models, credentials, outbox, and cache.
        Map<String, Object> user = jdbc.queryForMap("SELECT email, display_name, enabled FROM users WHERE id = ?", alice.id());
        assertThat(user.get("email")).isEqualTo("redacted-" + alice.id() + "@deleted.lgpd");
        assertThat(user.get("display_name")).isEqualTo(REDACTED);
        assertThat(user.get("enabled")).isEqualTo(false);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_credentials WHERE user_id = ?",
                Integer.class, alice.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT description FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo(REDACTED);
        assertThat(jdbc.queryForObject("SELECT message FROM notifications WHERE user_id = ?", String.class, alice.id()))
                .isEqualTo(REDACTED);
        List<String> outbox = jdbc.queryForList(
                "SELECT payload::text FROM outbox_events WHERE payload ->> 'userId' = ?", String.class, alice.id().toString());
        assertThat(String.join("", outbox)).doesNotContain(email).doesNotContain(displayName).doesNotContain(secretDescription);
        assertThat(redis.hasKey(cacheKey)).isFalse();

        // Old credentials and refresh token no longer work.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest(alice.refreshToken()))))
                .andExpect(status().isUnauthorized());

        // Repeat is idempotent: 204 and no second event.
        int events = after.size();
        mockMvc.perform(delete("/api/v1/users/" + alice.id())
                        .header("Authorization", "Bearer " + alice.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForList(EVENT_SNAPSHOT)).hasSize(events);
        assertThat(eventCountFor(alice.id(), "identity.user-anonymization-requested")).isEqualTo(1);
    }

    @Test
    @DisplayName("Another non-admin user gets 403 problem+json, unknown id gets 404, an admin can erase")
    void authorizationRules() throws Exception {
        Account owner = registerAndLogin("owner.keep@example.com", "Owner Keep");
        Account intruder = registerAndLogin("intruder@example.com", "Intruder");

        mockMvc.perform(delete("/api/v1/users/" + owner.id())
                        .header("Authorization", "Bearer " + intruder.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith("application/problem+json"));
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, owner.id())).isTrue();
        assertThat(eventCountFor(owner.id(), "identity.user-anonymization-requested")).isZero();

        UUID adminId = UUID.randomUUID();
        String adminToken = jwtTokenIssuer.issueAccessToken(adminId, Set.of("ADMIN"));
        mockMvc.perform(delete("/api/v1/users/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/users/" + owner.id())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, owner.id())).isFalse();
        assertThat(eventCountFor(owner.id(), "identity.user-anonymization-requested")).isEqualTo(1);

        mockMvc.perform(delete("/api/v1/users/" + owner.id())).andExpect(status().isUnauthorized());
    }

    private String registeredEmail(UUID userId) {
        return eventStore.loadAfter(new StreamRef("User", userId), 0).stream()
                .map(StoredEvent::event)
                .filter(UserRegisteredEvent.class::isInstance)
                .map(UserRegisteredEvent.class::cast)
                .map(UserRegisteredEvent::email)
                .findFirst()
                .orElseThrow();
    }
}
