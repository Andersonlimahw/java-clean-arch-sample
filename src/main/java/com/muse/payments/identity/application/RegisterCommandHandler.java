package com.muse.payments.identity.application;

import com.muse.payments.identity.domain.User;
import com.muse.payments.identity.domain.UserRepository;
import com.muse.payments.shared.application.CommandHandler;
import com.muse.payments.shared.domain.ConflictException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Set;

@Service
public class RegisterCommandHandler implements CommandHandler<RegisterCommand, UserResponse> {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegisterCommandHandler(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = Objects.requireNonNull(userRepository);
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
    }

    /**
     * Not one transaction on purpose. The duplicate check and the insert each run in the
     * repository's own transaction, so no database connection is held while BCrypt runs.
     * The unique index on the email decides a concurrent duplicate, as it did before.
     */
    @Override
    public UserResponse handle(RegisterCommand command) {
        Objects.requireNonNull(command, "command cannot be null");

        String normalizedEmail = command.email().trim().toLowerCase();
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new ConflictException("User with email " + normalizedEmail + " is already registered");
        }

        String passwordHash = passwordEncoder.encode(command.password());
        User user = User.create(normalizedEmail, passwordHash, command.displayName(), Set.of("USER"));
        User saved = userRepository.save(user);

        return new UserResponse(saved.getId(), saved.getEmail(), saved.getDisplayName(), saved.getRoles());
    }
}
