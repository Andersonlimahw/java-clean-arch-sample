package com.muse.payments.shared.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.muse.payments.shared.application.PersonalDataFields;
import com.muse.payments.shared.domain.PersonalData;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonalDataPayloadProtectorTest {

    private static final String TYPE = "identity.user-registered";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InMemoryKeys keys = new InMemoryKeys();
    private final PersonalDataPayloadProtector protector = new PersonalDataPayloadProtector(
            List.of(new PersonalDataFields(TYPE, "userId", Set.of("email", "displayName"))),
            keys,
            new AesGcmCryptoService());

    @Test
    void encryptsRegisteredFieldsAndRevealsThem() {
        UUID userId = UUID.randomUUID();
        ObjectNode payload = payload(userId, "ana@example.com", "Ana");

        protector.protect(TYPE, payload);

        assertThat(payload.get("email").asText()).startsWith(PersonalDataPayloadProtector.PREFIX + userId + ":");
        assertThat(payload.toString()).doesNotContain("ana@example.com").doesNotContain("\"Ana\"");
        assertThat(payload.get("userId").asText()).isEqualTo(userId.toString());
        assertThat(payload.get("roles").get(0).asText()).isEqualTo("USER");

        protector.reveal(TYPE, payload);

        assertThat(payload.get("email").asText()).isEqualTo("ana@example.com");
        assertThat(payload.get("displayName").asText()).isEqualTo("Ana");
    }

    @Test
    void revealsRedactedOnceTheKeyIsDestroyed() {
        UUID userId = UUID.randomUUID();
        ObjectNode payload = payload(userId, "ana@example.com", "Ana");
        protector.protect(TYPE, payload);
        String storedEmail = payload.get("email").asText();

        keys.destroy(userId);
        protector.reveal(TYPE, payload);

        assertThat(storedEmail).doesNotContain("ana@example.com");
        assertThat(payload.get("email").asText()).isEqualTo(PersonalData.REDACTED);
        assertThat(payload.get("displayName").asText()).isEqualTo(PersonalData.REDACTED);
    }

    @Test
    void writesRedactedForASubjectWhoseKeyIsAlreadyDestroyed() {
        UUID userId = UUID.randomUUID();
        keys.destroy(userId);
        ObjectNode payload = payload(userId, "ana@example.com", "Ana");

        protector.protect(TYPE, payload);

        assertThat(payload.get("email").asText()).isEqualTo(PersonalData.REDACTED);
        assertThat(payload.toString()).doesNotContain("ana@example.com");
    }

    @Test
    void leavesUnregisteredEventsAndLegacyPlaintextUntouched() {
        UUID userId = UUID.randomUUID();
        ObjectNode payload = payload(userId, "legacy@example.com", "Legacy");

        protector.protect("identity.user-disabled", payload);
        protector.reveal(TYPE, payload);

        assertThat(payload.get("email").asText()).isEqualTo("legacy@example.com");
        assertThat(keys.created).isEmpty();
    }

    @Test
    void skipsNullValuesWithoutCreatingAKey() {
        UUID userId = UUID.randomUUID();
        ObjectNode payload = payload(userId, null, null);

        protector.protect(TYPE, payload);

        assertThat(payload.get("email").isNull()).isTrue();
        assertThat(keys.created).isEmpty();
    }

    @Test
    void failsClosedWhenAValueIsMovedToAnotherField() {
        UUID userId = UUID.randomUUID();
        ObjectNode payload = payload(userId, "ana@example.com", "Ana");
        protector.protect(TYPE, payload);
        payload.put("displayName", payload.get("email").asText());

        assertThatThrownBy(() -> protector.reveal(TYPE, payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity")
                .hasMessageNotContaining("ana@example.com");
    }

    @Test
    void failsClosedOnAMalformedValue() {
        ObjectNode payload = payload(UUID.randomUUID(), PersonalDataPayloadProtector.PREFIX + "garbage", "Ana");

        assertThatThrownBy(() -> protector.reveal(TYPE, payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed");
    }

    @Test
    void requiresTheSubjectField() {
        ObjectNode payload = JSON.createObjectNode().put("email", "ana@example.com");

        assertThatThrownBy(() -> protector.protect(TYPE, payload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("subject");
    }

    private static ObjectNode payload(UUID userId, String email, String displayName) {
        ObjectNode payload = JSON.createObjectNode();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("userId", userId.toString());
        payload.put("email", email);
        payload.put("displayName", displayName);
        payload.putArray("roles").add("USER");
        return payload;
    }

    private static final class InMemoryKeys implements SubjectKeyStore {
        private final AesGcmCryptoService crypto = new AesGcmCryptoService();
        private final Map<UUID, byte[]> active = new HashMap<>();
        private final Set<UUID> destroyed = new HashSet<>();
        private final Set<UUID> created = new HashSet<>();

        @Override
        public Optional<byte[]> keyForWrite(UUID subjectId) {
            if (destroyed.contains(subjectId)) {
                return Optional.empty();
            }
            created.add(subjectId);
            return Optional.of(active.computeIfAbsent(subjectId, id -> crypto.newKey()));
        }

        @Override
        public Optional<byte[]> keyForRead(UUID subjectId) {
            return destroyed.contains(subjectId) ? Optional.empty() : Optional.ofNullable(active.get(subjectId));
        }

        void destroy(UUID subjectId) {
            active.remove(subjectId);
            destroyed.add(subjectId);
        }
    }
}
