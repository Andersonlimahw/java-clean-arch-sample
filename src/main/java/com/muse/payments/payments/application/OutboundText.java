package com.muse.payments.payments.application;

public final class OutboundText {

    static final int MAX_REASON = 500;

    private OutboundText() {
    }

    public static String limit(String value) {
        if (value == null || value.length() <= MAX_REASON) {
            return value;
        }
        return value.substring(0, MAX_REASON);
    }
}
