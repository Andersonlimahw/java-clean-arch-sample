package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.domain.EventSourcedAggregate;
import com.muse.payments.shared.domain.ValidationException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class User extends EventSourcedAggregate<UUID> {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");

    private UUID id;
    private String email;
    private String displayName;
    private Set<String> roles;
    private boolean enabled;
    private Instant createdAt;
    private Instant updatedAt;
    private Long projectionVersion;

    private User() {
    }

    private User(
            UUID id,
            String email,
            String displayName,
            Set<String> roles,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            Long projectionVersion
    ) {
        this.id = Objects.requireNonNull(id, "User ID cannot be null");
        this.email = validateEmail(email);
        this.displayName = validateDisplayName(displayName);
        this.roles = roles == null || roles.isEmpty() ? Set.of("USER") : Set.copyOf(roles);
        this.enabled = enabled;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.projectionVersion = projectionVersion;
    }

    public static User create(String email, String displayName, Set<String> roles) {
        String normalizedEmail = validateEmail(email);
        String normalizedDisplayName = validateDisplayName(displayName);
        Set<String> resolvedRoles = roles == null || roles.isEmpty() ? Set.of("USER") : Set.copyOf(roles);
        User user = new User();
        user.raise(new UserRegisteredEvent(UUID.randomUUID(), normalizedEmail, normalizedDisplayName, resolvedRoles, Instant.now()));
        return user;
    }

    public static User rehydrate(List<? extends DomainEvent> history) {
        return EventSourcedAggregate.rehydrate(User::new, history);
    }

    public static User rehydrate(
            UUID id,
            String email,
            String displayName,
            Set<String> roles,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            Long version
    ) {
        return new User(id, email, displayName, roles, enabled, createdAt, updatedAt, version);
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

    public void disable() {
        if (!this.enabled) {
            return;
        }
        raise(new UserDisabledEvent(this.id, Instant.now()));
    }

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case UserRegisteredEvent e -> {
                this.id = e.userId();
                this.email = e.email();
                this.displayName = e.displayName();
                this.roles = e.roles();
                this.enabled = true;
                this.createdAt = e.occurredAt();
                this.updatedAt = e.occurredAt();
                this.projectionVersion = null;
            }
            case UserDisabledEvent e -> {
                this.enabled = false;
                this.updatedAt = e.occurredAt();
                this.projectionVersion = null;
            }
            default -> throw new IllegalArgumentException("Unsupported user event: " + event.getClass().getName());
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        if (projectionVersion != null) {
            return projectionVersion;
        }
        return version() == 0 ? null : version() - 1;
    }
}
