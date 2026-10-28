-- Remote feature flags served by GET /api/v1/config (F0.6).
-- A flag without a row uses the default declared in FeatureFlag.kt.
-- Toggle in production with: UPDATE feature_flags SET enabled = true, updated_at = CURRENT_TIMESTAMP WHERE key = '...';

CREATE TABLE feature_flags (
    key        VARCHAR(100) PRIMARY KEY,
    enabled    BOOLEAN      NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO feature_flags (key, enabled) VALUES ('new_navigation', false);
