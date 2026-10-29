-- Overviews machine translated into Portuguese (DeepL) when TMDB has no pt-BR one.
--
--   * overview_translated: the overview is a translation; an official pt-BR one from TMDB, or
--     Filmow's, replaces it, and it never replaces either of them.
--   * the movies kept with an overview in another language go back to the enrichment queue to
--     be translated.

ALTER TABLE movies ADD COLUMN overview_translated BOOLEAN DEFAULT FALSE NOT NULL;

UPDATE movies SET needs_enrichment = TRUE WHERE overview_language IS NOT NULL;
