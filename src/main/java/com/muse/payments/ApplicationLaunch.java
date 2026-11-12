package com.muse.payments;

import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Result of {@link PaymentsApplication#launch}. The context is open only when the
 * process is the normal server; migration mode always closes it before returning.
 */
record ApplicationLaunch(
        boolean migrationOnly,
        int exitCode,
        WebApplicationType webApplicationType,
        ConfigurableApplicationContext context
) {
}
