package com.muse.payments.payments.infrastructure.fraud;

import com.muse.fraudscreening.FraudScreeningTestApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
        classes = FraudScreeningTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.fraud-screening.mode=http",
                "management.tracing.enabled=false",
                "management.otlp.tracing.export.enabled=false",
                "management.otlp.metrics.export.enabled=false",
                "spring.main.banner-mode=off",
                "spring.modulith.moments.enabled=false",
                "management.endpoint.health.validate-group-membership=false"
        }
)
@ActiveProfiles("test")
public @interface FraudScreeningSpringTest {
}
