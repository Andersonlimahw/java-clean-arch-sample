package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test
    void disablingAndReplayingPreservesStateWithoutNewEvents() {
        User user = User.create("replay@example.com", "Replay", Set.of("USER"));
        user.disable();
        User replay = User.rehydrate(user.peekDomainEvents());
        assertThat(replay.isEnabled()).isFalse();
        assertThat(replay.getId()).isEqualTo(user.getId());
        assertThat(replay.version()).isEqualTo(2);
        assertThat(replay.peekDomainEvents()).isEmpty();
    }

    @Test
    @DisplayName("anonymize() redacts personal fields, disables the account and replays identically")
    void anonymizeRedactsAndReplays() {
        User user = User.create("erase@example.com", "Erase Me", Set.of("USER"));
        user.anonymize(user.getId());

        assertThat(user.isAnonymized()).isTrue();
        assertThat(user.isEnabled()).isFalse();
        assertThat(user.getEmail()).isEqualTo(User.anonymizedEmail(user.getId()));
        assertThat(user.getDisplayName()).isEqualTo("[REDACTED_LGPD]");
        assertThat(user.peekDomainEvents()).last().isInstanceOf(UserAnonymizationRequestedEvent.class);

        User replay = User.rehydrate(user.peekDomainEvents());
        assertThat(replay.isAnonymized()).isTrue();
        assertThat(replay.getEmail()).isEqualTo(user.getEmail());
        assertThat(replay.getVersion()).isEqualTo(user.getVersion());
    }

    @Test
    @DisplayName("anonymize() is idempotent: a second call raises no event")
    void anonymizeIsIdempotent() {
        User user = User.create("twice@example.com", "Twice", Set.of("USER"));
        user.anonymize(user.getId());
        int events = user.peekDomainEvents().size();

        user.anonymize(user.getId());

        assertThat(user.peekDomainEvents()).hasSize(events);
    }

    @Test
    @DisplayName("The anonymization event carries no personal data")
    void anonymizationEventHasNoPersonalData() {
        User user = User.create("clean@example.com", "Clean", Set.of("USER"));
        user.anonymize(user.getId());

        String event = user.peekDomainEvents().get(user.peekDomainEvents().size() - 1).toString();
        assertThat(event).doesNotContain("clean@example.com").doesNotContain("Clean");
    }

    @Test
    @DisplayName("Successfully create user with valid attributes")
    void createValidUser() {
        User user = User.create("test@example.com", "John Doe", Set.of("USER"));

        assertThat(user.getId()).isNotNull();
        assertThat(user.getEmail()).isEqualTo("test@example.com");
        assertThat(user.getDisplayName()).isEqualTo("John Doe");
        assertThat(user.getRoles()).contains("USER");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getVersion()).isZero();
        assertThat(user.peekDomainEvents()).singleElement().isInstanceOf(UserRegisteredEvent.class);
    }

    @Test
    @DisplayName("Rejects invalid email format")
    void rejectInvalidEmail() {
        assertThatThrownBy(() -> User.create("invalid-email", "John", Set.of("USER")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid email address format");
    }

    @Test
    @DisplayName("Rejects empty display name")
    void rejectEmptyDisplayName() {
        assertThatThrownBy(() -> User.create("test@example.com", "   ", Set.of("USER")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Display name cannot be empty");
    }
}
