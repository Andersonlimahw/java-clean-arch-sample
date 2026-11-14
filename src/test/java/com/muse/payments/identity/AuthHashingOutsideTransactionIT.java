package com.muse.payments.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.muse.payments.TestcontainersBase;
import com.muse.payments.identity.application.LoginRequest;
import com.muse.payments.identity.application.RegisterRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BCrypt takes hundreds of milliseconds. Hashing inside a transaction would hold one pooled
 * database connection for that whole time on every register and login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthHashingOutsideTransactionIT.ProbeConfig.class)
class AuthHashingOutsideTransactionIT extends TestcontainersBase {

    private static final List<String> HASHING_INSIDE_TRANSACTION = new CopyOnWriteArrayList<>();
    private static final List<String> HASHING_CALLS = new CopyOnWriteArrayList<>();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Register and login hash the password with no transaction open")
    void hashingRunsOutsideATransaction() throws Exception {
        HASHING_CALLS.clear();
        HASHING_INSIDE_TRANSACTION.clear();
        String email = "hashing." + UUID.randomUUID() + "@example.com";
        String password = "SecurePassword123!";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(email, password, "Hashing Probe"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk());

        assertThat(HASHING_CALLS).containsExactly("encode", "matches");
        assertThat(HASHING_INSIDE_TRANSACTION).isEmpty();
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        static BeanPostProcessor passwordEncoderTransactionProbe() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof PasswordEncoder encoder) {
                        return new ProbingEncoder(encoder);
                    }
                    return bean;
                }
            };
        }
    }

    private record ProbingEncoder(PasswordEncoder delegate) implements PasswordEncoder {

        @Override
        public String encode(CharSequence rawPassword) {
            note("encode");
            return delegate.encode(rawPassword);
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            note("matches");
            return delegate.matches(rawPassword, encodedPassword);
        }

        private static void note(String call) {
            HASHING_CALLS.add(call);
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                HASHING_INSIDE_TRANSACTION.add(call);
            }
        }
    }
}
