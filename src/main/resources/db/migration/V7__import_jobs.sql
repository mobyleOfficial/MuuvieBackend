-- Import jobs (B3, phase 3 of the maratona roadmap).
--
--   * import_jobs: one row per import, persisted so the app can follow it after leaving the
--     screen and so the history survives restarts. Status flow:
--     pending -> reading -> preview -> importing -> done, or failed / cancelled.
--     The bundle read from the source is kept until the job ends, so confirming the
--     preview does not scrape again. JSON is stored as TEXT (written and read by the app only).
--   * import_unmatched: movies the import could not match with TMDB confidently. Nothing is
--     dropped silently: the movie is imported anyway and waits here for the user to confirm.
-- Credentials used to read the source (Filmow cookies) are never stored.

CREATE TABLE import_jobs (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    source      VARCHAR(20)  NOT NULL CONSTRAINT ck_import_jobs_source CHECK (source IN ('filmow', 'letterboxd')),
    status      VARCHAR(20)  NOT NULL CONSTRAINT ck_import_jobs_status
        CHECK (status IN ('pending', 'reading', 'preview', 'importing', 'done', 'failed', 'cancelled')),
    input_ref   VARCHAR(255) NOT NULL,
    bundle      TEXT         NULL,
    preview     TEXT         NULL,
    options     TEXT         NULL,
    counts      TEXT         NULL,
    processed   INT          DEFAULT 0 NOT NULL,
    total       INT          DEFAULT 0 NOT NULL,
    error       TEXT         NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    finished_at TIMESTAMP    NULL,
    CONSTRAINT fk_import_jobs_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE INDEX idx_import_jobs_user ON import_jobs (user_id, created_at DESC);
-- One active job per user and source.
CREATE UNIQUE INDEX uq_import_job_active ON import_jobs (user_id, source)
    WHERE status IN ('pending', 'reading', 'preview', 'importing');

CREATE TABLE import_unmatched (
    id          BIGSERIAL    PRIMARY KEY,
    job_id      BIGINT       NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    raw         TEXT         NOT NULL,
    candidates  TEXT         NOT NULL,
    resolution  VARCHAR(20)  DEFAULT 'pending' NOT NULL
        CONSTRAINT ck_import_unmatched_resolution CHECK (resolution IN ('pending', 'matched', 'skipped')),
    movie_id    BIGINT       NULL,
    created_at  TIMESTAMP    NOT NULL,
    CONSTRAINT fk_import_unmatched_job_id__id FOREIGN KEY (job_id) REFERENCES import_jobs (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_import_unmatched_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE SET NULL ON UPDATE RESTRICT
);

CREATE UNIQUE INDEX uq_import_unmatched_item ON import_unmatched (job_id, external_id);
