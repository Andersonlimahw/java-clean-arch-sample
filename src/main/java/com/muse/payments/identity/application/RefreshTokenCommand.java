package com.muse.payments.identity.application;

import com.muse.payments.shared.application.Command;

public record RefreshTokenCommand(
        String refreshToken
) implements Command<AuthTokensResponse> {
}
