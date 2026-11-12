package com.muse.payments;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationOnlyModeTest {

    @Test
    void detectsCliFlagAndEnvironmentVariable() {
        assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only=true"}, null)).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only=TRUE"}, "")).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only", "yes"}, null)).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{}, "true")).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{}, "on")).isTrue();
        assertThat(MigrationOnlyMode.enabled(new String[]{"--server.port=8080"}, "false")).isFalse();
        assertThat(MigrationOnlyMode.enabled(null, null)).isFalse();
        assertThat(MigrationOnlyMode.enabled(new String[]{}, "maybe")).isFalse();
    }

    @Test
    void commandLineOverridesEnvironmentAndLastExplicitValueWins() {
        assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only=false"}, "true")).isFalse();
        assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only=true"}, "false")).isTrue();
        assertThat(MigrationOnlyMode.enabled(
                new String[]{"--app.migration-only=true", "--app.migration-only=false"},
                "true")).isFalse();
        assertThat(MigrationOnlyMode.enabled(
                new String[]{"app.migration-only=false", "--app.migration-only", "1"},
                "false")).isTrue();
    }

    @Test
    void systemPropertyOverridesEnvironmentButNotCommandLine() {
        String previous = System.getProperty(MigrationOnlyMode.PROPERTY);
        try {
            System.setProperty(MigrationOnlyMode.PROPERTY, "true");
            assertThat(MigrationOnlyMode.enabled(new String[]{}, "false")).isTrue();
            assertThat(MigrationOnlyMode.enabled(new String[]{"--app.migration-only=false"}, "true")).isFalse();
        } finally {
            if (previous == null) {
                System.clearProperty(MigrationOnlyMode.PROPERTY);
            } else {
                System.setProperty(MigrationOnlyMode.PROPERTY, previous);
            }
        }
    }
}
