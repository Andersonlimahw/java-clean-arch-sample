package com.muse.payments;

import com.muse.payments.support.ControllableFraudScreening;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The broker is static and shared by every Spring test in the JVM. A context left open
 * keeps its notification listener, which would ack messages a later test expects to retry
 * or dead-letter. Close each context when its class finishes.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class TestcontainersBase extends SharedContainers {

    @Autowired
    private ObjectProvider<ControllableFraudScreening> fraudScreening;

    @BeforeEach
    void resetFraudScreeningBefore() {
        fraudScreening.ifAvailable(ControllableFraudScreening::reset);
    }

    @AfterEach
    void resetFraudScreeningAfter() {
        fraudScreening.ifAvailable(ControllableFraudScreening::reset);
    }
}
