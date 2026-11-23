package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.application.PersonalDataErasureParticipant;
import com.muse.payments.shared.application.PersonalDataKeyPort;
import com.muse.payments.shared.domain.EntityNotFoundException;
import com.muse.payments.shared.domain.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/**
 * Right to erasure through crypto-shredding (spec 015, ADR 0020). One transaction destroys the
 * user's data key, appends {@code UserAnonymizationRequested}, deletes the credentials, and lets
 * every module sanitize its read models. {@code event_store} rows are never updated or deleted.
 */
@Service
public class AnonymizeUserUseCase implements CommandHandler<AnonymizeUserCommand, Void> {

    private static final Logger log = LoggerFactory.getLogger(AnonymizeUserUseCase.class);

    private final UserRepository userRepository;
    private final UserCredentialsRepository credentialsRepository;
    private final PersonalDataKeyPort personalDataKeys;
    private final List<PersonalDataErasureParticipant> participants;
    private final AuthRateLimitPort authRateLimit;

    public AnonymizeUserUseCase(
            UserRepository userRepository,
            UserCredentialsRepository credentialsRepository,
            PersonalDataKeyPort personalDataKeys,
            List<PersonalDataErasureParticipant> participants,
            AuthRateLimitPort authRateLimit
    ) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.credentialsRepository = Objects.requireNonNull(credentialsRepository);
        this.personalDataKeys = Objects.requireNonNull(personalDataKeys);
        this.participants = List.copyOf(Objects.requireNonNull(participants));
        this.authRateLimit = Objects.requireNonNull(authRateLimit);
    }

    @Override
    @Transactional
    public Void handle(AnonymizeUserCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        if (!command.actorIsAdmin() && !command.actorId().equals(command.userId())) {
            throw new ForbiddenException("Only the user or an administrator can erase this account");
        }

        User user = userRepository.findById(command.userId())
                .orElseThrow(() -> new EntityNotFoundException("User not found with id: " + command.userId()));
        if (user.isAnonymized()) {
            return null;
        }
        String email = user.getEmail();

        // Key first: its row lock makes an in-flight append for this user commit before we sanitize.
        personalDataKeys.destroy(user.getId());
        user.anonymize(command.actorId());
        userRepository.save(user);
        credentialsRepository.delete(user.getId());
        for (PersonalDataErasureParticipant participant : participants) {
            participant.erase(user.getId());
        }

        afterCommit(() -> authRateLimit.forgetAccount(email));
        log.info("Erased personal data of user {} (requested by {})", user.getId(), command.actorId());
        return null;
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
