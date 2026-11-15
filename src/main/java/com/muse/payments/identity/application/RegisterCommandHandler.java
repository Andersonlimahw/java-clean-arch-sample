package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.domain.ConflictException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Set;

@Service
public class RegisterCommandHandler implements CommandHandler<RegisterCommand, UserResponse> {

    private final UserRepository userRepository;
    private final UserReadRepository userReadRepository;
    private final UserCredentialsRepository userCredentialsRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public RegisterCommandHandler(
            UserRepository userRepository,
            UserReadRepository userReadRepository,
            UserCredentialsRepository userCredentialsRepository,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager
    ) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.userReadRepository = Objects.requireNonNull(userReadRepository);
        this.userCredentialsRepository = Objects.requireNonNull(userCredentialsRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
        this.transactionTemplate = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    }

    /**
     * Not one transaction on purpose. The duplicate check and the insert each run in their own
     * transaction, so no database connection is held while BCrypt runs.
     * The unique index on the email decides a concurrent duplicate, as it did before.
     */
    @Override
    public UserResponse handle(RegisterCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        String normalizedEmail = command.email().trim().toLowerCase();
        if (userReadRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException("User with email " + normalizedEmail + " is already registered");
        }

        String passwordHash = passwordEncoder.encode(command.password());
        User user = User.create(normalizedEmail, command.displayName(), Set.of("USER"));
        User saved = transactionTemplate.execute(status -> {
            User persisted = userRepository.save(user);
            userCredentialsRepository.save(persisted.getId(), passwordHash);
            return persisted;
        });

        return new UserResponse(saved.getId(), saved.getEmail(), saved.getDisplayName(), saved.getRoles());
    }
}
