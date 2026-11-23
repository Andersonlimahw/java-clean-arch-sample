package com.muse.payments.identity.application;

import com.muse.payments.shared.application.Command;

import java.util.Objects;
import java.util.UUID;

/** Erase the personal data of {@code userId}, requested by {@code actorId}. */
public record AnonymizeUserCommand(
        UUID userId,
        UUID actorId,
        boolean actorIsAdmin
) implements Command<Void> {

    public AnonymizeUserCommand {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
    }
}
