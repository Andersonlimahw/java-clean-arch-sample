# ADR 0017 — Safe positional checkpoints across transaction ordering

- **Status:** Accepted
- **Date:** 2026-11-15
- **Complements:** [ADR 0016](0016-event-sourcing-with-hybrid-projections.md)

## Context

Transaction IDs and event sequence positions need not be allocated in the same order. A transaction can acquire its XID before waiting, then append after a newer transaction has reserved a smaller global position. Filtering visible rows by `transaction_id < snapshot.xmin` alone could advance past an uncommitted lower position. Snapshot xmax can also equal an active newer XID while an older XID has completed.

## Decision

`JdbcEventStore` allocates its transaction ID before reserving an event position. The async runner reads the sequence horizon, then allocates a fence XID in a separate short transaction. It waits for the current snapshot's xmin to reach that fence and processes only positions at or below the captured horizon. The existing xmin payload filter and transactional, locked checkpoint remain. After consuming the horizon, it captures the next one. On restart a fresh conservative fence is safe; only completed progress is durable.

This preserves concurrent appends to independent streams. Rebuild is an offline operation, acquiring the checkpoint before truncating its read model. Identity read models do not have a foreign key coupling their rebuilds; credentials remain independent non-rebuildable state.

## Consequences

Two short database queries per horizon and an extra XID are accepted for correctness. A long transaction delays asynchronous projections and raises observable lag. No global append lock or new infrastructure is introduced. Sequence gaps from rolled-back writes are harmless.

## Eval

`AsyncProjectionIT` forces older-XID/later-position and opposite commit order, then restarts the runner. It requires both events exactly once. `ProjectionRebuildIT` checks independent rebuilds and preservation of credentials and token state.
