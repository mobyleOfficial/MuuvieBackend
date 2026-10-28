-- Maratonas (B7, phase 7 of the maratona roadmap).
--
--   * movies gets the metadata the challenge criteria read and the enrichment did not keep:
--     original language, production countries and TMDB keywords. Enriched movies are queued
--     again so the loop fills them in;
--   * maratonas is the event, with its owners (the creator plus co-owners), categories of
--     challenges, levels, teams and prizes. Status: upcoming (open to join, not started),
--     active, finished or cancelled; the scheduler moves it along the period;
--   * maratona_participations links a user to a maratona with a level, an optional team and
--     the "Fila" shelf created on joining. Leaving deletes the row and the shelf;
--   * challenge_links are the movies a participant tied to a challenge, by hand or by the
--     criteria when a session is logged. A challenge with a link is done.

ALTER TABLE movies ADD COLUMN original_language VARCHAR(10) NULL;

CREATE TABLE movie_countries (
    movie_id BIGINT     NOT NULL,
    country  VARCHAR(2) NOT NULL,
    CONSTRAINT pk_movie_countries PRIMARY KEY (movie_id, country),
    CONSTRAINT fk_movie_countries_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_movie_countries_country ON movie_countries (country);

CREATE TABLE movie_keywords (
    movie_id   BIGINT       NOT NULL,
    keyword_id INT          NOT NULL,
    "name"     VARCHAR(255) NOT NULL,
    CONSTRAINT pk_movie_keywords PRIMARY KEY (movie_id, keyword_id),
    CONSTRAINT fk_movie_keywords_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_movie_keywords_keyword ON movie_keywords (keyword_id);

-- Fetch the new metadata for the movies already enriched.
UPDATE movies SET needs_enrichment = TRUE WHERE tmdb_id IS NOT NULL AND enriched_at IS NOT NULL;

CREATE TABLE maratonas (
    id            BIGSERIAL PRIMARY KEY,
    slug          VARCHAR(50)   NOT NULL CONSTRAINT uq_maratonas_slug UNIQUE,
    owner_id      BIGINT        NOT NULL,
    title         VARCHAR(100)  NOT NULL,
    description   VARCHAR(2000) NULL,
    logo_url      VARCHAR(500)  NULL,
    accent_color  VARCHAR(7)    NULL,
    starts_on     DATE          NOT NULL,
    ends_on       DATE          NOT NULL,
    status        VARCHAR(20)   DEFAULT 'upcoming' NOT NULL CONSTRAINT ck_maratonas_status CHECK (status IN ('upcoming', 'active', 'finished', 'cancelled')),
    password_hash VARCHAR(255)  NULL,
    social_link   VARCHAR(500)  NULL,
    -- Curated maratonas ("grupo confiável"); set by hand, there is no endpoint for it.
    trusted       BOOLEAN       DEFAULT FALSE NOT NULL,
    template_id   VARCHAR(50)   NULL,
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_maratonas_period CHECK (ends_on >= starts_on),
    CONSTRAINT fk_maratonas_owner_id__id FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_maratonas_status ON maratonas (status, starts_on);

-- Co-owners: they edit the maratona; only the creator cancels it or changes this list.
CREATE TABLE maratona_owners (
    maratona_id BIGINT NOT NULL,
    user_id     BIGINT NOT NULL,
    CONSTRAINT pk_maratona_owners PRIMARY KEY (maratona_id, user_id),
    CONSTRAINT fk_maratona_owners_maratona_id__id FOREIGN KEY (maratona_id) REFERENCES maratonas (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_maratona_owners_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE TABLE maratona_categories (
    id          BIGSERIAL PRIMARY KEY,
    maratona_id BIGINT      NOT NULL,
    "name"      VARCHAR(80) NOT NULL,
    "position"  INT         DEFAULT 0 NOT NULL,
    team_only   BOOLEAN     DEFAULT FALSE NOT NULL,
    CONSTRAINT fk_maratona_categories_maratona_id__id FOREIGN KEY (maratona_id) REFERENCES maratonas (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_maratona_categories_maratona ON maratona_categories (maratona_id);

CREATE TABLE maratona_challenges (
    id          BIGSERIAL PRIMARY KEY,
    category_id BIGINT       NOT NULL,
    description VARCHAR(300) NOT NULL,
    -- criteria: checked against the movie metadata; free: only linked by hand.
    "type"      VARCHAR(20)  DEFAULT 'free' NOT NULL CONSTRAINT ck_maratona_challenges_type CHECK ("type" IN ('criteria', 'free')),
    -- JSON as TEXT: {"all":[{"field":"genre","op":"in","value":[27]}, ...]}
    criteria    TEXT         NULL,
    "position"  INT          DEFAULT 0 NOT NULL,
    daily       BOOLEAN      DEFAULT FALSE NOT NULL,
    CONSTRAINT fk_maratona_challenges_category_id__id FOREIGN KEY (category_id) REFERENCES maratona_categories (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_maratona_challenges_category ON maratona_challenges (category_id);

CREATE TABLE maratona_levels (
    id             BIGSERIAL PRIMARY KEY,
    maratona_id    BIGINT      NOT NULL,
    "name"         VARCHAR(50) NOT NULL,
    min_challenges INT         NOT NULL CONSTRAINT ck_maratona_levels_min CHECK (min_challenges >= 1),
    CONSTRAINT fk_maratona_levels_maratona_id__id FOREIGN KEY (maratona_id) REFERENCES maratonas (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_maratona_levels_maratona ON maratona_levels (maratona_id);

CREATE TABLE maratona_teams (
    id          BIGSERIAL PRIMARY KEY,
    maratona_id BIGINT      NOT NULL,
    "name"      VARCHAR(50) NOT NULL,
    color       VARCHAR(7)  NULL,
    CONSTRAINT fk_maratona_teams_maratona_id__id FOREIGN KEY (maratona_id) REFERENCES maratonas (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_maratona_teams_maratona ON maratona_teams (maratona_id);

CREATE TABLE maratona_prizes (
    id          BIGSERIAL PRIMARY KEY,
    maratona_id BIGINT       NOT NULL,
    title       VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    CONSTRAINT fk_maratona_prizes_maratona_id__id FOREIGN KEY (maratona_id) REFERENCES maratonas (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_maratona_prizes_maratona ON maratona_prizes (maratona_id);

CREATE TABLE maratona_participations (
    id             BIGSERIAL PRIMARY KEY,
    maratona_id    BIGINT    NOT NULL,
    user_id        BIGINT    NOT NULL,
    -- Null when the level was removed before the start: the lowest level then applies.
    level_id       BIGINT    NULL,
    team_id        BIGINT    NULL,
    queue_shelf_id BIGINT    NULL,
    joined_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_maratona_participations UNIQUE (maratona_id, user_id),
    CONSTRAINT fk_maratona_participations_maratona_id__id FOREIGN KEY (maratona_id) REFERENCES maratonas (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_maratona_participations_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_maratona_participations_level_id__id FOREIGN KEY (level_id) REFERENCES maratona_levels (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_maratona_participations_team_id__id FOREIGN KEY (team_id) REFERENCES maratona_teams (id) ON DELETE SET NULL ON UPDATE RESTRICT,
    CONSTRAINT fk_maratona_participations_queue_shelf_id__id FOREIGN KEY (queue_shelf_id) REFERENCES shelves (id) ON DELETE SET NULL ON UPDATE RESTRICT
);
CREATE INDEX idx_maratona_participations_user ON maratona_participations (user_id);

CREATE TABLE challenge_links (
    id               BIGSERIAL PRIMARY KEY,
    participation_id BIGINT      NOT NULL,
    challenge_id     BIGINT      NOT NULL,
    movie_id         BIGINT      NOT NULL,
    watch_log_id     BIGINT      NULL,
    "source"         VARCHAR(10) NOT NULL CONSTRAINT ck_challenge_links_source CHECK ("source" IN ('manual', 'auto')),
    completed_at     TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_challenge_links UNIQUE (participation_id, challenge_id, movie_id),
    CONSTRAINT fk_challenge_links_participation_id__id FOREIGN KEY (participation_id) REFERENCES maratona_participations (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_challenge_links_challenge_id__id FOREIGN KEY (challenge_id) REFERENCES maratona_challenges (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_challenge_links_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_challenge_links_watch_log_id__id FOREIGN KEY (watch_log_id) REFERENCES watch_logs (id) ON DELETE SET NULL ON UPDATE RESTRICT
);
CREATE INDEX idx_challenge_links_challenge ON challenge_links (challenge_id);
