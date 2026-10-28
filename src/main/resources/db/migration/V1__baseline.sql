-- Baseline: the schema Exposed's SchemaUtils.createMissingTablesAndColumns produced
-- before Flyway was adopted.
--
-- Idempotent on purpose. A legacy database (created by Exposed, without
-- flyway_schema_history) may be on any older app version, so this script runs on
-- every database: it creates what is missing and never touches existing data.
-- Columns that may have been added after a table was first created are re-added
-- with ADD COLUMN IF NOT EXISTS; unique constraints are matched by name through
-- CREATE UNIQUE INDEX IF NOT EXISTS.

-- users
CREATE TABLE IF NOT EXISTS users (id BIGSERIAL PRIMARY KEY, external_id VARCHAR(255) NOT NULL, username VARCHAR(255) NOT NULL, email VARCHAR(255) NULL, avatar_url VARCHAR(500) NULL, bio VARCHAR(500) NULL, password_hash VARCHAR(255) NULL, created_at TIMESTAMP NOT NULL);
ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255) NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS avatar_url VARCHAR(500) NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS bio VARCHAR(500) NULL;
ALTER TABLE users ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS users_external_id_unique ON users (external_id);
CREATE UNIQUE INDEX IF NOT EXISTS users_username_unique ON users (username);

-- movies
CREATE TABLE IF NOT EXISTS movies (id BIGSERIAL PRIMARY KEY, tmdb_id INT NULL, title VARCHAR(500) NOT NULL, local_title VARCHAR(500) NULL, original_title VARCHAR(500) NULL, "year" INT NULL, poster_path VARCHAR(500) NULL, vote_average REAL NULL, filmow_id VARCHAR(50) NULL, overview TEXT NULL, backdrop_path VARCHAR(500) NULL, release_date VARCHAR(20) NULL, runtime INT NULL, tagline VARCHAR(1000) NULL, letterboxd_id VARCHAR(100) NULL, enriched_at TIMESTAMP NULL, needs_enrichment BOOLEAN DEFAULT TRUE NOT NULL, imdb_url VARCHAR(500) NULL, director VARCHAR(255) NULL, trailer_key VARCHAR(20) NULL, filmow_genres TEXT NULL);
ALTER TABLE movies ADD COLUMN IF NOT EXISTS tmdb_id INT NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS local_title VARCHAR(500) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS original_title VARCHAR(500) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS "year" INT NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS poster_path VARCHAR(500) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS vote_average REAL NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS filmow_id VARCHAR(50) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS overview TEXT NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS backdrop_path VARCHAR(500) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS release_date VARCHAR(20) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS runtime INT NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS tagline VARCHAR(1000) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS letterboxd_id VARCHAR(100) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS enriched_at TIMESTAMP NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS needs_enrichment BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS imdb_url VARCHAR(500) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS director VARCHAR(255) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS trailer_key VARCHAR(20) NULL;
ALTER TABLE movies ADD COLUMN IF NOT EXISTS filmow_genres TEXT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS movies_tmdb_id_unique ON movies (tmdb_id);
CREATE INDEX IF NOT EXISTS idx_movies_filmow_id ON movies (filmow_id);
CREATE INDEX IF NOT EXISTS idx_movies_letterboxd_id ON movies (letterboxd_id);

