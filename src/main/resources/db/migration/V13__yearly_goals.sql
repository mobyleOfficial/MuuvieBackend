-- Yearly goals (B9.3, phase 9 of the maratona roadmap): "watch 100 movies in 2027".
-- The progress is not stored: it is the distinct movies with a completed session in the year.

CREATE TABLE yearly_goals (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT    NOT NULL,
    "year"        INT       NOT NULL CONSTRAINT ck_yearly_goals_year CHECK ("year" BETWEEN 1900 AND 3000),
    target_movies INT       NOT NULL CONSTRAINT ck_yearly_goals_target CHECK (target_movies BETWEEN 1 AND 5000),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_yearly_goals UNIQUE (user_id, "year"),
    CONSTRAINT fk_yearly_goals_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

-- The statistics read a user's sessions of a year.
CREATE INDEX idx_watch_logs_user_watched ON watch_logs (user_id, watched_on);
