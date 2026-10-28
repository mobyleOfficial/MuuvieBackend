-- Gamification and well-being (B10, phase 10 of the maratona roadmap).
--
--   * user_pets: the bichinhos a user unlocked. The catalog and the unlock rules live in code
--     (PetCatalog). One row is the starter, chosen once and for good; the pinned one gets the
--     XP of the sessions and shows on the profile;
--   * user_missions: the weekly missions a user completed, so the XP is given once. The
--     missions of a week and their progress are computed from the sessions;
--   * diary_days and diary_tasks: the private Caderninho. Reading a day never creates it;
--     saving does, and saving an empty day deletes it.

CREATE TABLE user_pets (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL,
    pet_slug    VARCHAR(50) NOT NULL,
    xp          INT         DEFAULT 0 NOT NULL CONSTRAINT ck_user_pets_xp CHECK (xp >= 0),
    starter     BOOLEAN     DEFAULT FALSE NOT NULL,
    pinned      BOOLEAN     DEFAULT FALSE NOT NULL,
    unlocked_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_pets UNIQUE (user_id, pet_slug),
    CONSTRAINT fk_user_pets_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE UNIQUE INDEX uq_user_pets_starter ON user_pets (user_id) WHERE starter;
CREATE UNIQUE INDEX uq_user_pets_pinned ON user_pets (user_id) WHERE pinned;

CREATE TABLE user_missions (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    week_start   DATE        NOT NULL,
    mission_slug VARCHAR(50) NOT NULL,
    xp           INT         NOT NULL,
    completed_at TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_user_missions UNIQUE (user_id, week_start, mission_slug),
    CONSTRAINT fk_user_missions_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE TABLE diary_days (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL,
    "day"      DATE      NOT NULL,
    mood       VARCHAR(10) NULL CONSTRAINT ck_diary_days_mood CHECK (mood IN ('great', 'good', 'okay', 'bad', 'awful')),
    note       TEXT      NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_diary_days UNIQUE (user_id, "day"),
    CONSTRAINT fk_diary_days_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE TABLE diary_tasks (
    id          BIGSERIAL PRIMARY KEY,
    day_id      BIGINT       NOT NULL,
    category    VARCHAR(10)  NOT NULL CONSTRAINT ck_diary_tasks_category CHECK (category IN ('movie', 'short', 'series', 'study', 'other')),
    description VARCHAR(200) NOT NULL,
    done        BOOLEAN      DEFAULT FALSE NOT NULL,
    "position"  INT          DEFAULT 0 NOT NULL,
    movie_id    BIGINT       NULL,
    CONSTRAINT fk_diary_tasks_day_id__id FOREIGN KEY (day_id) REFERENCES diary_days (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_diary_tasks_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE SET NULL ON UPDATE RESTRICT
);
CREATE INDEX idx_diary_tasks_day ON diary_tasks (day_id, "position");
