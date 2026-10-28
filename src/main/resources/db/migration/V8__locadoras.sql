-- Locadoras v1 (B4, phase 4 of the maratona roadmap).
--
--   * visibility uses the shared vocabulary, like shelves (V5);
--   * the flags that hide the locadora's shelves from the profile tab and from the filters;
--   * profile order (position, pinned), cover and updated_at, as on shelves.
-- locadora_shelves.shelf_id already points to shelves: V5 renamed user_lists in place.

UPDATE locadoras SET visibility = CASE
    WHEN visibility IN ('public', 'mutual', 'secret') THEN visibility
    WHEN visibility = 'private' THEN 'secret'
    WHEN visibility = 'mutuals' THEN 'mutual'
    ELSE 'public'
END;
ALTER TABLE locadoras ADD CONSTRAINT ck_locadoras_visibility CHECK (visibility IN ('public', 'mutual', 'secret'));

ALTER TABLE locadoras
    ADD COLUMN hide_shelves_in_profile BOOLEAN      DEFAULT FALSE NOT NULL,
    ADD COLUMN hide_shelves_in_filters BOOLEAN      DEFAULT FALSE NOT NULL,
    ADD COLUMN "position"              INT          DEFAULT 0 NOT NULL,
    ADD COLUMN pinned                  BOOLEAN      DEFAULT FALSE NOT NULL,
    ADD COLUMN cover_url               VARCHAR(500) NULL,
    ADD COLUMN updated_at              TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL;
UPDATE locadoras SET updated_at = created_at;

-- Newest first, the order of the legacy listing.
UPDATE locadoras l SET "position" = ordered.rn - 1
FROM (
    SELECT id, row_number() OVER (PARTITION BY user_id ORDER BY created_at DESC, id DESC) AS rn
    FROM locadoras
) ordered
WHERE l.id = ordered.id;

CREATE INDEX idx_locadoras_user_id ON locadoras (user_id);
CREATE INDEX idx_locadora_shelves_shelf_id ON locadora_shelves (shelf_id);
