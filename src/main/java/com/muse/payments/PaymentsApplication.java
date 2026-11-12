package com.muse.payments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentsApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PaymentsApplication.class);
        if (MigrationOnly.requested(args)) {
            MigrationOnly.configure(application);
        }
        ConfigurableApplicationContext context = application.run(args);
        if (MigrationOnly.enabled(context.getEnvironment())) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
