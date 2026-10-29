-- Overview in another language when TMDB has no pt-BR one.
--
--   * overview_language: ISO 639-1 of the overview when it is not Portuguese (the English one
--     filled the gap); null for a Portuguese overview, as every one kept so far.
--   * the TMDB movies kept without an overview go back to the enrichment queue to get it.

ALTER TABLE movies ADD COLUMN overview_language VARCHAR(10) NULL;

UPDATE movies SET needs_enrichment = TRUE
WHERE tmdb_id IS NOT NULL AND (overview IS NULL OR overview = '');
