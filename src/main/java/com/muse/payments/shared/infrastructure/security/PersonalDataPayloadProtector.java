package com.muse.payments.shared.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.muse.payments.shared.application.PersonalDataFields;
import com.muse.payments.shared.domain.PersonalData;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Encrypts the registered personal-data fields of an event payload with the subject's data key
 * and reverses it on read. A protected value is {@code lgpd:v1:<subjectId>:<base64(iv|ct|tag)>};
 * its AAD binds event type, field name, and subject. Values whose key is destroyed or missing read
 * as {@link PersonalData#REDACTED}. Payloads written before this feature carry no prefix and pass
 * through unchanged.
 */
@Component
public class PersonalDataPayloadProtector {

    public static final String PREFIX = "lgpd:v1:";

    private final Map<String, PersonalDataFields> byEventType;
    private final SubjectKeyStore keys;
    private final AesGcmCryptoService crypto;

    public PersonalDataPayloadProtector(
            List<PersonalDataFields> registrations,
            SubjectKeyStore keys,
            AesGcmCryptoService crypto
    ) {
        Map<String, PersonalDataFields> map = new HashMap<>();
        for (PersonalDataFields registration : Objects.requireNonNull(registrations)) {
            if (map.putIfAbsent(registration.eventType(), registration) != null) {
                throw new IllegalArgumentException("Personal data fields registered twice for " + registration.eventType());
            }
        }
        this.byEventType = Map.copyOf(map);
        this.keys = Objects.requireNonNull(keys);
        this.crypto = Objects.requireNonNull(crypto);
    }

    /** A protector with no registrations, for serializer tests that do not exercise personal data. */
    public static PersonalDataPayloadProtector none() {
        SubjectKeyStore unavailable = new SubjectKeyStore() {
            @Override
            public Optional<byte[]> keyForWrite(UUID subjectId) {
                throw new IllegalStateException("No subject key store configured");
            }

            @Override
            public Optional<byte[]> keyForRead(UUID subjectId) {
                throw new IllegalStateException("No subject key store configured");
            }
        };
        return new PersonalDataPayloadProtector(List.of(), unavailable, new AesGcmCryptoService());
    }

    /** True when a stored payload may hold protected values; lets readers skip the JSON walk. */
    public static boolean mayContainProtectedValues(String payloadJson) {
        return payloadJson != null && payloadJson.contains(PREFIX);
    }

    public void protect(String eventType, ObjectNode payload) {
        PersonalDataFields registration = byEventType.get(eventType);
        if (registration == null) {
            return;
        }
        UUID subjectId = subjectOf(registration, payload);
        Optional<byte[]> key = null;
        for (String field : registration.fields()) {
            JsonNode value = payload.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (!value.isTextual()) {
                throw new IllegalStateException("Personal data field " + field + " of " + eventType + " must be text");
            }
            if (PersonalData.isRedacted(value.textValue())) {
                continue;
            }
            if (key == null) {
                key = keys.keyForWrite(subjectId);
            }
            if (key.isEmpty()) {
                payload.put(field, PersonalData.REDACTED);
                continue;
            }
            byte[] sealed = crypto.encrypt(key.get(), value.textValue().getBytes(StandardCharsets.UTF_8),
                    aad(eventType, field, subjectId));
            payload.put(field, PREFIX + subjectId + ":" + Base64.getEncoder().encodeToString(sealed));
        }
    }

    public void reveal(String eventType, ObjectNode payload) {
        Map<UUID, Optional<byte[]>> keyCache = new HashMap<>();
        List<String> fieldNames = new ArrayList<>();
        payload.fieldNames().forEachRemaining(fieldNames::add);
        for (String field : fieldNames) {
            JsonNode value = payload.get(field);
            if (value == null || !value.isTextual() || !value.textValue().startsWith(PREFIX)) {
                continue;
            }
            Protected sealed = parse(eventType, field, value.textValue());
            Optional<byte[]> key = keyCache.computeIfAbsent(sealed.subjectId(), keys::keyForRead);
            if (key.isEmpty()) {
                payload.put(field, PersonalData.REDACTED);
                continue;
            }
            try {
                byte[] plaintext = crypto.decrypt(key.get(), sealed.bytes(), aad(eventType, field, sealed.subjectId()));
                payload.put(field, new String(plaintext, StandardCharsets.UTF_8));
            } catch (AesGcmCryptoService.CryptoIntegrityException e) {
                throw new IllegalStateException("Protected field " + field + " of " + eventType + " failed its integrity check");
            }
        }
    }

    private static UUID subjectOf(PersonalDataFields registration, ObjectNode payload) {
        JsonNode subject = payload.get(registration.subjectField());
        if (subject == null || !subject.isTextual()) {
            throw new IllegalStateException("Event " + registration.eventType() + " has no subject field "
                    + registration.subjectField());
        }
        try {
            return UUID.fromString(subject.textValue());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Subject field of " + registration.eventType() + " is not a UUID");
        }
    }

    private static Protected parse(String eventType, String field, String value) {
        String rest = value.substring(PREFIX.length());
        int separator = rest.indexOf(':');
        try {
            if (separator <= 0) {
                throw new IllegalArgumentException("missing subject");
            }
            return new Protected(UUID.fromString(rest.substring(0, separator)),
                    Base64.getDecoder().decode(rest.substring(separator + 1)));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Protected field " + field + " of " + eventType + " is malformed");
        }
    }

    private static byte[] aad(String eventType, String field, UUID subjectId) {
        return ("lgpd-field|" + eventType + "|" + field + "|" + subjectId).getBytes(StandardCharsets.UTF_8);
    }

    private record Protected(UUID subjectId, byte[] bytes) {
    }
}
