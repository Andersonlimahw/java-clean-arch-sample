package com.muse.payments.identity.web;

import com.muse.payments.identity.application.AnonymizeUserCommand;
import com.muse.payments.identity.application.AnonymizeUserUseCase;
import com.muse.payments.shared.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Account data rights (LGPD/GDPR)")
public class UserController {

    private final AnonymizeUserUseCase anonymizeUser;

    public UserController(AnonymizeUserUseCase anonymizeUser) {
        this.anonymizeUser = Objects.requireNonNull(anonymizeUser);
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Erase the user's personal data (right to be forgotten). The user or ADMIN. "
            + "Idempotent: 204 on repeat. Event history stays; its personal fields become unreadable.")
    public ResponseEntity<Void> erase(
            @PathVariable UUID userId,
            @Parameter(hidden = true) CurrentUser currentUser
    ) {
        anonymizeUser.handle(new AnonymizeUserCommand(userId, currentUser.userId(), currentUser.isAdmin()));
        return ResponseEntity.noContent().build();
    }
}
