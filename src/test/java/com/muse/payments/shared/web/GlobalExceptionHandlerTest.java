package com.muse.payments.shared.web;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.StaleObjectStateException;
import org.hibernate.StaleStateException;
import org.hibernate.dialect.lock.OptimisticEntityLockException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("optimisticLockFailures")
    void mapsOptimisticLockFailuresTo409ProblemJson(Failure failure) throws Exception {
        @RestController
        @RequestMapping("/test-optimistic-lock")
        class OptimisticLockProbe {
            @GetMapping
            void boom(HttpServletRequest request) throws Exception {
                throw (Exception) request.getAttribute("failure");
            }
        }

        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new OptimisticLockProbe())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        mockMvc.perform(get("/test-optimistic-lock")
                        .requestAttr("failure", failure.exception())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Concurrent Modification"))
                .andExpect(jsonPath("$.detail").value("The resource was modified concurrently. Please retry."))
                .andExpect(jsonPath("$.type").value("urn:problem-type:409"))
                .andExpect(jsonPath("$.instance").value("/test-optimistic-lock"))
                .andExpect(jsonPath("$.stackTrace").doesNotExist());
    }

    static Stream<Failure> optimisticLockFailures() {
        OptimisticLockException jpa = new OptimisticLockException("stale row");
        return Stream.of(
                new Failure(
                        "ObjectOptimisticLockingFailureException",
                        new ObjectOptimisticLockingFailureException("Payment", "id")
                ),
                new Failure(
                        "OptimisticLockingFailureException",
                        new OptimisticLockingFailureException("stale row")
                ),
                new Failure(
                        "JpaOptimisticLockingFailureException",
                        new JpaOptimisticLockingFailureException(jpa)
                ),
                new Failure("jakarta.persistence.OptimisticLockException", jpa),
                new Failure(
                        "StaleObjectStateException",
                        new StaleObjectStateException("Payment", "id")
                ),
                new Failure("StaleStateException", new StaleStateException("stale row")),
                new Failure(
                        "OptimisticEntityLockException",
                        new OptimisticEntityLockException("payment", "stale row")
                )
        );
    }

    record Failure(String name, Exception exception) {
        @Override
        public String toString() {
            return name;
        }
    }
}
