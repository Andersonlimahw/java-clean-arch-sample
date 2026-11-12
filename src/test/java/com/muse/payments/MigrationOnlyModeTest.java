package com.muse.payments;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationOnlyModeTest {

    @Test
    void detectsCliFlagAndEnvironmentVariable() {
        assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only=true"}, null)).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only=TRUE"}, "")).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{}, "true")).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{"--server.port=8080"}, "false")).isFalse();
        assertThat(MigrationOnlyMode.enabled(null, null)).isFalse();
    }
}
