-- Gradual rollout of a flag (F11.2, phase 11 of the maratona roadmap): with rollout_percent
-- set, an enabled flag is on only for that share of the logged-in users, picked by a stable
-- hash of the flag and the user. NULL means everyone. Logged-out visitors get it only at 100.
-- Roll the new navigation out with:
--   UPDATE feature_flags SET enabled = true, rollout_percent = 10, updated_at = CURRENT_TIMESTAMP WHERE key = 'new_navigation';

ALTER TABLE feature_flags ADD COLUMN rollout_percent SMALLINT NULL
    CONSTRAINT ck_feature_flags_rollout CHECK (rollout_percent BETWEEN 0 AND 100);
