-- One user_movies row per (user, movie) (domain ADR, rule 1).
--
-- Until now the key was (user, movie, source), so a movie imported from Filmow and
-- also marked by hand had two rows. Merge them:
--   * the keeper is the 'manual' row, else the most recently updated one;
--   * manual values win; empty fields are filled from the most recent other row;
--   * is_favorite is OR, watched_at/created_at the earliest, rewatches the sum.
-- From now on `source` means who owns the row: imports only overwrite rows whose
-- source is not 'manual'.

CREATE TEMP TABLE user_movie_groups ON COMMIT DROP AS
SELECT id,
       first_value(id) OVER (
           PARTITION BY user_id, movie_id
           ORDER BY ("source" = 'manual') DESC, updated_at DESC, id DESC
       ) AS keeper_id
FROM user_movies;

DELETE FROM user_movie_groups g
WHERE NOT EXISTS (
    SELECT 1 FROM user_movie_groups o WHERE o.keeper_id = g.keeper_id AND o.id <> g.id
);

CREATE TEMP TABLE user_movie_counts ON COMMIT DROP AS
SELECT (SELECT count(*) FROM (SELECT DISTINCT user_id, movie_id FROM user_movies) d) AS distinct_pairs;

UPDATE user_movies k
SET is_favorite = agg.is_favorite,
    watched_at  = agg.watched_at,
    created_at  = agg.created_at,
    updated_at  = agg.updated_at,
    imported_at = agg.imported_at,
    rewatches   = agg.rewatches,
    rating      = COALESCE(k.rating, (
        SELECT um.rating FROM user_movies um JOIN user_movie_groups g ON g.id = um.id
        WHERE g.keeper_id = k.id AND um.rating IS NOT NULL
        ORDER BY um.updated_at DESC, um.id DESC LIMIT 1)),
    review      = COALESCE(k.review, (
        SELECT um.review FROM user_movies um JOIN user_movie_groups g ON g.id = um.id
        WHERE g.keeper_id = k.id AND um.review IS NOT NULL
        ORDER BY um.updated_at DESC, um.id DESC LIMIT 1))
FROM (
    SELECT g.keeper_id,
           bool_or(um.is_favorite) AS is_favorite,
           min(um.watched_at)      AS watched_at,
           min(um.created_at)      AS created_at,
           max(um.updated_at)      AS updated_at,
           max(um.imported_at)     AS imported_at,
           sum(um.rewatches)       AS rewatches
    FROM user_movies um
    JOIN user_movie_groups g ON g.id = um.id
    GROUP BY g.keeper_id
) agg
WHERE k.id = agg.keeper_id;

-- Re-point tags of the merged rows to the keeper, dropping the ones it already has.
DELETE FROM movie_tags t
USING user_movie_groups g
WHERE t.user_movie_id = g.id
  AND g.id <> g.keeper_id
  AND EXISTS (SELECT 1 FROM movie_tags kt WHERE kt.user_movie_id = g.keeper_id AND kt.tag_id = t.tag_id);

UPDATE movie_tags t
SET user_movie_id = g.keeper_id
FROM user_movie_groups g
WHERE t.user_movie_id = g.id AND g.id <> g.keeper_id;

DELETE FROM user_movies um
USING user_movie_groups g
WHERE um.id = g.id AND g.id <> g.keeper_id;

-- Legacy databases have a constraint (Exposed); fresh ones an index (V1).
ALTER TABLE user_movies DROP CONSTRAINT IF EXISTS uq_user_movie_source;
DROP INDEX IF EXISTS uq_user_movie_source;
CREATE UNIQUE INDEX uq_user_movie ON user_movies (user_id, movie_id);

DO $$
DECLARE
    expected BIGINT := (SELECT distinct_pairs FROM user_movie_counts);
    actual   BIGINT := (SELECT count(*) FROM user_movies);
BEGIN
    IF expected <> actual THEN
        RAISE EXCEPTION 'user_movies merge mismatch: expected % rows, got %', expected, actual;
    END IF;
    RAISE NOTICE 'user_movies merge: % rows', actual;
END $$;
