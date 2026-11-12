-- Serves GET /api/v1/payments: WHERE user_id = ? ORDER BY created_at DESC, id DESC
-- (LIMIT/OFFSET). created_at ASC, id ASC is the reverse scan of the same index.
-- Depends only on payments from V3. This is V6, the next free version on main 745f822.
-- Renumber before merge if another migration already claimed V6.

CREATE INDEX IF NOT EXISTS idx_payments_user_created_at_id
    ON payments (user_id, created_at DESC, id DESC);
