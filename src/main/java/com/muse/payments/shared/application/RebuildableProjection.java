package com.muse.payments.shared.application;

public interface RebuildableProjection {

    String projectionName();

    String streamType();

    void truncate();

    void apply(StoredEvent event);
}
