-- Filmow movie enrichment (research/features/filmow-movie-enrichment/spec.md).
--
--   * match_status: whether the movie is linked to TMDB. Scraped movies start as pending; a
--     movie no TMDB movie matched with confidence becomes unmatched and leaves the queue,
--     so unmatchable movies never block the enrichment of the others.
--   * match_method: how the TMDB id was found (tmdb, imdb, title, director, manual).
--   * enrichment_attempts / enrichment_retry_at: backoff after TMDB errors.

ALTER TABLE movies ADD COLUMN match_status VARCHAR(20) DEFAULT 'pending' NOT NULL;
ALTER TABLE movies ADD CONSTRAINT ck_movies_match_status CHECK (match_status IN ('pending', 'matched', 'unmatched'));
ALTER TABLE movies ADD COLUMN match_method VARCHAR(20) NULL;
ALTER TABLE movies ADD CONSTRAINT ck_movies_match_method CHECK (match_method IN ('tmdb', 'imdb', 'title', 'director', 'manual'));
ALTER TABLE movies ADD COLUMN enrichment_attempts INT DEFAULT 0 NOT NULL;
ALTER TABLE movies ADD COLUMN enrichment_retry_at TIMESTAMP NULL;

UPDATE movies SET match_status = 'matched', match_method = 'tmdb' WHERE tmdb_id IS NOT NULL;

CREATE INDEX idx_movies_enrichment_queue ON movies (needs_enrichment, match_status, enrichment_retry_at);
