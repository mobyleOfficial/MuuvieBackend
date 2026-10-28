-- Profile, discovery and news (B5, phase 5 of the maratona roadmap).
--
--   * users get the public profile fields: @handle, display name, pronouns, banner,
--     accent color, links and the curation flag used by the discovery hub;
--   * profile_highlights holds the configurable sections of the profile (top 4,
--     featured shelf, featured locadora, soundtrack), one row per type;
--   * article_movies links a news article to the movies it mentions.

ALTER TABLE users
    ADD COLUMN handle       VARCHAR(30)  NULL,
    ADD COLUMN display_name VARCHAR(50)  NULL,
    ADD COLUMN pronouns     VARCHAR(30)  NULL,
    ADD COLUMN banner_url   VARCHAR(500) NULL,
    ADD COLUMN accent_color VARCHAR(7)   NULL,
    ADD COLUMN links        TEXT[]       DEFAULT ARRAY[]::text[] NOT NULL,
    ADD COLUMN featured     BOOLEAN      DEFAULT FALSE NOT NULL;
ALTER TABLE users ADD CONSTRAINT ck_users_handle CHECK (handle ~ '^[a-z0-9_.]{5,30}$');
ALTER TABLE users ADD CONSTRAINT ck_users_links CHECK (cardinality(links) <= 5);
CREATE UNIQUE INDEX uq_users_handle ON users (handle);

-- Existing accounts get their username as the handle when it already fits the format
-- and no other username collides with it once lowercased. The rest pick one on edit.
UPDATE users u SET handle = lower(u.username)
WHERE lower(u.username) ~ '^[a-z0-9_.]{5,30}$'
  AND NOT EXISTS (SELECT 1 FROM users o WHERE o.id <> u.id AND lower(o.username) = lower(u.username));

CREATE TABLE profile_highlights (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    type       VARCHAR(20) NOT NULL CONSTRAINT ck_profile_highlights_type CHECK (type IN ('top4', 'shelf', 'locadora', 'soundtrack')),
    "position" INT         NOT NULL,
    enabled    BOOLEAN     DEFAULT TRUE NOT NULL,
    -- JSON as TEXT, like import_jobs: {"movieIds":[...]}, {"shelfId":1}, {"locadoraId":1}, {"url":"..."}
    payload    TEXT        NOT NULL,
    updated_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_profile_highlight_type UNIQUE (user_id, type),
    CONSTRAINT fk_profile_highlights_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE TABLE article_movies (
    article_id BIGINT NOT NULL,
    movie_id   BIGINT NOT NULL,
    confidence REAL   NOT NULL,
    CONSTRAINT pk_article_movies PRIMARY KEY (article_id, movie_id),
    CONSTRAINT fk_article_movies_article_id__id FOREIGN KEY (article_id) REFERENCES articles (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_article_movies_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_article_movies_movie_id ON article_movies (movie_id);

-- Articles scraped before this migration are linked by the next run of the linker.
ALTER TABLE articles ADD COLUMN movies_linked_at TIMESTAMP NULL;

-- Discovery ranks by recent activity: shelf items and watch logs (indexed by V6) of the last 30 days.
CREATE INDEX idx_shelf_items_added_at ON shelf_items (added_at);
