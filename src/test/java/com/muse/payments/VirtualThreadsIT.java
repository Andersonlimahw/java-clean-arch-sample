package com.muse.payments;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.threads.VirtualThreadExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VirtualThreadsIT extends TestcontainersBase {

    @Autowired
    private Environment environment;

    @Autowired
    private ServletWebServerApplicationContext serverContext;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RequestThreadProbe requestThreadProbe;

    @Test
    @DisplayName("spring.threads.virtual.enabled makes Tomcat dispatch requests on virtual threads")
    void tomcatDispatchesRequestsOnVirtualThreads() {
        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean.class)).isTrue();

        TomcatWebServer webServer = (TomcatWebServer) serverContext.getWebServer();
        Connector connector = webServer.getTomcat().getConnector();
        assertThat(connector.getProtocolHandler().getExecutor()).isInstanceOf(VirtualThreadExecutor.class);

        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health/liveness", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatusCode.valueOf(200));
        assertThat(requestThreadProbe.virtual)
                .as("request ran on %s", requestThreadProbe.threadDescription)
                .isTrue();
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
