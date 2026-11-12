package com.muse.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PaymentsApplication.class);
        boolean migrationOnly = MigrationOnlyMode.enabled(args);
        if (migrationOnly) {
            application.setAdditionalProfiles("migration");
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (migrationOnly) {
            int code = SpringApplication.exit(context, () -> 0);
            System.exit(code);
        }
    }
}
