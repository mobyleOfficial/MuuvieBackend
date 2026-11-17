-- Negative cache of the similar movies (Universo de Filmes, phase 3 follow-up).
--
--   * movies.similars_checked_at: when TMDB was last asked for the similar movies of the movie,
--     even when it listed none. A movie with no similars and a recent check is answered empty
--     without asking TMDB again; the check expires after the same 7 days as the similars.

ALTER TABLE movies ADD COLUMN similars_checked_at TIMESTAMP NULL;
