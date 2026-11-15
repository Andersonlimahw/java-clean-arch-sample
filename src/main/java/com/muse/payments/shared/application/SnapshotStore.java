package com.muse.payments.shared.application;

import java.util.Optional;

public interface SnapshotStore {

    /** Returns empty when the persisted snapshot uses a different state schema version. */
    Optional<Snapshot> load(StreamRef stream, int expectedSchemaVersion);

    /** Stores a newer snapshot for the stream; older or equal versions do not replace it. */
    void save(Snapshot snapshot);
}
