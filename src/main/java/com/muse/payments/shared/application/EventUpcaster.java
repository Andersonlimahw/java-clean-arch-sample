package com.muse.payments.shared.application;

/** Converts the JSON representation of one event schema version to the next version. */
public interface EventUpcaster {

    boolean supports(String eventType, int sourceSchemaVersion);

    String upcast(String payloadJson);
}
