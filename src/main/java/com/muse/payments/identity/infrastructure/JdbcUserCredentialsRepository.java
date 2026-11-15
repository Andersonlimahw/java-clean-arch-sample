package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.application.UserCredentialsRepository;
import com.muse.payments.shared.domain.ValidationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcUserCredentialsRepository implements UserCredentialsRepository {

    private static final String UPSERT = """
            INSERT INTO user_credentials (user_id, password_hash, updated_at)
            VALUES (?, ?, NOW())
            ON CONFLICT (user_id) DO UPDATE SET
                password_hash = EXCLUDED.password_hash,
                updated_at = NOW()
            """;

    private static final String SELECT = """
            SELECT password_hash
            FROM user_credentials
            WHERE user_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcUserCredentialsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    @Transactional
    public void save(UUID userId, String passwordHash) {
        Objects.requireNonNull(userId, "userId is required");
        if (passwordHash == null || passwordHash.isBlank()) {
            throw new ValidationException("Password hash cannot be empty");
        }
        jdbcTemplate.update(UPSERT, userId, passwordHash);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findPasswordHashByUserId(UUID userId) {
        List<String> rows = jdbcTemplate.query(SELECT, (rs, rowNum) -> rs.getString("password_hash"), userId);
        return rows.stream().findFirst();
    }
}
