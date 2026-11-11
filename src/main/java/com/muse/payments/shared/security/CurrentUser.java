package com.muse.payments.shared.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CurrentUser(
        UUID userId,
        Set<String> roles
) {
    public CurrentUser {
        Objects.requireNonNull(userId, "userId must not be null");
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public boolean hasRole(String role) {
        return roles.contains(role) || roles.contains("ROLE_" + role);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN") || hasRole("ROLE_ADMIN");
    }
}
