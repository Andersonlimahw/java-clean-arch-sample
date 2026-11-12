package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.payments.application.CreatePaymentRequest;
import com.muse.payments.payments.domain.Payment;
import com.muse.payments.payments.domain.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private PaymentRepository paymentRepository;

    private final AtomicInteger arrived = new AtomicInteger();
    private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>();

    @BeforeEach
    void clearSharedAuthRateLimit() {
        Set<String> keys = redisTemplate.keys("ratelimit:auth:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("payments.version is a non-null bigint defaulting to 0")
    void paymentsVersionColumnExists() {
        Map<String, Object> column = jdbcTemplate.queryForMap(
                "SELECT data_type, is_nullable, column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = 'payments' AND column_name = 'version'"
        );
        assertThat(column.get("data_type")).isEqualTo("bigint");
        assertThat(column.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(column.get("column_default"))).contains("0");
    }

    @Test
    @DisplayName("Two confirms of the same version: one succeeds and the other is 409 problem+json")
    void concurrentConfirmFromTheSameVersionConflicts() throws Exception {
        String token = token();
        UUID paymentId = createPayment(token);
        arm(paymentId);

        List<MvcResult> results = race(
                () -> postUpdate(token, paymentId, "/confirm"),
                () -> postUpdate(token, paymentId, "/confirm")
        );
        MvcResult winner = winner(results);
        MvcResult loser = loser(results);

        assertThat(arrived.get()).isEqualTo(2);
        JsonNode winnerBody = objectMapper.readTree(winner.getResponse().getContentAsString());
        assertThat(winnerBody.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(winnerBody.get("version").asLong()).isEqualTo(1L);
        assertConcurrentModificationProblem(loser);
        assertPersistedOnce(paymentId, "COMPLETED", "PaymentCompleted");
    }

    @Test
    @DisplayName("Confirm and cancel of the same version: one wins and the other is 409")
    void concurrentConfirmAndCancelFromTheSameVersionConflicts() throws Exception {
        String token = token();
        UUID paymentId = createPayment(token);
        arm(paymentId);

        List<MvcResult> results = race(
                () -> postUpdate(token, paymentId, "/confirm"),
                () -> postUpdate(token, paymentId, "/cancel")
        );
        MvcResult winner = winner(results);
        MvcResult loser = loser(results);

        assertThat(arrived.get()).isEqualTo(2);
        JsonNode winnerBody = objectMapper.readTree(winner.getResponse().getContentAsString());
        String status = winnerBody.get("status").asText();
        assertThat(status).isIn("COMPLETED", "CANCELLED");
        assertThat(winnerBody.get("version").asLong()).isEqualTo(1L);
        assertConcurrentModificationProblem(loser);
        String eventType = "COMPLETED".equals(status) ? "PaymentCompleted" : "PaymentCancelled";
        assertPersistedOnce(paymentId, status, eventType);
    }

    private String token() throws Exception {
        return PaymentHttp.registerAndLogin(
                mockMvc,
                objectMapper,
                "race-" + UUID.randomUUID() + "@example.com",
                "SecurePassword123!"
        );
    }

    private UUID createPayment(String token) throws Exception {
        return PaymentHttp.createdPaymentUuid(
                mockMvc,
                objectMapper,
                token,
                new CreatePaymentRequest(new BigDecimal("15.00"), "BRL", "Race")
        );
    }

    private void arm(UUID paymentId) throws Exception {
        arrived.set(0);
        barrier.set(new CyclicBarrier(2));
        doAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            if (payment.getVersion() != null && paymentId.equals(payment.getId())) {
                arrived.incrementAndGet();
                try {
                    barrier.get().await(HANG_GUARD_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for the concurrent payment update", exception);
                } catch (BrokenBarrierException | TimeoutException exception) {
                    throw new IllegalStateException("Concurrent payment update did not reach the same version", exception);
                }
            }
            return invocation.callRealMethod();
        }).when(paymentRepository).save(any());
    }

    private List<MvcResult> race(Callable<MvcResult> left, Callable<MvcResult> right) throws Exception {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(left);
            var second = pool.submit(right);
            return List.of(
                    first.get(HANG_GUARD_SECONDS * 2, TimeUnit.SECONDS),
                    second.get(HANG_GUARD_SECONDS * 2, TimeUnit.SECONDS)
            );
        }
    }

    private MvcResult postUpdate(String token, UUID paymentId, String action) throws Exception {
        return mockMvc.perform(post("/api/v1/payments/" + paymentId + action)
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private static MvcResult winner(List<MvcResult> results) {
        List<MvcResult> winners = results.stream()
                .filter(result -> result.getResponse().getStatus() >= 200 && result.getResponse().getStatus() < 300)
                .toList();
        assertThat(winners).hasSize(1);
        return winners.get(0);
    }

    private static MvcResult loser(List<MvcResult> results) {
        List<MvcResult> losers = results.stream()
                .filter(result -> result.getResponse().getStatus() == 409)
                .toList();
        assertThat(losers).hasSize(1);
        return losers.get(0);
    }

    private void assertConcurrentModificationProblem(MvcResult loser) throws Exception {
        MediaType contentType = MediaType.parseMediaType(loser.getResponse().getContentType());
        assertThat(contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).isTrue();
        JsonNode problem = objectMapper.readTree(loser.getResponse().getContentAsString());
        assertThat(problem.get("status").asInt()).isEqualTo(409);
        assertThat(problem.get("title").asText()).isEqualTo("Concurrent Modification");
        assertThat(problem.get("detail").asText()).isEqualTo("The resource was modified concurrently. Please retry.");
        assertThat(problem.get("type").asText()).isEqualTo("urn:problem-type:concurrent-modification");
        assertThat(problem.get("instance").asText()).isEqualTo(loser.getRequest().getRequestURI());
        assertThat(problem.has("trace")).isFalse();
        assertThat(problem.has("exception")).isFalse();
    }

    private void assertPersistedOnce(UUID paymentId, String status, String eventType) {
        String storedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE id = ?",
                String.class,
                paymentId
        );
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM payments WHERE id = ?",
                Long.class,
                paymentId
        );
        Integer winningEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type = ?",
                Integer.class,
                paymentId,
                eventType
        );
        Integer otherTransitionEvents = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ? AND event_type <> ? AND event_type <> 'PaymentCreated'",
                Integer.class,
                paymentId,
                eventType
        );
        assertThat(storedStatus).isEqualTo(status);
        assertThat(version).isEqualTo(1L);
        assertThat(winningEvents).isEqualTo(1);
        assertThat(otherTransitionEvents).isZero();
    }
}
