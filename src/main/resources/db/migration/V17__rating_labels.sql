-- Rating by label (B13.1, phase 13 of the maratona roadmap).
--
--   * the star ratings, their reviews and everything hanging from them are deleted (D4: there
--     is no production); the comments on a rating and the rating activities go with them;
--   * user_movies.rating becomes one of 7 labels and the generated sentiment column goes;
--   * the heart (is_favorite) becomes the label 'favorite' (D1);
--   * on_hold and abandoned leave user_movies.status and become labels too (D2). status keeps
--     only the mirror of the system shelves: watched, want_to_watch or none.

-- ── Old ratings ────────────────────────────────────────────────────────────

DELETE FROM comments WHERE target_type = 'rating';
DELETE FROM activities WHERE type = 'avaliacao';
UPDATE activities SET payload = (payload::jsonb - 'rating')::text WHERE payload::jsonb ? 'rating';

ALTER TABLE user_movies DROP COLUMN sentiment;
ALTER TABLE user_movies DROP CONSTRAINT ck_user_movies_rating;
ALTER TABLE user_movies ALTER COLUMN rating TYPE VARCHAR(20) USING NULL;
UPDATE user_movies SET review = NULL, review_has_spoiler = FALSE, rated_at = NULL;

-- ── Labels from the heart and the situations ───────────────────────────────

UPDATE user_movies
SET rating   = CASE
                   WHEN is_favorite THEN 'favorite'
                   WHEN status = 'abandoned' THEN 'abandoned'
                   ELSE 'on_hold'
               END,
    rated_at = updated_at
WHERE is_favorite OR status IN ('abandoned', 'on_hold');

ALTER TABLE user_movies ADD CONSTRAINT ck_user_movies_rating CHECK (rating IS NULL OR rating IN (
    'favorite', 'loved', 'liked', 'neutral', 'not_for_me', 'abandoned', 'on_hold'));
ALTER TABLE user_movies DROP COLUMN is_favorite;

UPDATE user_movies SET status = 'none' WHERE status IN ('abandoned', 'on_hold');
ALTER TABLE user_movies DROP CONSTRAINT ck_user_movies_status;
ALTER TABLE user_movies ADD CONSTRAINT ck_user_movies_status
    CHECK (status IN ('watched', 'want_to_watch', 'none'));
