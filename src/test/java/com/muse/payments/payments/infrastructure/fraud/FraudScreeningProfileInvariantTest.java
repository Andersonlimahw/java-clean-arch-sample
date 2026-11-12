package com.muse.payments.payments.infrastructure.fraud;

import com.muse.payments.payments.application.FraudScreeningPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = FraudScreeningTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"app.fraud-screening.mode=http", "spring.main.banner-mode=off"}
)
@ActiveProfiles({"test", "fraud-adapter-test"})
class FraudScreeningProfileInvariantTest {

    @Autowired
    private FraudScreeningPort port;

    @Test
    @DisplayName("The test profile starts and passes the resilience startup checks")
    void testProfileStarts() {
        assertThat(port).isNotNull();
    }
}
