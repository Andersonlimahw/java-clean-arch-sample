package com.muse.payments.shared.web;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OptimisticLockProbe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("optimisticConflicts")
    void mapsOptimisticConflictsTo409(Exception failure) throws Exception {
        mockMvc.perform(get("/test-optimistic-lock")
                        .requestAttr("failure", failure)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Concurrent Modification"))
                .andExpect(jsonPath("$.detail").value("The resource was modified concurrently. Please retry."))
                .andExpect(jsonPath("$.type").value("urn:problem-type:concurrent-modification"))
                .andExpect(jsonPath("$.instance").value("/test-optimistic-lock"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transientLocks")
    void mapsTransientLocksTo503(Exception failure) throws Exception {
        mockMvc.perform(get("/test-optimistic-lock")
                        .requestAttr("failure", failure)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.title").value("Transient Lock Failure"))
                .andExpect(jsonPath("$.detail").value("The database could not acquire a lock. Retry the same request."))
                .andExpect(jsonPath("$.type").value("urn:problem-type:transient-lock"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    @Test
    void unexpectedExceptionIs500() throws Exception {
        mockMvc.perform(get("/test-optimistic-lock")
                        .requestAttr("failure", new RuntimeException("boom"))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.type").value("urn:problem-type:500"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist());
    }

    static Stream<Named<Exception>> optimisticConflicts() {
        return Stream.of(
                Named.named("OptimisticLockingFailureException", new OptimisticLockingFailureException("stale row")),
                Named.named("ObjectOptimisticLockingFailureException", new ObjectOptimisticLockingFailureException("Payment", "id")),
                Named.named("jakarta.persistence.OptimisticLockException", new OptimisticLockException("stale row"))
        );
    }

    static Stream<Named<Exception>> transientLocks() {
        return Stream.of(
                Named.named("CannotAcquireLockException", new CannotAcquireLockException("serialization failure")),
                Named.named("PessimisticLockingFailureException", new PessimisticLockingFailureException("deadlock"))
        );
    }

    @RestController
    @RequestMapping("/test-optimistic-lock")
    @Profile("optimistic-lock-probe")
    static class OptimisticLockProbe {

        @GetMapping
        void boom(HttpServletRequest request) throws Exception {
            throw (Exception) request.getAttribute("failure");
        }
    }
}
