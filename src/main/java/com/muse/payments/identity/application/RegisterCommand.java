package com.muse.payments.identity.application;

import com.muse.payments.shared.application.Command;

public record RegisterCommand(
        String email,
        String password,
        String displayName
) implements Command<UserResponse> {
}
