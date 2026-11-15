package com.muse.payments.shared.infrastructure.eventstore;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.projections")
public class AsyncProjectionProperties {

    private int batchSize = 100;
    private long pollIntervalMs = 250;

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("app.projections.batch-size must be positive");
        }
        this.batchSize = batchSize;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        if (pollIntervalMs < 1) {
            throw new IllegalArgumentException("app.projections.poll-interval-ms must be positive");
        }
        this.pollIntervalMs = pollIntervalMs;
    }
}
