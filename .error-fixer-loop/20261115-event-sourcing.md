# Complete Event Sourcing regression fixes

The first `mvn -B clean verify` in `.worktrees/event-sourcing-full` failed: history reviewer returned 403, Prometheus test returned 500, shared Redis budgets caused unrelated 429s, migration assertions were stale, and projection rebuild deleted sibling token rows. Class: missing-test.

Preserve the existing HTTP contract, domain purity and WIP. Add the explicit GET history role matcher; enable observability in its test; reset only test auth buckets; assert all delivered migrations. Rebuild each read model independently and preserve credentials. Test older-XID/later-position transactions as well as ordinary reversed commits: fence allocated positions before advancing a checkpoint. Do not suppress failures or relax latency/status assertions.

Verification: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock bash scripts/ci-local.sh`, then API smoke, Newman and k6 against an isolated Compose. Keep append-only migrations after V4. Rules: `docs/rules/refactor-and-validation.md`.
