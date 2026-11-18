package com.muse.payments.payments.domain.psp;

/** Provider-neutral meaning of an inbound event. Anything else is {@link #UNKNOWN}. */
public enum ProviderEventOutcome {
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
