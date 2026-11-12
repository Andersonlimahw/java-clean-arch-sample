package com.muse.payments.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.shared.security.JwtTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PaymentsPaginationIT extends TestcontainersBase {

    private static final UUID TIED_LOW = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TIED_HIGH = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Test
    @DisplayName("Offset pages follow created_at DESC, id DESC with no duplicates or gaps")
    void pagesAreStableAndDisjoint() throws Exception {
        UUID userId = UUID.randomUUID();
        String token = jwtTokenIssuer.issueAccessToken(userId, List.of("USER"));
        UUID otherUser = UUID.randomUUID();

        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        Instant t1 = Instant.parse("2026-01-02T00:00:00Z");
        Instant tied = Instant.parse("2026-01-03T00:00:00Z");
        Instant t3 = Instant.parse("2026-01-04T00:00:00Z");
        insertPayment(UUID.randomUUID(), userId, t0);
        insertPayment(UUID.randomUUID(), userId, t1);
        insertPayment(TIED_LOW, userId, tied);
        insertPayment(TIED_HIGH, userId, tied);
        insertPayment(UUID.randomUUID(), userId, t3);
        insertPayment(UUID.randomUUID(), otherUser, Instant.parse("2026-06-01T00:00:00Z"));

        List<UUID> expectedDesc = jdbcTemplate.query(
                "SELECT id FROM payments WHERE user_id = ? ORDER BY created_at DESC, id DESC",
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                userId
        );
        assertThat(expectedDesc).hasSize(5);
        assertThat(expectedDesc.indexOf(TIED_HIGH)).isLessThan(expectedDesc.indexOf(TIED_LOW));
        assertThat(expectedDesc.indexOf(TIED_LOW) - expectedDesc.indexOf(TIED_HIGH)).isEqualTo(1);

        List<UUID> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            JsonNode body = list(token, page, 2, "createdAt,desc");
            assertThat(body.get("page").asInt()).isEqualTo(page);
            assertThat(body.get("size").asInt()).isEqualTo(2);
            assertThat(body.get("totalElements").asLong()).isEqualTo(5);
            assertThat(body.get("totalPages").asInt()).isEqualTo(3);
            assertThat(body.get("sort").asText()).isEqualTo("createdAt,desc");
            JsonNode items = body.get("items");
            assertThat(items.size()).isEqualTo(page < 2 ? 2 : 1);
            for (JsonNode item : items) {
                assertThat(item.get("userId").asText()).isEqualTo(userId.toString());
                seen.add(UUID.fromString(item.get("id").asText()));
            }
        }
        assertThat(seen).containsExactlyElementsOf(expectedDesc);
        assertThat(seen).doesNotHaveDuplicates();

        List<UUID> expectedAsc = jdbcTemplate.query(
                "SELECT id FROM payments WHERE user_id = ? ORDER BY created_at ASC, id ASC",
                (rs, rowNum) -> rs.getObject(1, UUID.class),
                userId
        );
        JsonNode ascending = list(token, 0, 20, "createdAt,asc");
        List<UUID> ascIds = new ArrayList<>();
        ascending.get("items").forEach(item -> ascIds.add(UUID.fromString(item.get("id").asText())));
        assertThat(ascIds).containsExactlyElementsOf(expectedAsc);
        assertThat(ascending.get("sort").asText()).isEqualTo("createdAt,asc");

        JsonNode defaults = list(token, null, null, null);
        assertThat(defaults.get("page").asInt()).isZero();
        assertThat(defaults.get("size").asInt()).isEqualTo(20);
        assertThat(defaults.get("sort").asText()).isEqualTo("createdAt,desc");
        assertThat(defaults.get("items").size()).isEqualTo(5);

        JsonNode pastEnd = list(token, 50, 2, "createdAt,desc");
        assertThat(pastEnd.get("items").size()).isZero();
        assertThat(pastEnd.get("totalElements").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("Invalid page, size, and sort are HTTP 400 and are not clamped")
    void rejectsInvalidPaginationParameters() throws Exception {
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID(), List.of("USER"));

        expectInvalid(token, "-1", "20", null, "page must be greater than or equal to 0");
        expectInvalid(token, "0", "0", null, "size must be between 1 and 100");
        expectInvalid(token, "0", "-1", null, "size must be between 1 and 100");
        expectInvalid(token, "0", "101", null, "size must be between 1 and 100");
        expectInvalid(token, "0", "20", "amount,desc", "sort must be one of: createdAt,desc, createdAt,asc");
        expectInvalid(token, "0", "100", "createdAt,desc", null);

        mockMvc.perform(get("/api/v1/payments")
                        .param("page", "abc")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'page'"));

        mockMvc.perform(get("/api/v1/payments")
                        .param("size", "20.5")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.detail").value("Invalid value for parameter 'size'"));
    }

    private void expectInvalid(String token, String page, String size, String sort, String detail) throws Exception {
        var request = get("/api/v1/payments")
                .param("page", page)
                .param("size", size)
                .header("Authorization", "Bearer " + token);
        if (sort != null) {
            request = request.param("sort", sort);
        }
        if (detail == null) {
            mockMvc.perform(request).andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
            return;
        }
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.detail").value(detail));
    }

    private JsonNode list(String token, Integer page, Integer size, String sort) throws Exception {
        var request = get("/api/v1/payments").header("Authorization", "Bearer " + token);
        if (page != null) {
            request = request.param("page", page.toString());
        }
        if (size != null) {
            request = request.param("size", size.toString());
        }
        if (sort != null) {
            request = request.param("sort", sort);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void insertPayment(UUID id, UUID userId, Instant createdAt) {
        Timestamp timestamp = Timestamp.from(createdAt);
        jdbcTemplate.update(
                "INSERT INTO payments (id, user_id, amount, currency, description, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, 10.00, 'BRL', 'page', 'PENDING', ?, ?, 0)",
                id,
                userId,
                timestamp,
                timestamp
        );
    }
}
