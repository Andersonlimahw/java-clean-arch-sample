package com.muse.payments.identity.application;

import com.muse.payments.shared.application.Command;

public record LoginCommand(
        String email,
        String password
) implements Command<AuthTokensResponse> {
}
