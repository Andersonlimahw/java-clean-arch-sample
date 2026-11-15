package com.muse.payments.identity.domain;

import com.muse.payments.shared.domain.ConflictException;
import com.muse.payments.shared.domain.DomainEvent;
import com.muse.payments.shared.domain.EventSourcedAggregate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class RefreshTokenFamily extends EventSourcedAggregate<UUID> {

    public record TokenEntry(UUID tokenId, String tokenHash, boolean revoked, Instant expiresAt) {
    }

    private UUID familyId;
    private UUID userId;
    private boolean familyRevoked;
    private final Map<String, TokenEntry> tokensByHash = new LinkedHashMap<>();

    private RefreshTokenFamily() {
    }

    public static RefreshTokenFamily start(UUID userId, String tokenHash, Instant expiresAt) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        RefreshTokenFamily family = new RefreshTokenFamily();
        family.raise(new FamilyStartedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                userId,
                UUID.randomUUID(),
                tokenHash,
                expiresAt,
                Instant.now()
        ));
        return family;
    }

    public static RefreshTokenFamily rehydrate(List<? extends DomainEvent> history) {
        return EventSourcedAggregate.rehydrate(RefreshTokenFamily::new, history);
    }

    public static RefreshTokenFamily fromSnapshot(
            UUID familyId,
            UUID userId,
            boolean familyRevoked,
            List<TokenEntry> tokens,
            long snapshotVersion,
            List<? extends DomainEvent> tailEvents
    ) {
        RefreshTokenFamily family = new RefreshTokenFamily();
        family.familyId = Objects.requireNonNull(familyId);
        family.userId = Objects.requireNonNull(userId);
        family.familyRevoked = familyRevoked;
        for (TokenEntry token : tokens) {
            family.tokensByHash.put(token.tokenHash(), token);
        }
        family.restoreFromSnapshot(snapshotVersion, tailEvents);
        return family;
    }

    public void rotate(String currentTokenHash, String newTokenHash, Instant newExpiresAt, Instant now) {
        Objects.requireNonNull(currentTokenHash, "currentTokenHash must not be null");
        Objects.requireNonNull(newTokenHash, "newTokenHash must not be null");
        Objects.requireNonNull(newExpiresAt, "newExpiresAt must not be null");
        Objects.requireNonNull(now, "now must not be null");

        if (familyRevoked) {
            throw new ConflictException("Refresh token family is revoked");
        }
        TokenEntry entry = tokensByHash.get(currentTokenHash);
        if (entry == null || entry.revoked()) {
            throw new ConflictException("Refresh token is revoked or unknown");
        }
        if (entry.expiresAt().isBefore(now)) {
            throw new ConflictException("Refresh token is expired");
        }
        raise(new TokenRotatedEvent(
                UUID.randomUUID(),
                this.familyId,
                this.userId,
                UUID.randomUUID(),
                currentTokenHash,
                newTokenHash,
                newExpiresAt,
                now
        ));
    }

    public void revokeToken(String tokenHash, String reason, Instant now) {
        Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        TokenEntry entry = tokensByHash.get(tokenHash);
        if (entry != null && !entry.revoked()) {
            raise(new TokenRevokedEvent(UUID.randomUUID(), this.familyId, tokenHash, reason, now));
        }
    }

    public void revokeFamily(String reason, Instant now) {
        if (!familyRevoked) {
            raise(new FamilyRevokedEvent(UUID.randomUUID(), this.familyId, reason, now));
        }
    }

    @Override
    protected void apply(DomainEvent event) {
        switch (event) {
            case FamilyStartedEvent e -> {
                this.familyId = e.familyId();
                this.userId = e.userId();
                this.familyRevoked = false;
                this.tokensByHash.put(e.tokenHash(), new TokenEntry(e.tokenId(), e.tokenHash(), false, e.expiresAt()));
            }
            case TokenRotatedEvent e -> {
                TokenEntry oldEntry = this.tokensByHash.get(e.oldTokenHash());
                if (oldEntry != null) {
                    this.tokensByHash.put(e.oldTokenHash(),
                            new TokenEntry(oldEntry.tokenId(), oldEntry.tokenHash(), true, oldEntry.expiresAt()));
                }
                this.tokensByHash.put(e.newTokenHash(),
                        new TokenEntry(e.newTokenId(), e.newTokenHash(), false, e.expiresAt()));
            }
            case TokenRevokedEvent e -> {
                TokenEntry entry = this.tokensByHash.get(e.tokenHash());
                if (entry != null) {
                    this.tokensByHash.put(e.tokenHash(),
                            new TokenEntry(entry.tokenId(), entry.tokenHash(), true, entry.expiresAt()));
                }
            }
            case FamilyRevokedEvent e -> {
                this.familyRevoked = true;
                this.tokensByHash.replaceAll((hash, entry) ->
                        new TokenEntry(entry.tokenId(), entry.tokenHash(), true, entry.expiresAt()));
            }
            default -> throw new IllegalArgumentException("Unsupported refresh token family event: " + event.getClass().getName());
        }
    }

    @Override
    public UUID getId() {
        return familyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public boolean isFamilyRevoked() {
        return familyRevoked;
    }

    public List<TokenEntry> getTokens() {
        return List.copyOf(tokensByHash.values());
    }
}
