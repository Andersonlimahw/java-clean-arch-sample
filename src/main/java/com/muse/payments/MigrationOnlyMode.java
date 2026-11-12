package com.muse.payments;

/**
 * Detects the Kubernetes migration Job ({@code APP_MIGRATION_ONLY=true}).
 * Flyway runs during startup; the process then exits so the Job can complete.
 *
 * <p>Precedence matches Spring Boot externalized config for the sources that exist
 * before the application context is created: command line, then the system property
 * {@code app.migration-only}, then the environment variable. An explicit
 * {@code false} on the command line turns the Job off even when the variable is
 * {@code true}.
 */
public final class MigrationOnlyMode {

    public static final String PROPERTY = "app.migration-only";

    private MigrationOnlyMode() {
    }

    public static boolean enabled(String[] args) {
        return enabled(args, System.getenv("APP_MIGRATION_ONLY"));
    }

    static boolean enabled(String[] args, String environmentValue) {
        Boolean fromArgs = commandLineValue(args);
        if (fromArgs != null) {
            return fromArgs;
        }
        Boolean fromProperty = parseBoolean(System.getProperty(PROPERTY));
        if (fromProperty != null) {
            return fromProperty;
        }
        return Boolean.TRUE.equals(parseBoolean(environmentValue));
    }

    private static Boolean commandLineValue(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Boolean selected = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            String normalized = arg.startsWith("--") ? arg.substring(2) : arg;
            if (normalized.equalsIgnoreCase(PROPERTY)) {
                if (i + 1 < args.length) {
                    Boolean next = parseBoolean(args[i + 1]);
                    if (next != null) {
                        selected = next;
                        i++;
                    }
                }
                continue;
            }
            int separator = normalized.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            if (!normalized.substring(0, separator).equalsIgnoreCase(PROPERTY)) {
                continue;
            }
            Boolean parsed = parseBoolean(normalized.substring(separator + 1));
            if (parsed != null) {
                selected = parsed;
            }
        }
        return selected;
    }

    /**
     * Same tokens Spring Boot accepts for a boolean property: true/false, yes/no, on/off, 1/0.
     */
    private static Boolean parseBoolean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on")
                || value.equals("1")) {
            return Boolean.TRUE;
        }
        if (value.equalsIgnoreCase("false")
                || value.equalsIgnoreCase("no")
                || value.equalsIgnoreCase("off")
                || value.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
