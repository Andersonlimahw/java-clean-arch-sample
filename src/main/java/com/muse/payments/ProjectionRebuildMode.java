package com.muse.payments;

import java.util.Optional;

public final class ProjectionRebuildMode {

    public static final String PROPERTY = "app.projection-rebuild";

    private ProjectionRebuildMode() {
    }

    public static Optional<String> target(String[] args) {
        return target(args, System.getenv("APP_PROJECTION_REBUILD"));
    }

    static Optional<String> target(String[] args, String environmentValue) {
        String fromArgs = commandLineValue(args);
        if (fromArgs != null && !fromArgs.isBlank()) {
            return Optional.of(fromArgs.trim());
        }
        String fromProperty = System.getProperty(PROPERTY);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Optional.of(fromProperty.trim());
        }
        if (environmentValue != null && !environmentValue.isBlank()) {
            return Optional.of(environmentValue.trim());
        }
        return Optional.empty();
    }

    private static String commandLineValue(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        String selected = null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) {
                continue;
            }
            String normalized = arg.startsWith("--") ? arg.substring(2) : arg;
            if (normalized.equalsIgnoreCase(PROPERTY) && i + 1 < args.length) {
                selected = args[++i];
                continue;
            }
            int separator = normalized.indexOf('=');
            if (separator > 0 && normalized.substring(0, separator).equalsIgnoreCase(PROPERTY)) {
                selected = normalized.substring(separator + 1);
            }
        }
        return selected;
    }
}
