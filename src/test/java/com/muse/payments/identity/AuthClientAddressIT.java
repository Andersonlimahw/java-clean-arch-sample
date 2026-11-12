package com.muse.payments.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class AuthClientAddressIT extends TestcontainersBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearAuthRateLimit() {
        Set<String> keys = redisTemplate.keys("ratelimit:auth:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    @DisplayName("A direct client cannot choose its rate-limit key with X-Forwarded-For")
    void untrustedForwardedForDoesNotReplaceRemoteAddress() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.44");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("direct-" + UUID.randomUUID() + "@example.com")))
                .andExpect(status().isCreated());

        assertThat(redisTemplate.hasKey("ratelimit:auth:203.0.113.44")).isTrue();
        assertThat(redisTemplate.hasKey("ratelimit:auth:198.51.100.8")).isFalse();
    }

    @Test
    @DisplayName("A trusted proxy still supplies the client address through X-Forwarded-For")
    void trustedProxyUsesForwardedFor() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Forwarded-For", "198.51.100.23");
        var response = restTemplate.exchange(
                "/api/v1/auth/register",
                POST,
                new HttpEntity<>(body("proxy-" + UUID.randomUUID() + "@example.com"), headers),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(redisTemplate.hasKey("ratelimit:auth:198.51.100.23")).isTrue();
    }

    private String body(String email) throws Exception {
        return objectMapper.writeValueAsString(new RegisterRequest(email, "SecurePassword123!", "Forwarded User"));
    }
}
