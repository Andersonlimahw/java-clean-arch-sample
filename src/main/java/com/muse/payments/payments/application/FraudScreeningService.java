package com.muse.payments.payments.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Calls the fraud port outside the surrounding database transaction.
 * Payment creation is transactional, and the provider call may wait on a timeout and retries.
 * {@link Propagation#NOT_SUPPORTED} suspends that transaction so the JDBC connection is not held.
 */
@Service
public class FraudScreeningService {

    private final FraudScreeningPort fraudScreeningPort;

    public FraudScreeningService(FraudScreeningPort fraudScreeningPort) {
        this.fraudScreeningPort = Objects.requireNonNull(fraudScreeningPort);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public FraudScreeningResult screen(FraudScreeningRequest request) {
        return fraudScreeningPort.screen(request);
    }
}
