package com.muse.payments.shared.infrastructure.eventstore;

import com.fasterxml.jackson.databind.JsonNode;

record SerializedEvent(String eventType, int schemaVersion, JsonNode payload) {
}
