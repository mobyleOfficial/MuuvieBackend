-- Every user FK points to users.id (domain ADR, rule 8).
-- user_external_id stays until the code stops reading it; a later migration drops it.

ALTER TABLE refresh_tokens ADD COLUMN user_id BIGINT NULL CONSTRAINT fk_refresh_tokens_user_id__id REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE movie_likes ADD COLUMN user_id BIGINT NULL CONSTRAINT fk_movie_likes_user_id__id REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE review_likes ADD COLUMN user_id BIGINT NULL CONSTRAINT fk_review_likes_user_id__id REFERENCES users(id) ON DELETE CASCADE ON UPDATE RESTRICT;

UPDATE refresh_tokens t SET user_id = u.id FROM users u WHERE u.external_id = t.user_external_id;
UPDATE movie_likes t SET user_id = u.id FROM users u WHERE u.external_id = t.user_external_id;
UPDATE review_likes t SET user_id = u.id FROM users u WHERE u.external_id = t.user_external_id;

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_movie_likes_user_id ON movie_likes (user_id);
CREATE INDEX idx_review_likes_user_id ON review_likes (user_id);
