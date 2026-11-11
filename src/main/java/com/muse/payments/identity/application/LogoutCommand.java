package com.muse.payments.identity.application;

import com.muse.payments.shared.application.Command;

public record LogoutCommand(
        String refreshToken
) implements Command<Void> {
}
