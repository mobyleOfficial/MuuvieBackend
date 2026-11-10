-- Universo de Filmes, phase 5 (research/features/movie-universe/roadmap.md).
--
--   * universe_visits: the star systems a user opened in the universe, one row per movie, so
--     visiting a system twice counts once. The explorer bichinhos unlock by how many there are.

CREATE TABLE universe_visits (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL,
    movie_id   BIGINT    NOT NULL,
    visited_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_universe_visits UNIQUE (user_id, movie_id),
    CONSTRAINT fk_universe_visits_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_universe_visits_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
