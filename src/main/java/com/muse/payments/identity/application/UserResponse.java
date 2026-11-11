package com.muse.payments.identity.application;

import java.util.Set;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        Set<String> roles
) {
}
