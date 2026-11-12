package com.muse.payments.support;

import com.muse.payments.payments.application.FraudScreeningPort;
import com.muse.payments.payments.application.FraudScreeningRequest;
import com.muse.payments.payments.application.FraudScreeningResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test double for the full Spring context. Production does not ship this class.
 * The default answer is CLEAR so existing API tests still confirm a pending payment.
 * A test can change the answer or block inside {@code screen} to observe the connection pool.
 */
@Component
@ConditionalOnProperty(name = "app.fraud-screening.mode", havingValue = "stub")
public class ControllableFraudScreening implements FraudScreeningPort {

    private volatile FraudScreeningResult result = FraudScreeningResult.clear();
    private volatile CountDownLatch entered;
    private volatile CountDownLatch release;

    public void setResult(FraudScreeningResult result) {
        this.result = result;
    }

    public void blockNextCall() {
        this.entered = new CountDownLatch(1);
        this.release = new CountDownLatch(1);
    }

    public boolean awaitEntered(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch current = entered;
        return current != null && current.await(timeout, unit);
    }

    public void release() {
        CountDownLatch current = release;
        if (current != null) {
            current.countDown();
        }
    }

    @Override
    public FraudScreeningResult screen(FraudScreeningRequest request) {
        CountDownLatch gate = release;
        CountDownLatch started = entered;
        if (gate != null && started != null) {
            started.countDown();
            try {
                if (!gate.await(30, TimeUnit.SECONDS)) {
                    return FraudScreeningResult.underReview("test double timed out waiting to be released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return FraudScreeningResult.underReview("test double interrupted");
            } finally {
                this.entered = null;
                this.release = null;
            }
        }
        return result;
    }
}
