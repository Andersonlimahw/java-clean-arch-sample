# Plan: 011 — Login & Auth Performance Optimization

## Architecture & Implementation Plan

1. **BCrypt Cost Configuration (`SecurityConfig` + `application.yml` + `.env.example`)**
   - Add `app.security.bcrypt-cost: ${BCRYPT_COST:10}` to `src/main/resources/application.yml`.
   - Document `BCRYPT_COST=10` in `.env.example`.
   - Inject `@Value("${app.security.bcrypt-cost:10}") int bcryptCost` into `SecurityConfig.passwordEncoder(...)`, validate `4 <= bcryptCost <= 31`, and pass it to `new BCryptPasswordEncoder(bcryptCost)`.

2. **`OffloadedPasswordEncoder` Sizing & Burst Handling**
   - Update `OffloadedPasswordEncoder.defaultThreads()` to `Math.max(1, Runtime.getRuntime().availableProcessors() - 1)`.
   - Replace `LinkedBlockingQueue` with `ArrayBlockingQueue` for zero-allocation queueing under bursts.
   - Call `executor.prestartAllCoreThreads()` in the constructor.
   - Keep the caller fallback on `RejectedExecutionException` and the `QUEUE_SLOTS_PER_THREAD` contract verified by `OffloadedPasswordEncoderTest`.

3. **Lean `LoginCommandHandler` & `JwtTokenIssuer`**
   - Add `RefreshToken insert(RefreshToken token)` to `RefreshTokenRepository` (with default delegating to `save` for compatibility).
   - Implement `Persistable<UUID>` on `RefreshTokenJpaEntity` with `@Transient private boolean isNew` + `@PostLoad`/`@PostPersist` resetting `isNew = false`.
   - Implement `RefreshTokenJpaAdapter.insert(RefreshToken)` setting `isNew = true` so Hibernate issues a direct `INSERT` without a preceding `SELECT`.
   - Use `refreshTokenRepository.insert(...)` in `LoginCommandHandler` and for `childToken` in `RefreshTokenCommandHandler`.
   - Use `Locale.ROOT` in `LoginCommandHandler` email normalization.
   - Cache `RS256` `JwsHeader` as a `static final` constant and pre-compute `List.of(properties.audience())` in `JwtTokenIssuer`.

4. **Verification & Benchmarking**
   - Measure BEFORE and AFTER login latency (p50, p95, p99) via an integration benchmark test against real Testcontainers.
   - Update `docs/adr/0015-virtual-thread-hot-paths.md` and `docs/concurrency.md` if needed.
   - Run `mvn test`, `mvn test-compile failsafe:integration-test`, and `bash scripts/validate-docs.sh`.
