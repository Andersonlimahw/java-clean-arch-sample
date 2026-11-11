package com.muse.payments.identity.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest {

    @Test
    @DisplayName("Create active refresh token")
    void createActiveToken() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);

        RefreshToken token = RefreshToken.create(userId, "token_hash_1", expiresAt);

        assertThat(token.getId()).isNotNull();
        assertThat(token.getUserId()).isEqualTo(userId);
        assertThat(token.getFamilyId()).isNotNull();
        assertThat(token.isRevoked()).isFalse();
        assertThat(token.isExpired(Instant.now())).isFalse();
    }

    @Test
    @DisplayName("Rotating token revokes parent and generates child in same family")
    void rotateToken() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(3600);
        RefreshToken parent = RefreshToken.create(userId, "hash_parent", expiresAt);

        Instant childExpiresAt = Instant.now().plusSeconds(7200);
        RefreshToken child = parent.rotate("hash_child", childExpiresAt);

        assertThat(parent.isRevoked()).isTrue();
        assertThat(child.isRevoked()).isFalse();
        assertThat(child.getFamilyId()).isEqualTo(parent.getFamilyId());
        assertThat(child.getTokenHash()).isEqualTo("hash_child");
        assertThat(child.getId()).isNotEqualTo(parent.getId());
    }

    @Test
    @DisplayName("Expired token is identified correctly")
    void tokenExpired() {
        UUID userId = UUID.randomUUID();
        Instant past = Instant.now().minusSeconds(10);
        RefreshToken token = RefreshToken.create(userId, "hash_expired", past);

        assertThat(token.isExpired(Instant.now())).isTrue();
    }
}
