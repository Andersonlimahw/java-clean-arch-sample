package com.muse.payments.identity.infrastructure;

import com.muse.payments.identity.domain.FamilyRevokedEvent;
import com.muse.payments.identity.domain.FamilyStartedEvent;
import com.muse.payments.identity.domain.TokenRevokedEvent;
import com.muse.payments.identity.domain.TokenRotatedEvent;
import com.muse.payments.shared.application.EventMetadata;
import com.muse.payments.shared.application.RebuildableProjection;
import com.muse.payments.shared.application.StoredEvent;
import com.muse.payments.shared.application.StreamRef;
import com.muse.payments.shared.application.SyncProjector;
import com.muse.payments.shared.domain.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Objects;

@Component
public class RefreshTokenProjector implements SyncProjector<DomainEvent>, RebuildableProjection {

    public static final String PROJECTION_NAME = "refresh_tokens";

    private static final String INSERT_TOKEN = """
            INSERT INTO refresh_tokens (
                id, user_id, token_hash, family_id, is_revoked, expires_at, created_at
            ) VALUES (?, ?, ?, ?, false, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private static final String REVOKE_TOKEN = """
            UPDATE refresh_tokens
            SET is_revoked = true
            WHERE token_hash = ?
            """;

    private static final String REVOKE_FAMILY = """
            UPDATE refresh_tokens
            SET is_revoked = true
            WHERE family_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public RefreshTokenProjector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
    }

    @Override
    public String projectionName() {
        return PROJECTION_NAME;
    }

    @Override
    public String streamType() {
        return IdentityEventRegistryConfiguration.REFRESH_TOKEN_FAMILY_STREAM_TYPE;
    }

    @Override
    public void truncate() {
        jdbcTemplate.execute("TRUNCATE TABLE refresh_tokens");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(StoredEvent event) {
        Objects.requireNonNull(event, "event is required");
        project(event.stream(), event.event(), event.streamVersion(), event.metadata());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void project(StreamRef stream, DomainEvent event, long streamVersion, EventMetadata metadata) {
        Objects.requireNonNull(stream, "stream is required");
        Objects.requireNonNull(event, "event is required");

        switch (event) {
            case FamilyStartedEvent e -> jdbcTemplate.update(
                    INSERT_TOKEN,
                    e.tokenId(),
                    e.userId(),
                    e.tokenHash(),
                    e.familyId(),
                    Timestamp.from(e.expiresAt()),
                    Timestamp.from(e.occurredAt())
            );
            case TokenRotatedEvent e -> {
                jdbcTemplate.update(REVOKE_TOKEN, e.oldTokenHash());
                jdbcTemplate.update(
                        INSERT_TOKEN,
                        e.newTokenId(),
                        e.userId(),
                        e.newTokenHash(),
                        e.familyId(),
                        Timestamp.from(e.expiresAt()),
                        Timestamp.from(e.occurredAt())
                );
            }
            case TokenRevokedEvent e -> jdbcTemplate.update(REVOKE_TOKEN, e.tokenHash());
            case FamilyRevokedEvent e -> jdbcTemplate.update(REVOKE_FAMILY, e.familyId());
            default -> throw new IllegalArgumentException(
                    "Unsupported refresh token family event for projection: " + event.getClass().getName());
        }
    }
}
