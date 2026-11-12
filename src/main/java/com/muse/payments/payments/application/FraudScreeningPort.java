package com.muse.payments.payments.application;

/**
 * Outbound port for the external fraud provider. Implementations live in infrastructure.
 * The result is always explicit: a failure to obtain a decision is {@link FraudScreeningOutcome#UNDER_REVIEW},
 * never a silent clearance.
 */
public interface FraudScreeningPort {

    FraudScreeningResult screen(FraudScreeningRequest request);
}
