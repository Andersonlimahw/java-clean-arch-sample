package com.muse.payments.identity.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class RefreshToken {

    private final UUID id;
    private final UUID userId;
    private final String tokenHash;
    private final UUID familyId;
    private boolean isRevoked;
    private final Instant expiresAt;
    private final Instant createdAt;

    public RefreshToken(
            UUID id,
            UUID userId,
            String tokenHash,
            UUID familyId,
            boolean isRevoked,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "Token ID cannot be null");
        this.userId = Objects.requireNonNull(userId, "User ID cannot be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "Token hash cannot be null");
        this.familyId = Objects.requireNonNull(familyId, "Family ID cannot be null");
        this.isRevoked = isRevoked;
        this.expiresAt = Objects.requireNonNull(expiresAt, "Expires at cannot be null");
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public static RefreshToken create(UUID userId, String tokenHash, Instant expiresAt) {
        return new RefreshToken(
                UUID.randomUUID(),
                userId,
                tokenHash,
                UUID.randomUUID(),
                false,
                expiresAt,
                Instant.now()
        );
    }

    public RefreshToken rotate(String newTokenHash, Instant newExpiresAt) {
        this.revoke();
        return new RefreshToken(
                UUID.randomUUID(),
                this.userId,
                newTokenHash,
                this.familyId,
                false,
                newExpiresAt,
                Instant.now()
        );
    }

    public void revoke() {
        this.isRevoked = true;
    }

    public boolean isExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public boolean isRevoked() {
        return isRevoked;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
