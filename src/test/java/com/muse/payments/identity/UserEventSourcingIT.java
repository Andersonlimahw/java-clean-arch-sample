package com.muse.payments.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserEventSourcingIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.muse.payments.identity.application.UserCredentialsRepository credentials;

    @Test
    void rejectsEmptyCredentialHashOutsideTheUserAggregate() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> credentials.save(java.util.UUID.randomUUID(), " "))
                .isInstanceOf(com.muse.payments.shared.domain.ValidationException.class)
                .hasMessageContaining("Password hash cannot be empty");
    }

    @Test
    @DisplayName("User registration never stores password hashes in event_store and stores credentials in user_credentials")
    void registerStoresNoPasswordHashInEventStore() throws Exception {
        String rawPassword = "SuperSecretPassword123!";
        RegisterRequest request = new RegisterRequest("es_user_no_hash@example.com", rawPassword, "ES User");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());

        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload::text FROM event_store WHERE stream_type = 'User'",
                String.class
        );
        assertThat(payloads).isNotEmpty();
        assertThat(payloads).allSatisfy(payload -> {
            assertThat(payload).doesNotContain(rawPassword);
            assertThat(payload).doesNotContain("passwordHash");
            assertThat(payload).doesNotContain("$2a$");
            assertThat(payload).doesNotContain("$2b$");
        });

        Integer credCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_credentials uc JOIN users u ON u.id = uc.user_id WHERE u.email = ?",
                Integer.class,
                "es_user_no_hash@example.com"
        );
        assertThat(credCount).isEqualTo(1);
    }
}
