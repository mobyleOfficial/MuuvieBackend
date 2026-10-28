-- Watch sessions, star ratings and real comments (B2, phase 2 of the maratona roadmap).
--
--   * watch_logs: one row per session; `cycle` separates rewatches (domain ADR, rule 4);
--   * user_movies.rating becomes 0.5-5 in half stars; ratings on the old 0.5-10 scale
--     (anything above 5) are halved, the rest rounded to the nearest half star;
--   * user_movies.sentiment is generated from the rating and mirrors SentimentMapper;
--   * status keeps only situations: 'dropped' becomes 'abandoned', 'on_hold' is new;
--   * movie_likes is copied into user_movies.is_favorite (the table goes in B11);
--   * comments replace the mocked ones.

-- ── Ratings ────────────────────────────────────────────────────────────────

ALTER TABLE user_movies ALTER COLUMN rating TYPE NUMERIC(2, 1) USING (
    CASE
        WHEN rating IS NULL THEN NULL
        WHEN rating > 5 THEN GREATEST(0.5, LEAST(5, round(rating::numeric) / 2.0))
        ELSE GREATEST(0.5, LEAST(5, round(rating::numeric * 2) / 2.0))
    END
);
ALTER TABLE user_movies ADD CONSTRAINT ck_user_movies_rating
    CHECK (rating IS NULL OR (rating BETWEEN 0.5 AND 5 AND rating * 2 = floor(rating * 2)));

-- Same thresholds as SentimentMapper.
ALTER TABLE user_movies ADD COLUMN sentiment VARCHAR(20) GENERATED ALWAYS AS (
    CASE
        WHEN rating IS NULL THEN NULL
        WHEN rating >= 4.5 THEN 'loved'
        WHEN rating >= 3.5 THEN 'liked'
        WHEN rating >= 2.5 THEN 'neutral'
        ELSE 'not_for_me'
    END
) STORED;

ALTER TABLE user_movies
    ADD COLUMN rated_at           TIMESTAMP NULL,
    ADD COLUMN review_has_spoiler BOOLEAN   DEFAULT FALSE NOT NULL,
    ADD COLUMN current_cycle      INT       DEFAULT 1 NOT NULL,
    ADD COLUMN progress_minutes   INT       NULL,
    ADD COLUMN progress_percent   SMALLINT  NULL;
UPDATE user_movies SET rated_at = updated_at WHERE rating IS NOT NULL OR review IS NOT NULL;
-- A rewatched movie is on its next cycle already.
UPDATE user_movies SET current_cycle = rewatches + 1 WHERE rewatches > 0;
UPDATE user_movies SET progress_percent = 100 WHERE status = 'watched';

-- ── Situations ─────────────────────────────────────────────────────────────

UPDATE user_movies SET status = 'abandoned' WHERE status = 'dropped';
UPDATE user_movies SET status = 'none'
WHERE status NOT IN ('watched', 'want_to_watch', 'on_hold', 'abandoned', 'none');
ALTER TABLE user_movies ADD CONSTRAINT ck_user_movies_status
    CHECK (status IN ('watched', 'want_to_watch', 'on_hold', 'abandoned', 'none'));

-- ── Favorites ──────────────────────────────────────────────────────────────

UPDATE user_movies um SET is_favorite = TRUE
FROM movie_likes ml
WHERE ml.user_id = um.user_id AND ml.movie_id = um.movie_id AND NOT um.is_favorite;

INSERT INTO user_movies (user_id, movie_id, status, is_favorite, "source", created_at, updated_at)
SELECT DISTINCT ON (ml.user_id, ml.movie_id) ml.user_id, ml.movie_id, 'none', TRUE, 'manual', ml.created_at, ml.created_at
FROM movie_likes ml
JOIN movies m ON m.id = ml.movie_id
WHERE ml.user_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM user_movies um WHERE um.user_id = ml.user_id AND um.movie_id = ml.movie_id)
ORDER BY ml.user_id, ml.movie_id, ml.created_at;

-- ── Watching now ───────────────────────────────────────────────────────────

ALTER TABLE users ADD COLUMN watching_now_movie_id BIGINT NULL
    CONSTRAINT fk_users_watching_now REFERENCES movies (id) ON DELETE SET NULL;

-- ── Watch logs ─────────────────────────────────────────────────────────────

CREATE TABLE watch_logs (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    movie_id        BIGINT       NOT NULL,
    watched_on      DATE         NOT NULL,
    cycle           INT          DEFAULT 1 NOT NULL CHECK (cycle >= 1),
    mode            VARCHAR(10)  NOT NULL CHECK (mode IN ('watched', 'minutes', 'percent')),
    minutes_watched INT          NULL CHECK (minutes_watched >= 0),
    percent         SMALLINT     NULL CHECK (percent BETWEEN 0 AND 100),
    completed       BOOLEAN      DEFAULT FALSE NOT NULL,
    venue           VARCHAR(20)  NULL
        CHECK (venue IN ('cinema', 'streaming', 'tv', 'physical_media', 'festival', 'other')),
    provider_name   VARCHAR(255) NULL,
    emotion_ids     SMALLINT[]   DEFAULT ARRAY[]::smallint[] NOT NULL CHECK (cardinality(emotion_ids) <= 6),
    gif_id          VARCHAR(100) NULL,
    gif_url         VARCHAR(1000) NULL,
    "comment"       TEXT         NULL,
    has_spoiler     BOOLEAN      DEFAULT FALSE NOT NULL,
    visibility      VARCHAR(20)  DEFAULT 'public' NOT NULL CHECK (visibility IN ('public', 'mutual', 'secret')),
    import_source   VARCHAR(50)  NULL,
    external_id     VARCHAR(255) NULL,
    created_at      TIMESTAMP    NOT NULL,
    updated_at      TIMESTAMP    NOT NULL,
    CONSTRAINT fk_watch_logs_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_watch_logs_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX idx_watch_logs_user_movie ON watch_logs (user_id, movie_id, watched_on);
CREATE INDEX idx_watch_logs_user_created ON watch_logs (user_id, created_at DESC);
CREATE UNIQUE INDEX uq_watch_log_import ON watch_logs (user_id, import_source, external_id) WHERE external_id IS NOT NULL;

-- ── Comments ───────────────────────────────────────────────────────────────

CREATE TABLE comments (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    target_type VARCHAR(20) NOT NULL CHECK (target_type IN ('watch_log', 'rating', 'maratona', 'cineclube')),
    target_id   BIGINT      NOT NULL,
    parent_id   BIGINT      NULL,
    body        TEXT        NOT NULL,
    has_spoiler BOOLEAN     DEFAULT FALSE NOT NULL,
    created_at  TIMESTAMP   NOT NULL,
    deleted_at  TIMESTAMP   NULL,
    CONSTRAINT fk_comments_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_comments_parent_id__id FOREIGN KEY (parent_id) REFERENCES "comments"(id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX idx_comments_target ON comments (target_type, target_id, created_at);
