package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.AuthTokensResponse;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RegisterRequest;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import com.muse.payments.payments.infrastructure.PaymentJpaAdapter;
import com.muse.payments.payments.infrastructure.PaymentJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PaymentOptimisticLockRaceIT extends TestcontainersBase {

    private static final long HANG_GUARD_SECONDS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UpdateGate updateGate;

    @Test
    @DisplayName("Two confirms of the same version: one succeeds and the other is 409 problem+json")
    void concurrentConfirmFromTheSameVersionConflicts() throws Exception {
        String email = "race-" + UUID.randomUUID() + "@example.com";
        String token = authenticateUser(email, "SecurePassword123!");
        UUID paymentId = createPayment(token);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MvcResult> left = pool.submit(() -> confirm(token, paymentId));
            Future<MvcResult> right = pool.submit(() -> confirm(token, paymentId));
            MvcResult first = left.get(HANG_GUARD_SECONDS * 2, TimeUnit.SECONDS);
            MvcResult second = right.get(HANG_GUARD_SECONDS * 2, TimeUnit.SECONDS);

            List<MvcResult> results = List.of(first, second);
            List<MvcResult> winners = results.stream()
                    .filter(result -> result.getResponse().getStatus() >= 200 && result.getResponse().getStatus() < 300)
                    .toList();
            List<MvcResult> losers = results.stream()
                    .filter(result -> result.getResponse().getStatus() == 409)
                    .toList();

            assertThat(updateGate.arrived.get())
                    .as("both updates must reach the gate while still on the original version")
                    .isEqualTo(2);
            assertThat(winners).hasSize(1);
            assertThat(losers).hasSize(1);

            JsonNode winnerBody = objectMapper.readTree(winners.get(0).getResponse().getContentAsString());
            assertThat(winnerBody.get("status").asText()).isEqualTo("COMPLETED");
            assertThat(winnerBody.get("version").asLong()).isEqualTo(1L);

            MvcResult loser = losers.get(0);
            MediaType loserType = MediaType.parseMediaType(loser.getResponse().getContentType());
            assertThat(loserType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
            JsonNode problem = objectMapper.readTree(loser.getResponse().getContentAsString());
            assertThat(problem.get("status").asInt()).isEqualTo(409);
            assertThat(problem.get("title").asText()).isEqualTo("Concurrent Modification");
            assertThat(problem.get("detail").asText())
                    .isEqualTo("The resource was modified concurrently. Please retry.");
            assertThat(problem.get("type").asText()).isEqualTo("urn:problem-type:409");
            assertThat(problem.get("instance").asText()).isEqualTo("/api/v1/payments/" + paymentId + "/confirm");
            assertThat(problem.has("stackTrace")).isFalse();
            assertThat(loser.getResponse().getContentAsString()).doesNotContain("StaleObjectStateException");
        }

        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?",
                String.class,
                paymentId
        );
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM payments WHERE id = ?",
                Long.class,
                paymentId
        );
        Integer completedEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = 'PaymentCompleted'",
                Integer.class,
                paymentId
        );
        assertThat(status).isEqualTo("COMPLETED");
        assertThat(version).isEqualTo(1L);
        assertThat(completedEvents).isEqualTo(1);
    }

    private String authenticateUser(String email, String password) throws Exception {
        // Auth limits are keyed by client address and the suite shares one Redis.
        // A fresh forwarded address keeps this test off the bucket other tests already filled.
        String clientIp = "203.0.113." + Math.floorMod(email.hashCode(), 200);
        mockMvc.perform(post("/api/v1/auth/register")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, "Race User"))))
                .andExpect(status().isCreated());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", clientIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(login.getResponse().getContentAsString(), AuthTokensResponse.class).accessToken();
    }

    private UUID createPayment(String token) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/payments")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(new BigDecimal("15.00"), "BRL", "Race"))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asText());
    }

    private MvcResult confirm(String token, UUID paymentId) throws Exception {
        return mockMvc.perform(post("/api/v1/payments/" + paymentId + "/confirm")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    @TestConfiguration
    static class SameVersionGate {

        @Bean
        UpdateGate updateGate() {
            return new UpdateGate();
        }

        @Bean
        @Primary
        PaymentRepository paymentRepository(PaymentJpaRepository jpaRepository, UpdateGate updateGate) {
            return new GatedPaymentRepository(new PaymentJpaAdapter(jpaRepository), updateGate);
        }
    }

    static final class UpdateGate {
        final CyclicBarrier barrier = new CyclicBarrier(2);
        final AtomicInteger arrived = new AtomicInteger();
    }

    /**
     * Both versioned saves wait until the peer has also loaded that version.
     * The timeout only stops a hung test; the winner is released by the peer, not by the clock.
     */
    static final class GatedPaymentRepository implements PaymentRepository {

        private final PaymentRepository delegate;
        private final UpdateGate gate;

        GatedPaymentRepository(PaymentRepository delegate, UpdateGate gate) {
            this.delegate = delegate;
            this.gate = gate;
        }

        @Override
        public Payment save(Payment payment) {
            if (payment.getVersion() != null) {
                gate.arrived.incrementAndGet();
                try {
                    gate.barrier.await(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the concurrent payment update", exception);
                } catch (BrokenBarrierException | TimeoutException exception) {
                    throw new IllegalStateException("Concurrent payment update did not reach the same version", exception);
                }
            }
            return delegate.save(payment);
        }

        @Override
        public Optional<Payment> findById(UUID id) {
            return delegate.findById(id);
        }

        @Override
        public List<Payment> findByUserId(UUID userId, int page, int size) {
            return delegate.findByUserId(userId, page, size);
        }

        @Override
        public long countByUserId(UUID userId) {
            return delegate.countByUserId(userId);
        }
    }
}
