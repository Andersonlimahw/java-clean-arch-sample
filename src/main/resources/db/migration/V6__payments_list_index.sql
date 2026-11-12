-- PAYMENTS_LIST_INDEX_VERSION=V6
-- Renumber this file (and its .conf) before merge if version 6 is already taken.
-- PR #15 (open against main 745f822) also ships V6__payment_version.sql.
-- Do not edit this script after it has been applied to a shared database.
-- Depends only on payments from V3. No other migration is required.
--
-- Writes stay allowed: CREATE INDEX CONCURRENTLY takes ShareUpdateExclusiveLock,
-- which blocks other DDL and VACUUM, not INSERT/UPDATE/DELETE.
-- There is no IF NOT EXISTS. A failed build can leave an INVALID index with this
-- name; drop that index before retrying, or Flyway will fail on "already exists"
-- instead of skipping the invalid one.
--
-- idx_payments_user_status and idx_payments_created_at are dropped. No payment
-- query filters by status or orders by created_at without user_id. The list and
-- countByUserId both start with user_id equality, which this index serves.
-- Flyway runs this file with executeInTransaction=false (see the sibling .conf)
-- and spring.flyway.postgresql.transactional-lock=false.

CREATE INDEX CONCURRENTLY idx_payments_user_created_at_id
    ON payments (user_id, created_at DESC, id DESC);

DROP INDEX CONCURRENTLY idx_payments_created_at;

DROP INDEX CONCURRENTLY idx_payments_user_status;
