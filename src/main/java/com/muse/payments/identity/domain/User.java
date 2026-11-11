package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.AggregateRoot;
import com.muse.payments.shared.domain.ValidationException;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class User extends AggregateRoot<UUID> {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    private final UUID id;
    private final String email;
    private final String passwordHash;
    private final String displayName;
    private final Set<String> roles;
    private boolean enabled;
    private final Instant createdAt;
    private Instant updatedAt;
    private Long version;

    public User(
            UUID id,
            String email,
            String passwordHash,
            String displayName,
            Set<String> roles,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            Long version
    ) {
        this.id = Objects.requireNonNull(id, "User ID cannot be null");
        this.email = validateEmail(email);
        this.passwordHash = validatePasswordHash(passwordHash);
        this.displayName = validateDisplayName(displayName);
        this.roles = roles == null || roles.isEmpty() ? Set.of("USER") : Set.copyOf(roles);
        this.enabled = enabled;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.version = version;
    }

    public static User create(String email, String passwordHash, String displayName, Set<String> roles) {
        Instant now = Instant.now();
        return new User(
                UUID.randomUUID(),
                email,
                passwordHash,
                displayName,
                roles,
                true,
                now,
                now,
                null
        );
    }

    private static String validateEmail(String email) {
        if (email == null || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            throw new ValidationException("Invalid email address format: " + email);
        }
        return email.trim().toLowerCase();
    }

    private static String validateDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isBlank()) {
            throw new ValidationException("Display name cannot be empty");
        }
        return displayName.trim();
    }

    private static String validatePasswordHash(String passwordHash) {
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new ValidationException("Password hash cannot be empty");
        }
        return passwordHash;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
