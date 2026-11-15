# Tasks: 011 — Login & Auth Performance Optimization

- [x] Create `specs/011-login-auth-performance/{spec.md,plan.md,tasks.md}`.
- [x] Measure baseline login latency (before changes).
- [x] Make BCrypt cost configurable via `app.security.bcrypt-cost` (`BCRYPT_COST`, default `10`).
- [x] Optimize `OffloadedPasswordEncoder` thread sizing, `ArrayBlockingQueue`, and `prestartAllCoreThreads()`.
- [x] Eliminate redundant `SELECT` before `INSERT` on `refreshTokenRepository.insert` (`Persistable<UUID>`).
- [x] Reuse immutable `JwsHeader` and `audience` list in `JwtTokenIssuer`.
- [x] Add unit & integration tests (configurable BCrypt cost, direct insert, login latency benchmark).
- [x] Measure after login latency (p50, p95, p99) and verify p95 < 150ms.
- [x] Update `docs/adr/0015-virtual-thread-hot-paths.md` and run `scripts/validate-docs.sh`.
- [x] Run `mvn test` and `mvn test-compile failsafe:integration-test`.
- [x] Semantic commits, open PR, run `code-review-expert`, fix findings, and merge to `main` (PR #23, `2af343a`).
