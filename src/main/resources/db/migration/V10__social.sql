-- Social (B6, phase 6 of the maratona roadmap).
--
--   * activities is the unified feed (F6.1): one row per thing a user did, with a JSON
--     snapshot to render it. Sessions, ratings and "watching now" of the same movie in a
--     10-minute window share one row (subject_type 'movie', subject_id = the movie);
--   * activity_loves holds the "amei" of any activity;
--   * conversations, messages and conversation_reads are the Sussurros (F6.5), only
--     between mutuals. A message with no sender is a system message and its body is a code;
--   * device_tokens and notification_prefs drive the push notifications (F6.7).

CREATE TABLE activities (
    id           BIGSERIAL PRIMARY KEY,
    actor_id     BIGINT      NOT NULL,
    type         VARCHAR(30) NOT NULL CONSTRAINT ck_activities_type CHECK (type IN (
        'registro_sessao', 'avaliacao', 'concluiu_filme', 'nova_estante', 'estante_destaque', 'nova_locadora',
        'entrou_maratona', 'concluiu_desafio', 'concluiu_maratona', 'entrou_cineclube', 'criou_maratona',
        'criou_cineclube', 'desbloquear_bichinho', 'assistindo_agora', 'importou_filmes')),
    visibility   VARCHAR(20) DEFAULT 'public' NOT NULL CONSTRAINT ck_activities_visibility CHECK (visibility IN ('public', 'mutual', 'secret')),
    subject_type VARCHAR(20) NOT NULL CONSTRAINT ck_activities_subject_type CHECK (subject_type IN (
        'movie', 'shelf', 'locadora', 'import', 'maratona', 'cineclube', 'bichinho')),
    subject_id   BIGINT      NOT NULL,
    -- The movie the activity is about, when there is one ("registros de quem eu sigo", F2.7).
    movie_id     BIGINT      NULL,
    -- JSON as TEXT, like import_jobs: {"movie":{...},"log":{...},"rating":{...}}, {"shelf":{...}}...
    payload      TEXT        NOT NULL,
    hidden       BOOLEAN     DEFAULT FALSE NOT NULL,
    created_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at   TIMESTAMP   DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_activities_actor_id__id FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_activities_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_activities_actor_created ON activities (actor_id, created_at DESC, id DESC);
CREATE INDEX idx_activities_created ON activities (created_at DESC, id DESC);
CREATE INDEX idx_activities_subject ON activities (subject_type, subject_id);
CREATE INDEX idx_activities_movie ON activities (movie_id, created_at DESC);

CREATE TABLE activity_loves (
    activity_id BIGINT    NOT NULL,
    user_id     BIGINT    NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT pk_activity_loves PRIMARY KEY (activity_id, user_id),
    CONSTRAINT fk_activity_loves_activity_id__id FOREIGN KEY (activity_id) REFERENCES activities (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_activity_loves_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE TABLE conversations (
    id              BIGSERIAL PRIMARY KEY,
    -- The pair is stored once, with the smaller users.id first.
    user_a          BIGINT    NOT NULL,
    user_b          BIGINT    NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    last_message_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_conversations_pair CHECK (user_a < user_b),
    CONSTRAINT uq_conversations_pair UNIQUE (user_a, user_b),
    CONSTRAINT fk_conversations_user_a__id FOREIGN KEY (user_a) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_conversations_user_b__id FOREIGN KEY (user_b) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_conversations_user_b ON conversations (user_b);

CREATE TABLE messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT        NOT NULL,
    -- Null for system messages (e.g. the follow between the two was undone).
    sender_id       BIGINT        NULL,
    body            VARCHAR(2000) NOT NULL CONSTRAINT ck_messages_body CHECK (char_length(body) >= 1),
    created_at      TIMESTAMP     DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_messages_conversation_id__id FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_messages_sender_id__id FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_messages_conversation ON messages (conversation_id, id DESC);

-- Read up to a message id rather than a timestamp: ids never tie.
CREATE TABLE conversation_reads (
    conversation_id      BIGINT NOT NULL,
    user_id              BIGINT NOT NULL,
    last_read_message_id BIGINT DEFAULT 0 NOT NULL,
    CONSTRAINT pk_conversation_reads PRIMARY KEY (conversation_id, user_id),
    CONSTRAINT fk_conversation_reads_conversation_id__id FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE ON UPDATE RESTRICT,
    CONSTRAINT fk_conversation_reads_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

CREATE TABLE device_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    platform   VARCHAR(10)  NOT NULL CONSTRAINT ck_device_tokens_platform CHECK (platform IN ('android', 'ios', 'web')),
    token      VARCHAR(500) NOT NULL,
    -- Language of the notifications (pt, en, es).
    locale     VARCHAR(10)  NULL,
    updated_at TIMESTAMP    DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT uq_device_tokens_token UNIQUE (token),
    CONSTRAINT fk_device_tokens_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);
CREATE INDEX idx_device_tokens_user ON device_tokens (user_id);

CREATE TABLE notification_prefs (
    user_id    BIGINT    PRIMARY KEY,
    -- JSON as TEXT: {"messages":true,"newFollowers":true,...}; a missing key is on.
    prefs      TEXT      NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT fk_notification_prefs_user_id__id FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE ON UPDATE RESTRICT
);

-- Backfill, so the feed does not start empty. Imported sessions, ratings and shelves are left
-- out (an import is one activity, not thousands); emotions of old sessions are not carried.

-- Sessions logged in the app, one activity each.
INSERT INTO activities (actor_id, type, visibility, subject_type, subject_id, movie_id, payload, created_at, updated_at)
SELECT w.user_id,
       CASE WHEN w.completed THEN 'concluiu_filme' ELSE 'registro_sessao' END,
       w.visibility, 'movie', w.movie_id, w.movie_id,
       json_build_object(
           'movie', json_build_object('id', m.id, 'tmdbId', m.tmdb_id, 'title', m.title, 'posterPath', m.poster_path),
           'log', json_build_object(
               'id', w.id, 'watchedOn', w.watched_on, 'cycle', w.cycle, 'mode', w.mode,
               'minutes', w.minutes_watched, 'percent', w.percent, 'completed', w.completed,
               'venue', w.venue, 'providerName', w.provider_name, 'emotions', json_build_array(),
               'gifUrl', w.gif_url, 'comment', w.comment, 'hasSpoiler', w.has_spoiler)
       )::text,
       w.created_at, w.created_at
FROM watch_logs w
JOIN movies m ON m.id = w.movie_id
WHERE w.import_source IS NULL;

-- Ratings given in the app; they are as visible as the user's Já assisti.
INSERT INTO activities (actor_id, type, visibility, subject_type, subject_id, movie_id, payload, created_at, updated_at)
SELECT um.user_id, 'avaliacao', COALESCE(s.visibility, 'public'), 'movie', um.movie_id, um.movie_id,
       json_build_object(
           'movie', json_build_object('id', m.id, 'tmdbId', m.tmdb_id, 'title', m.title, 'posterPath', m.poster_path),
           'rating', json_build_object(
               'id', um.id, 'stars', um.rating, 'sentiment', um.sentiment, 'review', um.review,
               'hasSpoiler', um.review_has_spoiler)
       )::text,
       um.rated_at, um.rated_at
FROM user_movies um
JOIN movies m ON m.id = um.movie_id
LEFT JOIN shelves s ON s.user_id = um.user_id AND s.system_type = 'watched'
WHERE um.rating IS NOT NULL AND um.rated_at IS NOT NULL AND um.source = 'manual';

INSERT INTO activities (actor_id, type, visibility, subject_type, subject_id, payload, created_at, updated_at)
SELECT s.user_id, 'nova_estante', s.visibility, 'shelf', s.id,
       json_build_object('shelf', json_build_object('id', s.id, 'name', s.name, 'color', s.color))::text,
       s.created_at, s.created_at
FROM shelves s
WHERE s.system_type IS NULL AND s.import_source IS NULL;

INSERT INTO activities (actor_id, type, visibility, subject_type, subject_id, payload, created_at, updated_at)
SELECT l.user_id, 'nova_locadora', l.visibility, 'locadora', l.id,
       json_build_object('locadora', json_build_object('id', l.id, 'name', l.name))::text,
       l.created_at, l.created_at
FROM locadoras l;
