package com.muse.payments;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"default", "migration"})
class MigrationProfileIT extends TestcontainersBase {

    @Autowired
    private Flyway flyway;

    @Test
    @DisplayName("Migration profile applies Flyway without serving HTTP")
    void flywayRunsUnderMigrationProfile() {
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(4);
    }
}
