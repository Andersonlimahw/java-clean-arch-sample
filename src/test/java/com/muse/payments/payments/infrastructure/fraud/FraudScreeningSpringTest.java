package com.muse.payments.payments.infrastructure.fraud;

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
                "spring.main.banner-mode=off"
        }
)
@ActiveProfiles({"test", "fraud-adapter-test"})
public @interface FraudScreeningSpringTest {
}
