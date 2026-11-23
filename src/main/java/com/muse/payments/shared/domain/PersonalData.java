package com.muse.payments.shared.domain;

/** Markers shared by every module for personal data that was erased (LGPD/GDPR). */
public final class PersonalData {

    /** Value shown wherever erased personal data used to be. */
    public static final String REDACTED = "[REDACTED_LGPD]";

    private PersonalData() {
    }

    public static boolean isRedacted(String value) {
        return REDACTED.equals(value);
    }
}
