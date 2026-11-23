package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.shared.application.PersonalDataErasureParticipant;
import com.muse.payments.shared.application.PersonalDataKeyPort;
import com.muse.payments.shared.domain.EntityNotFoundException;
import com.muse.payments.shared.domain.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnonymizeUserUseCaseTest {

    private UserRepository users;
    private UserCredentialsRepository credentials;
    private PersonalDataKeyPort keys;
    private PersonalDataErasureParticipant participant;
    private AuthRateLimitPort rateLimit;
    private AnonymizeUserUseCase useCase;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        credentials = mock(UserCredentialsRepository.class);
        keys = mock(PersonalDataKeyPort.class);
        participant = mock(PersonalDataErasureParticipant.class);
        rateLimit = mock(AuthRateLimitPort.class);
        useCase = new AnonymizeUserUseCase(users, credentials, keys, List.of(participant), rateLimit);
    }

    @Test
    @DisplayName("Another non-admin user is forbidden and nothing is touched")
    void rejectsOtherUser() {
        UUID target = UUID.randomUUID();
        var command = new AnonymizeUserCommand(target, UUID.randomUUID(), false);

        assertThatThrownBy(() -> useCase.handle(command)).isInstanceOf(ForbiddenException.class);

        verify(users, never()).findById(any());
        verify(keys, never()).destroy(any());
    }

    @Test
    @DisplayName("Unknown user is not found")
    void unknownUser() {
        UUID target = UUID.randomUUID();
        when(users.findById(target)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(new AnonymizeUserCommand(target, target, false)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("The user erases self: key destroyed, event saved, credentials deleted, participants run")
    void selfErasure() {
        User user = User.create("self@example.com", "Self", Set.of("USER"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        useCase.handle(new AnonymizeUserCommand(user.getId(), user.getId(), false));

        assertThat(user.isAnonymized()).isTrue();
        verify(keys).destroy(user.getId());
        verify(users).save(user);
        verify(credentials).delete(user.getId());
        verify(participant).erase(user.getId());
        // Outside a transaction the after-commit action runs immediately.
        verify(rateLimit).forgetAccount("self@example.com");
    }

    @Test
    @DisplayName("An administrator can erase another user")
    void adminErasure() {
        User user = User.create("target@example.com", "Target", Set.of("USER"));
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        useCase.handle(new AnonymizeUserCommand(user.getId(), UUID.randomUUID(), true));

        assertThat(user.isAnonymized()).isTrue();
        verify(keys).destroy(user.getId());
    }

    @Test
    @DisplayName("An already anonymized user is a no-op")
    void repeatIsNoOp() {
        User user = User.create("again@example.com", "Again", Set.of("USER"));
        user.anonymize(user.getId());
        when(users.findById(user.getId())).thenReturn(Optional.of(user));

        useCase.handle(new AnonymizeUserCommand(user.getId(), user.getId(), false));

        verify(keys, never()).destroy(any());
        verify(users, never()).save(any());
        verify(participant, never()).erase(any());
    }
}
