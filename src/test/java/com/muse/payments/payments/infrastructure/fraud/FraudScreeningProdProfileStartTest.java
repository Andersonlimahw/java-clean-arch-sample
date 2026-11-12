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
        properties = {
                "app.fraud-screening.mode=http",
                "spring.main.banner-mode=off",
                "FRAUD_SCREENING_BASE_URL=https://fraud.example"
        }
)
@ActiveProfiles({"prod", "fraud-adapter-test"})
class FraudScreeningProdProfileStartTest {

    @Autowired
    private FraudScreeningPort port;

    @Test
    @DisplayName("The prod profile starts only with an https provider URL")
    void prodProfileStarts() {
        assertThat(port).isNotNull();
    }
}
