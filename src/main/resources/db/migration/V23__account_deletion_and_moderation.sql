-- Account deletion and moderation, needed to publish on the App Store and Google Play.
--
--   * Deleting a user deletes everything they made: the foreign keys to users (and the chains below
--     them) that still were RESTRICT become CASCADE, so `DELETE FROM users` takes the account away.
--   * user_blocks: who blocked whom. A block removes the follows both ways and hides each one from
--     the other.
--   * reports: content or accounts reported by users, for the team to review. The reporter is kept
--     as SET NULL so a report survives the deletion of either account.

-- ── Cascades ────────────────────────────────────────────────────────────────

ALTER TABLE comments DROP CONSTRAINT fk_comments_user_id__id;
ALTER TABLE comments ADD CONSTRAINT fk_comments_user_id__id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE comments DROP CONSTRAINT fk_comments_parent_id__id;
ALTER TABLE comments ADD CONSTRAINT fk_comments_parent_id__id
    FOREIGN KEY (parent_id) REFERENCES comments (id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE locadoras DROP CONSTRAINT fk_locadoras_user_id__id;
ALTER TABLE locadoras ADD CONSTRAINT fk_locadoras_user_id__id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE locadora_shelves DROP CONSTRAINT fk_locadora_shelves_locadora_id__id;
ALTER TABLE locadora_shelves ADD CONSTRAINT fk_locadora_shelves_locadora_id__id
    FOREIGN KEY (locadora_id) REFERENCES locadoras (id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE locadora_shelves DROP CONSTRAINT fk_locadora_shelves_shelf_id__id;
ALTER TABLE locadora_shelves ADD CONSTRAINT fk_locadora_shelves_shelf_id__id
    FOREIGN KEY (shelf_id) REFERENCES shelves (id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE shelves DROP CONSTRAINT fk_user_lists_user_id__id;
ALTER TABLE shelves ADD CONSTRAINT fk_user_lists_user_id__id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE shelf_items DROP CONSTRAINT fk_user_list_items_list_id__id;
ALTER TABLE shelf_items ADD CONSTRAINT fk_user_list_items_list_id__id
    FOREIGN KEY (shelf_id) REFERENCES shelves (id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE tags DROP CONSTRAINT fk_tags_user_id__id;
ALTER TABLE tags ADD CONSTRAINT fk_tags_user_id__id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE movie_tags DROP CONSTRAINT fk_movie_tags_tag_id__id;
ALTER TABLE movie_tags ADD CONSTRAINT fk_movie_tags_tag_id__id
    FOREIGN KEY (tag_id) REFERENCES tags (id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE movie_tags DROP CONSTRAINT fk_movie_tags_user_movie_id__id;
ALTER TABLE movie_tags ADD CONSTRAINT fk_movie_tags_user_movie_id__id
    FOREIGN KEY (user_movie_id) REFERENCES user_movies (id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE user_follows DROP CONSTRAINT fk_user_follows_follower_id__id;
ALTER TABLE user_follows ADD CONSTRAINT fk_user_follows_follower_id__id
    FOREIGN KEY (follower_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;
ALTER TABLE user_follows DROP CONSTRAINT fk_user_follows_followed_id__id;
ALTER TABLE user_follows ADD CONSTRAINT fk_user_follows_followed_id__id
    FOREIGN KEY (followed_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE user_movies DROP CONSTRAINT fk_user_movies_user_id__id;
ALTER TABLE user_movies ADD CONSTRAINT fk_user_movies_user_id__id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;

ALTER TABLE watch_logs DROP CONSTRAINT fk_watch_logs_user_id__id;
ALTER TABLE watch_logs ADD CONSTRAINT fk_watch_logs_user_id__id
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT;

-- ── Blocks ──────────────────────────────────────────────────────────────────

CREATE TABLE user_blocks (
    blocker_id BIGINT    NOT NULL,
    blocked_id BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_user_blocks PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT ck_user_blocks_not_self CHECK (blocker_id <> blocked_id),
    CONSTRAINT fk_user_blocks_blocker_id__id FOREIGN KEY (blocker_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_user_blocks_blocked_id__id FOREIGN KEY (blocked_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX user_blocks_blocked_id ON user_blocks (blocked_id);

-- ── Reports ─────────────────────────────────────────────────────────────────

CREATE TABLE reports (
    id               BIGSERIAL PRIMARY KEY,
    reporter_id      BIGINT        NULL,
    reported_user_id BIGINT        NULL,
    target_type      VARCHAR(20)   NOT NULL CONSTRAINT ck_reports_target_type CHECK (
        target_type IN ('user', 'activity', 'comment', 'message', 'shelf', 'locadora', 'maratona', 'cineclube', 'watch_log')
    ),
    target_id        VARCHAR(100)  NOT NULL,
    reason           VARCHAR(20)   NOT NULL CONSTRAINT ck_reports_reason CHECK (
        reason IN ('spam', 'harassment', 'hate', 'sexual', 'violence', 'impersonation', 'spoiler', 'other')
    ),
    details          VARCHAR(1000) NULL,
    status           VARCHAR(20)   NOT NULL DEFAULT 'open' CONSTRAINT ck_reports_status CHECK (
        status IN ('open', 'actioned', 'dismissed')
    ),
    created_at       TIMESTAMP     NOT NULL,
    CONSTRAINT fk_reports_reporter_id__id FOREIGN KEY (reporter_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_reports_reported_user_id__id FOREIGN KEY (reported_user_id) REFERENCES users (id) ON DELETE SET NULL ON UPDATE RESTRICT
);
CREATE INDEX reports_status_created_at ON reports (status, created_at);
