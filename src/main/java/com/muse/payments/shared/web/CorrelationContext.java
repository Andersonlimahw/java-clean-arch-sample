package com.muse.payments.shared.web;

import org.slf4j.MDC;

public final class CorrelationContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private CorrelationContext() {
    }

    public static void set(String correlationId) {
        if (correlationId != null) {
            MDC.put(MDC_KEY, correlationId);
        }
    }

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
