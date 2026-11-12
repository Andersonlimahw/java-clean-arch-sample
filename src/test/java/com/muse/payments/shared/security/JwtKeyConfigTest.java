package com.muse.payments.shared.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class JwtKeyConfigTest {

    @Test
    void ephemeralKeysAreAllowedOutsideProductionIncludingMigration() {
        MockEnvironment migration = new MockEnvironment();
        migration.setActiveProfiles("migration");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(migration)).isTrue();

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(local)).isTrue();

        assertThat(JwtKeyConfig.ephemeralKeysAllowed(new MockEnvironment())).isTrue();
    }

    @Test
    void productionStillRejectsEphemeralKeysWhenMigrationProfileIsAdded() {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(production)).isFalse();

        MockEnvironment productionMigration = new MockEnvironment();
        productionMigration.setActiveProfiles("prod", "migration");
        assertThat(JwtKeyConfig.ephemeralKeysAllowed(productionMigration)).isFalse();
    }
}
