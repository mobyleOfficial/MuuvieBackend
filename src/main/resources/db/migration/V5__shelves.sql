-- Lists become shelves (B1, phase 1 of the maratona roadmap).
--
--   * user_lists -> shelves, user_list_items -> shelf_items (list_id -> shelf_id);
--   * visibility is the only access flag: is_public is dropped and the values the
--     app sent before the shared vocabulary ('private', 'mutuals') are normalized;
--   * every account gets the two system shelves, "Já assisti" (watched) and
--     "Quero ver" (watchlist), filled from user_movies.status.
-- Runs in one transaction: a count mismatch at the end rolls everything back.

ALTER TABLE user_lists RENAME TO shelves;
ALTER TABLE user_list_items RENAME TO shelf_items;
ALTER SEQUENCE IF EXISTS user_lists_id_seq RENAME TO shelves_id_seq;
ALTER SEQUENCE IF EXISTS user_list_items_id_seq RENAME TO shelf_items_id_seq;
ALTER TABLE shelf_items RENAME COLUMN list_id TO shelf_id;
ALTER INDEX IF EXISTS user_lists_pkey RENAME TO shelves_pkey;
ALTER INDEX IF EXISTS user_list_items_pkey RENAME TO shelf_items_pkey;
-- New rows are inserted below; make sure the sequences are past every existing id.
SELECT setval(pg_get_serial_sequence('shelves', 'id'), GREATEST((SELECT max(id) FROM shelves), 0) + 1, false);
SELECT setval(pg_get_serial_sequence('shelf_items', 'id'), GREATEST((SELECT max(id) FROM shelf_items), 0) + 1, false);

-- Legacy databases have a unique constraint (Exposed); fresh ones an index (V1).
ALTER TABLE shelf_items DROP CONSTRAINT IF EXISTS uq_list_movie;
DROP INDEX IF EXISTS uq_list_movie;
CREATE UNIQUE INDEX uq_shelf_movie ON shelf_items (shelf_id, movie_id);

UPDATE shelves SET visibility = CASE
    WHEN visibility IN ('public', 'mutual', 'secret') THEN visibility
    WHEN visibility = 'private' THEN 'secret'
    WHEN visibility = 'mutuals' THEN 'mutual'
    WHEN is_public THEN 'public'
    ELSE 'secret'
END;
ALTER TABLE shelves DROP COLUMN is_public;
ALTER TABLE shelves ADD CONSTRAINT ck_shelves_visibility CHECK (visibility IN ('public', 'mutual', 'secret'));

ALTER TABLE shelves
    ADD COLUMN system_type   VARCHAR(20)  NULL CONSTRAINT ck_shelves_system_type CHECK (system_type IN ('watched', 'watchlist')),
    ADD COLUMN "position"    INT          DEFAULT 0 NOT NULL,
    ADD COLUMN pinned        BOOLEAN      DEFAULT FALSE NOT NULL,
    ADD COLUMN sort_mode     VARCHAR(30)  DEFAULT 'manual' NOT NULL,
    ADD COLUMN import_source VARCHAR(50)  NULL,
    ADD COLUMN external_id   VARCHAR(255) NULL,
    ADD COLUMN cover_url     VARCHAR(500) NULL,
    ADD COLUMN updated_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL;
UPDATE shelves SET updated_at = created_at;

CREATE INDEX idx_shelves_user_id ON shelves (user_id);
CREATE UNIQUE INDEX uq_shelf_system_type ON shelves (user_id, system_type) WHERE system_type IS NOT NULL;
CREATE UNIQUE INDEX uq_shelf_import ON shelves (user_id, import_source, external_id) WHERE external_id IS NOT NULL;

-- System shelves first on the profile, then the user's shelves, newest first (the old order).
UPDATE shelves s SET "position" = ordered.rn + 1
FROM (
    SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC, id DESC) AS rn
    FROM shelves
) ordered
WHERE s.id = ordered.id;

INSERT INTO shelves (user_id, "name", visibility, system_type, "position", sort_mode, created_at, updated_at)
SELECT u.id, 'Já assisti', 'public', 'watched', 0, 'added_newest', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users u;

INSERT INTO shelves (user_id, "name", visibility, system_type, "position", sort_mode, created_at, updated_at)
SELECT u.id, 'Quero ver', 'public', 'watchlist', 1, 'added_newest', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM users u;

INSERT INTO shelf_items (shelf_id, movie_id, "position", added_at)
SELECT s.id,
       um.movie_id,
       row_number() OVER (PARTITION BY s.id ORDER BY COALESCE(um.watched_at, um.created_at), um.id) - 1,
       COALESCE(um.watched_at, um.created_at)
FROM user_movies um
JOIN shelves s ON s.user_id = um.user_id AND s.system_type = 'watched'
WHERE um.status = 'watched';

INSERT INTO shelf_items (shelf_id, movie_id, "position", added_at)
SELECT s.id,
       um.movie_id,
       row_number() OVER (PARTITION BY s.id ORDER BY um.created_at, um.id) - 1,
       um.created_at
FROM user_movies um
JOIN shelves s ON s.user_id = um.user_id AND s.system_type = 'watchlist'
WHERE um.status = 'want_to_watch';

DO $$
DECLARE
    users_count     BIGINT := (SELECT count(*) FROM users);
    system_shelves  BIGINT := (SELECT count(*) FROM shelves WHERE system_type IS NOT NULL);
    watched_movies  BIGINT := (SELECT count(*) FROM user_movies WHERE status = 'watched');
    watched_items   BIGINT := (SELECT count(*) FROM shelf_items i JOIN shelves s ON s.id = i.shelf_id WHERE s.system_type = 'watched');
    wanted_movies   BIGINT := (SELECT count(*) FROM user_movies WHERE status = 'want_to_watch');
    wanted_items    BIGINT := (SELECT count(*) FROM shelf_items i JOIN shelves s ON s.id = i.shelf_id WHERE s.system_type = 'watchlist');
BEGIN
    IF system_shelves <> users_count * 2 THEN
        RAISE EXCEPTION 'system shelves mismatch: % users, % shelves', users_count, system_shelves;
    END IF;
    IF watched_items <> watched_movies OR wanted_items <> wanted_movies THEN
        RAISE EXCEPTION 'system shelf items mismatch: watched %/%, watchlist %/%',
            watched_items, watched_movies, wanted_items, wanted_movies;
    END IF;
    RAISE NOTICE 'shelves: % system shelves, % watched, % watchlist', system_shelves, watched_items, wanted_items;
END $$;
