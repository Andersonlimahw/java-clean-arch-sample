package com.muse.payments;

/**
 * Detects the Kubernetes migration Job ({@code APP_MIGRATION_ONLY=true}).
 * Flyway runs during startup; the process then exits so the Job can complete.
 */
public final class MigrationOnlyMode {

    public static final String PROPERTY = "app.migration-only";

    private MigrationOnlyMode() {
    }

    public static boolean enabled(String[] args) {
        return enabled(args, System.getenv("APP_MIGRATION_ONLY"));
    }

    static boolean enabled(String[] args, String environmentValue) {
        if (args != null) {
            for (String arg : args) {
                if (arg == null) {
                    continue;
                }
                String normalized = arg.startsWith("--") ? arg.substring(2) : arg;
                if (normalized.equalsIgnoreCase(PROPERTY + "=true")) {
                    return true;
                }
            }
        }
        return "true".equalsIgnoreCase(environmentValue);
    }
}
