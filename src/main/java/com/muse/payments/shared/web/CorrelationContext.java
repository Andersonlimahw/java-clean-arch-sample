package com.muse.payments.shared.web;

import org.slf4j.MDC;

public final class CorrelationContext {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private CorrelationContext() {
    }

    public static void set(String correlationId) {
        if (correlationId != null) {
            CURRENT.set(correlationId);
            MDC.put(MDC_KEY, correlationId);
        }
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
        MDC.remove(MDC_KEY);
    }
}
