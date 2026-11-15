package com.muse.payments.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RefreshTokenRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.identity.domain.RefreshTokenFamily;
import com.muse.payments.identity.domain.RefreshTokenFamilyRepository;
import com.muse.payments.shared.application.Snapshot;
import com.muse.payments.shared.application.SnapshotStore;
import com.muse.payments.shared.application.StreamRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "app.event-store.snapshot-every=3"
)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RefreshTokenFamilyIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RefreshTokenFamilyRepository familyRepository;

    @Autowired
    private SnapshotStore snapshotStore;

    @Test
    @DisplayName("Concurrent refresh token rotation yields one 200 and one 401 with FamilyRevoked appended")
    void concurrentRotationRevokesFamilyAndReturns401() throws Exception {
        String email = "rt_race_" + UUID.randomUUID() + "@example.com";
        String password = "Password123!";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, "RT Race"))))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        String refreshToken = objectMapper.readValue(
                loginResult.getResponse().getContentAsString(), AuthTokensResponse.class).refreshToken();

        CountDownLatch ready = new CountDownLatch(3);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> refreshCall = () -> {
            ready.countDown();
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new RefreshTokenRequest(refreshToken))))
                    .andReturn()
                    .getResponse()
                    .getStatus();
        };

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> first = pool.submit(refreshCall);
            Future<Integer> second = pool.submit(refreshCall);
            Future<Integer> third = pool.submit(refreshCall);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS), third.get(10, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 401, 401);
        }

        UUID userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        UUID familyId = jdbcTemplate.queryForObject(
                "SELECT family_id FROM refresh_tokens WHERE user_id = ? LIMIT 1", UUID.class, userId);

        Integer revokedEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_store WHERE stream_type = 'RefreshTokenFamily' AND stream_id = ? AND event_type = 'identity.family-revoked'",
                Integer.class,
                familyId
        );
        assertThat(revokedEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("Snapshots are taken at the configured threshold and loading with or without snapshot yields identical state")
    void snapshotEquivalence() {
        UUID userId = UUID.randomUUID();
        RefreshTokenFamily family = RefreshTokenFamily.start(userId, "hash_0", Instant.now().plusSeconds(3600));
        familyRepository.save(family);

        for (int i = 1; i <= 4; i++) {
            RefreshTokenFamily loaded = familyRepository.findById(family.getId()).orElseThrow();
            loaded.rotate("hash_" + (i - 1), "hash_" + i, Instant.now().plusSeconds(3600), Instant.now());
            familyRepository.save(loaded);
        }

        StreamRef stream = new StreamRef("RefreshTokenFamily", family.getId());
        Snapshot snapshot = snapshotStore.load(stream, 1).orElseThrow();
        assertThat(snapshot.streamVersion()).isGreaterThanOrEqualTo(3L);

        RefreshTokenFamily withSnapshot = familyRepository.findById(family.getId()).orElseThrow();

        jdbcTemplate.update("DELETE FROM snapshots WHERE stream_type = 'RefreshTokenFamily' AND stream_id = ?", family.getId());
        RefreshTokenFamily withoutSnapshot = familyRepository.findById(family.getId()).orElseThrow();

        assertThat(withSnapshot.getId()).isEqualTo(withoutSnapshot.getId());
        assertThat(withSnapshot.getUserId()).isEqualTo(withoutSnapshot.getUserId());
        assertThat(withSnapshot.isFamilyRevoked()).isEqualTo(withoutSnapshot.isFamilyRevoked());
        assertThat(withSnapshot.getTokens()).isEqualTo(withoutSnapshot.getTokens());
        assertThat(withSnapshot.version()).isEqualTo(withoutSnapshot.version());
    }
}
