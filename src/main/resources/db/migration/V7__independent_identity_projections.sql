-- Read models rebuild independently; rebuilding users must preserve token state.
-- Credentials are already a separate state table and are never truncated.
ALTER TABLE refresh_tokens DROP CONSTRAINT refresh_tokens_user_id_fkey;
