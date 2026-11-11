package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTest {

    @Test
    @DisplayName("Successfully create user with valid attributes")
    void createValidUser() {
        User user = User.create("test@example.com", "hashed_pass", "John Doe", Set.of("USER"));

        assertThat(user.getId()).isNotNull();
        assertThat(user.getEmail()).isEqualTo("test@example.com");
        assertThat(user.getDisplayName()).isEqualTo("John Doe");
        assertThat(user.getRoles()).contains("USER");
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("Rejects invalid email format")
    void rejectInvalidEmail() {
        assertThatThrownBy(() -> User.create("invalid-email", "hashed_pass", "John", Set.of("USER")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Invalid email address format");
    }

    @Test
    @DisplayName("Rejects empty display name")
    void rejectEmptyDisplayName() {
        assertThatThrownBy(() -> User.create("test@example.com", "hashed_pass", "   ", Set.of("USER")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Display name cannot be empty");
    }

    @Test
    @DisplayName("Rejects empty password hash")
    void rejectEmptyPasswordHash() {
        assertThatThrownBy(() -> User.create("test@example.com", "", "John", Set.of("USER")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Password hash cannot be empty");
    }
}
