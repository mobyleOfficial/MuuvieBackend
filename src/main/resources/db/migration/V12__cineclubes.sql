-- CineClubes (B8, phase 8 of the maratona roadmap).
--
--   * cineclubes is the group: one movie (one session) or a cycle of movies (N sessions),
--     each with its period and debate day. starts_on and ends_on cache the span of the
--     sessions (first start, last end or debate) for the list and the scheduler.
--     Status: upcoming (open to join, not started), active, finished or cancelled;
--   * cineclube_sessions holds the movies in order. opened_at marks the sessions whose movie
--     already went to the participants' Quero ver, and debate_notified_at the debate pushes
--     already sent, so the hourly scheduler never repeats them;
--   * cineclube_participations links a user to a CineClube. Leaving deletes the row. The
--     progress of a participant is not stored: it comes from their watch_logs of the movie
--     within the session period.

CREATE TABLE cineclubes (
    id            BIGSERIAL PRIMARY KEY,
    slug          VARCHAR(50)   NOT NULL CONSTRAINT uq_cineclubes_slug UNIQUE,
    owner_id      BIGINT        NOT NULL,
    title         VARCHAR(100)  NOT NULL,
    description   VARCHAR(2000) NULL,
    accent_color  VARCHAR(7)    NULL,
    password_hash VARCHAR(255)  NULL,
    debate_link   VARCHAR(500)  NULL,
    social_link   VARCHAR(500)  NULL,
    status        VARCHAR(20)   DEFAULT 'upcoming' NOT NULL CONSTRAINT ck_cineclubes_status CHECK (status IN ('upcoming', 'active', 'finished', 'cancelled')),
    is_cycle      BOOLEAN       DEFAULT FALSE NOT NULL,
    starts_on     DATE          NOT NULL,
    ends_on       DATE          NOT NULL,
    created_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_cineclubes_period CHECK (ends_on >= starts_on),
    CONSTRAINT fk_cineclubes_owner_id__id FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_cineclubes_status ON cineclubes (status, starts_on);

CREATE TABLE cineclube_sessions (
    id                 BIGSERIAL PRIMARY KEY,
    cineclube_id       BIGINT    NOT NULL,
    movie_id           BIGINT    NOT NULL,
    "position"         INT       DEFAULT 0 NOT NULL,
    starts_on          DATE      NOT NULL,
    ends_on            DATE      NOT NULL,
    debate_on          DATE      NOT NULL,
    opened_at          TIMESTAMP NULL,
    debate_notified_at TIMESTAMP NULL,
    CONSTRAINT ck_cineclube_sessions_period CHECK (ends_on >= starts_on AND debate_on >= starts_on),
    CONSTRAINT fk_cineclube_sessions_cineclube_id__id FOREIGN KEY (cineclube_id) REFERENCES cineclubes (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_cineclube_sessions_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE INDEX idx_cineclube_sessions_cineclube ON cineclube_sessions (cineclube_id, starts_on);

CREATE TABLE cineclube_participations (
    id           BIGSERIAL PRIMARY KEY,
    cineclube_id BIGINT    NOT NULL,
    user_id      BIGINT    NOT NULL,
    joined_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_cineclube_participations UNIQUE (cineclube_id, user_id),
    CONSTRAINT fk_cineclube_participations_cineclube_id__id FOREIGN KEY (cineclube_id) REFERENCES cineclubes (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_cineclube_participations_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_cineclube_participations_user ON cineclube_participations (user_id);
