# Specification: 011 — Login & Auth Performance Optimization

**Module**: `com.muse.payments.identity` & `com.muse.payments.shared.security`  
**Branch**: `perf/login-auth-performance`  
**Status**: In Progress

---

## 1. Objectives

Optimize the `POST /api/v1/auth/login` (`LoginCommandHandler`) hot path to achieve competitive local p95 latency (`< 150ms` under local load), while preserving OWASP-recommended password security and Clean Architecture / Spring Modulith boundaries:

1. **Configurable BCrypt cost (`app.security.bcrypt-cost`)**:
   - Default to `10` (within OWASP interactive web API recommendations, ~60–100ms locally vs ~500ms at cost 12).
   - Support environment override via `BCRYPT_COST` (e.g., `10` for local/test/staging, `12` for strict compliance environments).
   - Reject invalid BCrypt cost values outside `[4, 31]` (fail-fast at startup).
2. **Optimize `OffloadedPasswordEncoder`**:
   - Analyze and tune `app.security.password-hashing-threads` default sizing (use `max(1, availableProcessors() - 1)` instead of `availableProcessors() / 2` to double hashing throughput under burst while leaving a core for virtual-thread carriers and I/O).
   - Pre-start core platform threads (`prestartAllCoreThreads()`) to eliminate cold-start thread creation on the first burst.
   - Use bounded `ArrayBlockingQueue` (instead of `LinkedBlockingQueue`) to avoid per-node allocations under concurrent bursts, while keeping bounded queue capacity and caller fallback behavior intact.
3. **Lean `LoginCommandHandler` & Persistence/JWT Hot Path**:
   - Eliminate the redundant `SELECT` before `INSERT` on `refreshTokenRepository.save` during login/rotation by introducing an explicit `insert(RefreshToken)` path backed by `Persistable<UUID>` on `RefreshTokenJpaEntity` (`em.persist` directly).
   - Keep `userRepository.findByEmail` and `refreshTokenRepository.insert` in separate short-lived transactions so no DB connection is held during BCrypt hashing.
   - Reuse immutable `JwsHeader` (`RS256`) and `audience` list in `JwtTokenIssuer` without per-request header builder / list allocations; keys remain parsed/generated once at startup in `JwtKeyConfig`.
4. **Validation & Documentation**:
   - Measure login latency (p50, p95, p99) before and after under local load (Testcontainers PostgreSQL + Redis + RabbitMQ).
   - Pass all unit, ArchUnit, Modulith, and integration tests (`mvn test-compile failsafe:integration-test`).
   - Update `docs/adr/0015-virtual-thread-hot-paths.md` with the measured before/after comparison and run `scripts/validate-docs.sh`.
