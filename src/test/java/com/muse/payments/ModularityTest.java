package com.muse.payments;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTest {

    @Test
    @DisplayName("Verify Spring Modulith module boundaries")
    void verifyModularStructure() {
        ApplicationModules modules = ApplicationModules.of(PaymentsApplication.class);
        modules.verify();
        new Documenter(modules).writeDocumentation();
    }
}
