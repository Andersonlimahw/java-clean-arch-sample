package com.muse.payments;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.outbox.scheduling.enabled=true",
                "app.outbox.poll-interval-ms=3600000"
        }
)
class VirtualThreadsIT extends TestcontainersBase {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RequestThreadProbe requestThreadProbe;

    @Autowired
    private TaskScheduler taskScheduler;

    @Test
    @DisplayName("Tomcat handles a real request on a virtual thread")
    void tomcatDispatchesRequestsOnVirtualThreads() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(requestThreadProbe.virtual)
                .as("request ran on %s", requestThreadProbe.threadDescription)
                .isTrue();
    }

    @Test
    @DisplayName("With virtual threads enabled, scheduled work runs on a virtual thread")
    void schedulerRunsOnVirtualThreads() throws Exception {
        assertThat(taskScheduler).isInstanceOf(SimpleAsyncTaskScheduler.class);
        AtomicBoolean virtual = new AtomicBoolean();
        AtomicReference<String> description = new AtomicReference<>();
        CountDownLatch ran = new CountDownLatch(1);
        taskScheduler.schedule(() -> {
            Thread current = Thread.currentThread();
            virtual.set(current.isVirtual());
            description.set(current.toString());
            ran.countDown();
        }, Instant.now());
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(virtual.get()).as("scheduled task ran on %s", description.get()).isTrue();
    }

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        RequestThreadProbe requestThreadProbe() {
            return new RequestThreadProbe();
        }

        @Bean
        Filter virtualThreadProbe(RequestThreadProbe probe) {
            return (request, response, chain) -> {
                if (request instanceof HttpServletRequest http
                        && http.getRequestURI().endsWith("/actuator/health/liveness")) {
                    Thread current = Thread.currentThread();
                    probe.virtual = current.isVirtual();
                    probe.threadDescription = current.toString();
                }
                chain.doFilter(request, response);
            };
        }
    }

    static final class RequestThreadProbe {
        volatile boolean virtual;
        volatile String threadDescription = "<no request>";
    }
}