-- genres
CREATE TABLE IF NOT EXISTS genres (id BIGSERIAL PRIMARY KEY, tmdb_id INT NOT NULL, "name" VARCHAR(100) NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS genres_tmdb_id_unique ON genres (tmdb_id);

-- movie_genres
CREATE TABLE IF NOT EXISTS movie_genres (id BIGSERIAL PRIMARY KEY, movie_id BIGINT NOT NULL, genre_id BIGINT NOT NULL, CONSTRAINT fk_movie_genres_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_movie_genres_genre_id__id FOREIGN KEY (genre_id) REFERENCES genres(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE UNIQUE INDEX IF NOT EXISTS uq_movie_genre ON movie_genres (movie_id, genre_id);

-- people
CREATE TABLE IF NOT EXISTS people (id BIGSERIAL PRIMARY KEY, tmdb_id INT NOT NULL, "name" VARCHAR(255) NOT NULL, profile_path VARCHAR(500) NULL, fetched_at TIMESTAMP NULL);
ALTER TABLE people ADD COLUMN IF NOT EXISTS profile_path VARCHAR(500) NULL;
ALTER TABLE people ADD COLUMN IF NOT EXISTS fetched_at TIMESTAMP NULL;
CREATE UNIQUE INDEX IF NOT EXISTS people_tmdb_id_unique ON people (tmdb_id);

-- movie_cast
CREATE TABLE IF NOT EXISTS movie_cast (id BIGSERIAL PRIMARY KEY, movie_id BIGINT NOT NULL, person_id BIGINT NOT NULL, "role" VARCHAR(50) NOT NULL, "character" VARCHAR(255) NULL, "position" INT DEFAULT 0 NOT NULL, fetched_at TIMESTAMP NULL, CONSTRAINT fk_movie_cast_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_movie_cast_person_id__id FOREIGN KEY (person_id) REFERENCES people(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
ALTER TABLE movie_cast ADD COLUMN IF NOT EXISTS "character" VARCHAR(255) NULL;
ALTER TABLE movie_cast ADD COLUMN IF NOT EXISTS "position" INT DEFAULT 0 NOT NULL;
ALTER TABLE movie_cast ADD COLUMN IF NOT EXISTS fetched_at TIMESTAMP NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_movie_person_role ON movie_cast (movie_id, person_id, "role");

-- movie_similars
CREATE TABLE IF NOT EXISTS movie_similars (id BIGSERIAL PRIMARY KEY, movie_id BIGINT NOT NULL, similar_tmdb_id INT NOT NULL, title VARCHAR(500) NOT NULL, poster_path VARCHAR(500) NULL, vote_average REAL NULL, release_date VARCHAR(20) NULL, fetched_at TIMESTAMP NOT NULL, CONSTRAINT fk_movie_similars_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
ALTER TABLE movie_similars ADD COLUMN IF NOT EXISTS poster_path VARCHAR(500) NULL;
ALTER TABLE movie_similars ADD COLUMN IF NOT EXISTS vote_average REAL NULL;
ALTER TABLE movie_similars ADD COLUMN IF NOT EXISTS release_date VARCHAR(20) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_movie_similar ON movie_similars (movie_id, similar_tmdb_id);

-- movie_watch_providers
CREATE TABLE IF NOT EXISTS movie_watch_providers (id BIGSERIAL PRIMARY KEY, movie_id BIGINT NOT NULL, provider_name VARCHAR(255) NOT NULL, logo_path VARCHAR(500) NULL, fetched_at TIMESTAMP NOT NULL, CONSTRAINT fk_movie_watch_providers_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
ALTER TABLE movie_watch_providers ADD COLUMN IF NOT EXISTS logo_path VARCHAR(500) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_movie_provider ON movie_watch_providers (movie_id, provider_name);

-- user_movies
CREATE TABLE IF NOT EXISTS user_movies (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, movie_id BIGINT NOT NULL, status VARCHAR(50) NOT NULL, rating REAL NULL, review TEXT NULL, rewatches INT DEFAULT 0 NOT NULL, watched_at TIMESTAMP NULL, is_favorite BOOLEAN DEFAULT FALSE NOT NULL, "source" VARCHAR(50) NOT NULL, imported_at TIMESTAMP NULL, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, CONSTRAINT fk_user_movies_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_user_movies_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
ALTER TABLE user_movies ADD COLUMN IF NOT EXISTS rating REAL NULL;
ALTER TABLE user_movies ADD COLUMN IF NOT EXISTS review TEXT NULL;
ALTER TABLE user_movies ADD COLUMN IF NOT EXISTS rewatches INT DEFAULT 0 NOT NULL;
ALTER TABLE user_movies ADD COLUMN IF NOT EXISTS watched_at TIMESTAMP NULL;
ALTER TABLE user_movies ADD COLUMN IF NOT EXISTS is_favorite BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE user_movies ADD COLUMN IF NOT EXISTS imported_at TIMESTAMP NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_movie_source ON user_movies (user_id, movie_id, "source");

-- user_follows
CREATE TABLE IF NOT EXISTS user_follows (id BIGSERIAL PRIMARY KEY, follower_id BIGINT NOT NULL, followed_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT fk_user_follows_follower_id__id FOREIGN KEY (follower_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_user_follows_followed_id__id FOREIGN KEY (followed_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE UNIQUE INDEX IF NOT EXISTS uq_follower_followed ON user_follows (follower_id, followed_id);

-- user_lists
CREATE TABLE IF NOT EXISTS user_lists (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, "name" VARCHAR(255) NOT NULL, description TEXT NULL, is_public BOOLEAN DEFAULT TRUE NOT NULL, color VARCHAR(7) NULL, visibility VARCHAR(20) DEFAULT 'public' NOT NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT fk_user_lists_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
ALTER TABLE user_lists ADD COLUMN IF NOT EXISTS description TEXT NULL;
ALTER TABLE user_lists ADD COLUMN IF NOT EXISTS is_public BOOLEAN DEFAULT TRUE NOT NULL;
ALTER TABLE user_lists ADD COLUMN IF NOT EXISTS color VARCHAR(7) NULL;
ALTER TABLE user_lists ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) DEFAULT 'public' NOT NULL;

-- user_list_items
CREATE TABLE IF NOT EXISTS user_list_items (id BIGSERIAL PRIMARY KEY, list_id BIGINT NOT NULL, movie_id BIGINT NOT NULL, "position" INT NOT NULL, added_at TIMESTAMP NOT NULL, CONSTRAINT fk_user_list_items_list_id__id FOREIGN KEY (list_id) REFERENCES user_lists(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_user_list_items_movie_id__id FOREIGN KEY (movie_id) REFERENCES movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE UNIQUE INDEX IF NOT EXISTS uq_list_movie ON user_list_items (list_id, movie_id);

-- tags
CREATE TABLE IF NOT EXISTS tags (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, "name" VARCHAR(255) NOT NULL, CONSTRAINT fk_tags_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_tag ON tags (user_id, "name");

-- movie_tags
CREATE TABLE IF NOT EXISTS movie_tags (id BIGSERIAL PRIMARY KEY, tag_id BIGINT NOT NULL, user_movie_id BIGINT NOT NULL, CONSTRAINT fk_movie_tags_tag_id__id FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_movie_tags_user_movie_id__id FOREIGN KEY (user_movie_id) REFERENCES user_movies(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE UNIQUE INDEX IF NOT EXISTS uq_tag_user_movie ON movie_tags (tag_id, user_movie_id);

-- articles
CREATE TABLE IF NOT EXISTS articles (id BIGSERIAL PRIMARY KEY, source_url VARCHAR(1000) NOT NULL, title VARCHAR(500) NOT NULL, summary TEXT NOT NULL, "content" TEXT NOT NULL, image_url VARCHAR(1000) NULL, "source" VARCHAR(100) NOT NULL, published_at TIMESTAMP NOT NULL, scraped_at TIMESTAMP NOT NULL);
ALTER TABLE articles ADD COLUMN IF NOT EXISTS image_url VARCHAR(1000) NULL;
CREATE UNIQUE INDEX IF NOT EXISTS articles_source_url_unique ON articles (source_url);

-- token_blocklist
CREATE TABLE IF NOT EXISTS token_blocklist (id BIGSERIAL PRIMARY KEY, token VARCHAR(1000) NOT NULL, expires_at BIGINT NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS token_blocklist_token_unique ON token_blocklist (token);

-- refresh_tokens
CREATE TABLE IF NOT EXISTS refresh_tokens (id BIGSERIAL PRIMARY KEY, user_external_id VARCHAR(255) NOT NULL, token_hash VARCHAR(64) NOT NULL, expires_at BIGINT NOT NULL, revoked_at BIGINT NULL, created_at TIMESTAMP NOT NULL);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS revoked_at BIGINT NULL;
CREATE INDEX IF NOT EXISTS refresh_tokens_user_external_id ON refresh_tokens (user_external_id);
CREATE UNIQUE INDEX IF NOT EXISTS refresh_tokens_token_hash_unique ON refresh_tokens (token_hash);

-- movie_likes
CREATE TABLE IF NOT EXISTS movie_likes (id BIGSERIAL PRIMARY KEY, user_external_id VARCHAR(255) NOT NULL, movie_id BIGINT NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE INDEX IF NOT EXISTS movie_likes_user_external_id ON movie_likes (user_external_id);
CREATE INDEX IF NOT EXISTS movie_likes_movie_id ON movie_likes (movie_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_movie_like ON movie_likes (user_external_id, movie_id);

-- review_likes
CREATE TABLE IF NOT EXISTS review_likes (id BIGSERIAL PRIMARY KEY, user_external_id VARCHAR(255) NOT NULL, review_id VARCHAR(255) NOT NULL, created_at TIMESTAMP NOT NULL);
CREATE INDEX IF NOT EXISTS review_likes_user_external_id ON review_likes (user_external_id);
CREATE INDEX IF NOT EXISTS review_likes_review_id ON review_likes (review_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_review_like ON review_likes (user_external_id, review_id);

-- locadoras
CREATE TABLE IF NOT EXISTS locadoras (id BIGSERIAL PRIMARY KEY, user_id BIGINT NOT NULL, "name" VARCHAR(255) NOT NULL, description TEXT NULL, visibility VARCHAR(20) DEFAULT 'public' NOT NULL, created_at TIMESTAMP NOT NULL, CONSTRAINT fk_locadoras_user_id__id FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
ALTER TABLE locadoras ADD COLUMN IF NOT EXISTS description TEXT NULL;
ALTER TABLE locadoras ADD COLUMN IF NOT EXISTS visibility VARCHAR(20) DEFAULT 'public' NOT NULL;

-- locadora_shelves
CREATE TABLE IF NOT EXISTS locadora_shelves (id BIGSERIAL PRIMARY KEY, locadora_id BIGINT NOT NULL, shelf_id BIGINT NOT NULL, "position" INT NOT NULL, added_at TIMESTAMP NOT NULL, CONSTRAINT fk_locadora_shelves_locadora_id__id FOREIGN KEY (locadora_id) REFERENCES locadoras(id) ON DELETE RESTRICT ON UPDATE RESTRICT, CONSTRAINT fk_locadora_shelves_shelf_id__id FOREIGN KEY (shelf_id) REFERENCES user_lists(id) ON DELETE RESTRICT ON UPDATE RESTRICT);
CREATE UNIQUE INDEX IF NOT EXISTS uq_locadora_shelf ON locadora_shelves (locadora_id, shelf_id);
