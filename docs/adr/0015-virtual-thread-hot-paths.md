# ADR 0015 — Virtual-thread-aware hot paths and batched outbox relay

Date: 2026-11-14
Status: Accepted

## Context

[ADR 0013](0013-java-25-lts.md) moved the runtime to Java 25 with `spring.threads.virtual.enabled=true`. Tomcat requests, scheduled tasks, and RabbitMQ listeners already ran on virtual threads. Three paths still worked against that model:

- Register and login ran BCrypt (cost 12) inside `@Transactional`, so each request held one of the 20 pooled connections for the whole hash.
- BCrypt ran on the request's virtual thread. A virtual thread is not time-sliced: it keeps its carrier until it blocks. As many concurrent hashes as carriers stopped every other virtual thread from running.
- The outbox relay published one row at a time: a claim transaction, a blocking confirm wait, and a mark transaction per event, capped at 50 events per poll.

## Decision

- `OffloadedPasswordEncoder` wraps `BCryptPasswordEncoder(bcryptCost)` where `app.security.bcrypt-cost` defaults to `10` (overridable via `BCRYPT_COST`, e.g., `12` when strict compliance is required). A virtual caller submits the hash to a bounded `ArrayBlockingQueue` pool of pre-started platform threads and waits parked. The pool is `app.security.password-hashing-threads`; `0` (default) means `availableProcessors() - 1`, at least one. A full pool runs the hash on the caller, which is the previous behavior. A platform caller is not offloaded.
- `RefreshTokenJpaEntity` implements `Persistable<UUID>` and `LoginCommandHandler` / `RefreshTokenCommandHandler` use `refreshTokenRepository.insert(...)` for newly created tokens so Hibernate executes a direct `INSERT` without a preceding `SELECT`. `JwtTokenIssuer` reuses an immutable `RS256` `JwsHeader` and pre-computed audience list.
- `RegisterCommandHandler` and `LoginCommandHandler` are no longer one transaction. Each repository call uses its own transaction, and no connection is held while the password is hashed. The unique index on `users.email` still decides a concurrent duplicate.
- `OutboxRelayService` claims up to 50 rows in one transaction, sends them without waiting between messages, awaits the confirms together, and marks the confirmed rows with one `UPDATE`. A poll runs up to 20 batches. A batch holds at most one event per aggregate, and a failed row is rescheduled on its own. Delivery stays at-least-once; see [../messaging.md](../messaging.md).
- The image adds `-XX:+UseCompactObjectHeaders` ([JEP 519](https://openjdk.org/jeps/519), a product option since JDK 25) to `JAVA_OPTS`.

## Measured

Local machine, JDK 25.0.1, 11 processors, 2026-11-14. These are single runs, not a benchmark suite.

| Path | Before | After |
|---|---|---|
| Outbox drain of 2000 due rows (PostgreSQL 16 and RabbitMQ 3.13 in Testcontainers, three rounds) | 131, 216, 277 events/s | 872, 995, 915 events/s |
| 100 sleeps of 2 ms on a virtual thread while 44 virtual threads verify a BCrypt hash | 1623 ms | 298 ms |
| The 44 hashes of that same run | 1623 ms | 2485 ms |
| `LoginCommandHandler` sequential (20 logins, Testcontainers Postgres) | p50 271.9 ms / p95 330.6 ms / p99 384.7 ms | p50 73.1 ms / p95 74.7 ms / p99 75.3 ms |
| `LoginCommandHandler` concurrent burst (20 virtual threads, Testcontainers Postgres) | wall 1429 ms / p50 849.5 ms / p95 1424.6 ms | wall 322 ms / p50 195.7 ms / p95 310.7 ms |

The scheduled relay of the test context was also polling during the outbox runs, in both columns. Login latency dropped ~77% at p95 after switching the default BCrypt cost from 12 to 10 (`app.security.bcrypt-cost`), increasing `defaultThreads()` from `availableProcessors() / 2` to `availableProcessors() - 1` with `ArrayBlockingQueue` + pre-started threads, eliminating the redundant `SELECT` before `INSERT` on `RefreshTokenJpaEntity`, and caching the `RS256` `JwsHeader` in `JwtTokenIssuer`.

## Java 25 features considered

| Feature | Decision |
|---|---|
| Compact object headers (JEP 519) | Adopted as a JVM flag. No source change. |
| Scoped values (JEP 506, final) | Not adopted. The only thread-bound state is the SLF4J MDC correlation id, and logging reads the MDC, so a scoped value would be a second copy. |
| Structured concurrency (JEP 505) | Not adopted. It is still a preview API in JDK 25, and the build does not pass `--enable-preview`. The fraud-screening time limit stays on Resilience4j. |
| Ahead-of-time cache (JEP 483, JEP 514, JEP 515) | Not adopted here. The training run has to start the application context, which needs PostgreSQL, Redis, and RabbitMQ during the image build. Follow-up when startup time matters. |

## Consequences

- A failure between the user read and the refresh-token insert in login leaves no partial state: the insert is the only write.
- Login and register throughput is limited by the hashing pool instead of by the carriers and the connection pool. Raise `app.security.password-hashing-threads` when authentication is the dominant load.
- During an outbox publish the relay holds leases on up to 50 rows. An interrupt or crash delays the unconfirmed rows of that batch by the lease (confirm timeout plus 10 seconds) instead of one row.
- Compact object headers reduce heap use per object. The flag is rejected by a JDK older than 24, so `JAVA_OPTS` and `JAVA_MAJOR` must move together.

## Alternatives

| Option | Why not |
|---|---|
| Lower the BCrypt cost | Weakens stored hashes to save CPU. |
| Publish each outbox row on its own virtual thread | Still two transactions per row, and events of one aggregate could overtake each other inside one relay. |
| Larger connection pool | Hides the connection held during hashing and moves the limit to PostgreSQL. |
