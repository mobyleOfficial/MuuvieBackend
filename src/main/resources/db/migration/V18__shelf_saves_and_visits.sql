-- Popular shelves (B14.4, phase 14 of the maratona roadmap).
--
--   * shelf_saves holds the shelves of others a user saved, for the "Salvas" list;
--   * shelf_visits keeps the last time a logged-in user other than the owner opened a shelf.
-- Both feed the ranking of /discover/shelves, with the "amei" on the shelf's activities and
-- the owner's followers.

CREATE TABLE shelf_saves (
    shelf_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_shelf_saves PRIMARY KEY (shelf_id, user_id),
    CONSTRAINT fk_shelf_saves_shelf_id__id FOREIGN KEY (shelf_id) REFERENCES shelves (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_shelf_saves_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX idx_shelf_saves_user ON shelf_saves (user_id, created_at DESC);

CREATE TABLE shelf_visits (
    shelf_id   BIGINT    NOT NULL,
    user_id    BIGINT    NOT NULL,
    visited_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_shelf_visits PRIMARY KEY (shelf_id, user_id),
    CONSTRAINT fk_shelf_visits_shelf_id__id FOREIGN KEY (shelf_id) REFERENCES shelves (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_shelf_visits_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
