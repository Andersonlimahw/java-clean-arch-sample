package com.muse.payments.shared.infrastructure.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LgpdCryptoConfigurationTest {

    @Test
    void prodWithoutAKekFailsAtStartup() {
        assertThatThrownBy(() -> LgpdCryptoConfiguration.build(env("prod"), "", "lgpd-kek-v1", "", "lgpd-kek-v0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LGPD_KEK_BASE64");
        assertThatThrownBy(() -> LgpdCryptoConfiguration.build(
                env("prod", "migration"), "", "lgpd-kek-v1", "", "lgpd-kek-v0"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prodWithAConfiguredKekIsNotEphemeral() {
        KeyEncryptionKeys keks = LgpdCryptoConfiguration.build(env("prod"), randomKek(), "lgpd-kek-v2", "", "lgpd-kek-v1");

        assertThat(keks.ephemeral()).isFalse();
        assertThat(keks.activeId()).isEqualTo("lgpd-kek-v2");
        assertThat(keks.toString()).doesNotContain(keks.active().toString());
    }

    @Test
    void localWithoutAKekUsesOneEphemeralKekPerJvm() {
        KeyEncryptionKeys first = LgpdCryptoConfiguration.build(env("local"), "", "lgpd-kek-v1", "", "lgpd-kek-v0");
        KeyEncryptionKeys second = LgpdCryptoConfiguration.build(env("test"), "", "lgpd-kek-v1", "", "lgpd-kek-v0");

        assertThat(first.ephemeral()).isTrue();
        assertThat(first.activeId()).startsWith("ephemeral-").isEqualTo(second.activeId());
        assertThat(first.active()).isEqualTo(second.active());
    }

    @Test
    void rejectsAKekThatIsNot32Bytes() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> LgpdCryptoConfiguration.build(env("prod"), shortKey, "lgpd-kek-v1", "", "lgpd-kek-v0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> LgpdCryptoConfiguration.build(env("prod"), "not base64!", "lgpd-kek-v1", "", "lgpd-kek-v0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base64");
    }

    @Test
    void rotationKeepsThePreviousKekForUnwrapOnly() {
        KeyEncryptionKeys keks = LgpdCryptoConfiguration.build(env("prod"), randomKek(), "lgpd-kek-v2", randomKek(), "lgpd-kek-v1");

        assertThat(keks.find("lgpd-kek-v1")).isPresent();
        assertThat(keks.find("lgpd-kek-v2")).isPresent();
        assertThat(keks.find("lgpd-kek-v0")).isEmpty();
        assertThatThrownBy(() -> LgpdCryptoConfiguration.build(env("prod"), randomKek(), "same", randomKek(), "same"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static String randomKek() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
