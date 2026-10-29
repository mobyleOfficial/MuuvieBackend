# MoovieBackend

Backend API for the Moovie app, powered by [TMDB](https://www.themoviedb.org/). Built with Kotlin, Ktor, and Koin.

## Tech Stack

- **Kotlin** 2.0.20
- **Ktor** 2.3.0 (Server + Client)
- **Koin** 3.5.6 (Dependency Injection)
- **Kotlinx Serialization** (JSON)
- **Netty** (HTTP engine)
- **Exposed** (ORM)
- **PostgreSQL** 16 + **HikariCP** (connection pool)
- **Java** 17+

## Prerequisites

- JDK 17 or higher
- [Docker](https://www.docker.com/products/docker-desktop/) (for the database)
- A [TMDB API](https://developer.themoviedb.org/) Bearer token

## Environment Variables

| Variable            | Required | Default                  | Description                                      |
|---------------------|----------|--------------------------|--------------------------------------------------|
| `TMDB_API_KEY`      | Yes      | —                        | TMDB API Bearer token                            |
| `DATABASE_URL`      | Yes      | —                        | PostgreSQL connection string                     |
| `PORT`              | No       | `8080`                   | Server port                                      |
| `CORS_ORIGINS`      | No       | —                        | Comma-separated list of allowed origins for CORS |
| `POSTGRES_USER`     | No       | `moovie`                 | Docker Compose: database user                    |
| `POSTGRES_PASSWORD` | No       | —                        | Docker Compose: database password                |
| `POSTGRES_DB`       | No       | `moovie`                 | Docker Compose: database name                    |
| `DB_PORT`           | No       | `5433`                   | Docker Compose: host port for PostgreSQL         |
| `TENOR_API_KEY`     | No       | —                        | Tenor API v2 key for `/gifs/search` (Google Cloud console: enable the Tenor API and create an API key). Without it, or when Tenor fails, the search answers 503 `gif_search_unavailable` |
| `DEEPL_API_KEY`     | No       | —                        | DeepL API key (free keys end in `:fx` and use `api-free.deepl.com`). Translates into Portuguese, once per movie during enrichment, the overviews TMDB only has in another language. Without it, or when DeepL fails, the overview stays in that language with `overviewLanguage` |
| `FCM_SERVICE_ACCOUNT` | No     | —                        | Push notifications (F6.7): the JSON key of a Firebase service account with the "Firebase Cloud Messaging API Admin" role, as one line. Without it (or `FCM_SERVICE_ACCOUNT_FILE`), push is off and the WebSocket still delivers in real time |
| `FCM_SERVICE_ACCOUNT_FILE` | No | —                        | Path to that JSON key, instead of `FCM_SERVICE_ACCOUNT` |
| `JWT_SECRET`        | Yes      | —                        | Signs the access tokens; the server does not start without it (`openssl rand -base64 32`) |
| `JWT_EXPIRY_SECONDS` | No      | `86400`                  | Lifetime of an access token |
| `JWT_ISSUER`        | No       | `moovie-backend`         | Issuer of the access tokens |
| `REFRESH_TOKEN_EXPIRY_DAYS` | No | `30`                   | Lifetime of a refresh token |
| `SCRAPE_INTERVAL_HOURS` | No   | `3`                      | How often the news are scraped |
| `OAUTH_CLIENT_ID`, `OAUTH_CLIENT_SECRET`, `OAUTH_PROVIDER_URL`, `OAUTH_REDIRECT_URI` | No | — | OAuth login; off without them |
| `METRICS_TOKEN`     | No       | —                        | Turns on `GET /metrics` (Prometheus) behind `Authorization: Bearer <token>`; without it the route is a 404 |

## Getting Started

### 1. Configure environment

```bash
cp .env.example .env
```

Edit `.env` and fill in your values (TMDB token, database password, etc.).

### 2. Start the database

```bash
docker compose up -d
```

This starts a PostgreSQL 16 container on port 5433 (configurable via `DB_PORT`).

Verify it's running:

```bash
docker exec -it moovie-db psql -U moovie -d moovie -c "SELECT 1"
```

### 3. Run the backend

```bash
./gradlew run
```

The server starts at `http://localhost:8080`. On startup, Flyway applies the versioned migrations in `src/main/resources/db/migration/`.

### Database migrations

- Every schema change is a new `V<n>__<description>.sql` file. Never edit a migration that has already been applied.
- `V1__baseline.sql` is idempotent: databases created before Flyway (by Exposed) are baselined at version 0 and get whatever they are missing.
- Migration tests run against a real embedded PostgreSQL (no Docker needed): `./gradlew test --tests "org.mobyle.data.local.database.*"`.

### Useful Docker commands

| Action         | Command                  |
|----------------|--------------------------|
| Start database | `docker compose up -d`   |
| Stop database  | `docker compose down`    |
| View logs      | `docker compose logs db` |
| Reset database | `docker compose down -v` (deletes all data) |

### Build fat JAR

```bash
./gradlew buildFatJar
java -jar build/libs/MoovieBackend-1.0-SNAPSHOT-all.jar
```

### Docker

```bash
docker build -t moovie-backend .
docker run -p 8080:8080 \
  -e TMDB_API_KEY="your_tmdb_bearer_token" \
  moovie-backend
```

## API Endpoints

All routes are served under `/api/v1` (contract: `research/decisions/api-v1-contract.md` in the MuuvieAi meta-repo); the paths below are relative to it. There is no unprefixed alias. Only `/health/*` and `/metrics` live outside the prefix.

### Movies

| Method | Endpoint                      | Description              | Query Params                                                                                                        |
|--------|-------------------------------|--------------------------|---------------------------------------------------------------------------------------------------------------------|
| GET    | `/movies/trending`            | Trending movies          | `page`                                                                                                              |
| GET    | `/movies/search`              | Search movies            | `query` (required), `page`                                                                                          |
| GET    | `/movies/discover`            | Discover with filters    | `page`, `year`, `release_date_gte`, `release_date_lte`, `sort_by`, `with_genres`, `with_original_language`, `with_origin_country`, `vote_count_gte` |
| GET    | `/movies/genres`              | List genres              | —                                                                                                                   |
| GET    | `/movies/countries`           | List countries; `name` is localized by TMDB (pt-BR), `englishName` kept | —                                                                    |
| GET    | `/movies/languages`           | List languages; `name` is the endonym ("Français"), `englishName` kept | —                                                                      |

With a token, `/movies/trending`, `/movies/search`, `/movies/discover` and the `/discover/movies/*` rankings carry `me: { status, rating }` on each movie the viewer has on Já assisti / Quero ver or rated; the field is absent otherwise.
| GET    | `/movies/{id}`                | Movie details (TMDB id), without the TMDB score. With a token it carries `me`: shelves, situation (`watched`/`want_to_watch`), progress, rating. Without a pt-BR overview on TMDB, another Portuguese one or else the English one fills it, with `overviewLanguage` (ISO 639-1, e.g. `en`; absent for Portuguese), until the enrichment translates it with DeepL: then `overviewTranslated` is true. An official pt-BR overview, or Filmow's, replaces a translated one | —                     |
| GET    | `/movies`                     | Movie details by `id` (database), `tmdbId` or `filmowId`. An imported movie without TMDB id is resolved and enriched on the spot; `matchStatus` is `matched`, `pending` or `unmatched` (only the source's data) | `id`, `tmdbId`, `filmowId` |
| GET    | `/persons/{id}`, `/persons/tmdb/{tmdbId}` | Person with the catalog movies they are in (database or TMDB id) | —                                                                                                  |

### Profile

| Method | Endpoint              | Description          | Body           |
|--------|-----------------------|----------------------|----------------|
| GET    | `/profile`            | Get current profile  | —              |
| PUT    | `/profile`            | Update profile       | `UserProfile`  |

### Shelves

Every account has two system shelves, `watched` ("Já assisti") and `watchlist` ("Quero ver"): they cannot be renamed or deleted, and a movie is never on both. Adding a movie to one of them sets the user's status for it; removing it from "Já assisti" unmarks it as watched. Visibility is `public`, `mutual` (users who follow each other) or `secret`; a shelf the caller cannot see answers 404. Reads accept an optional token; writes require one. Errors use the `{ error, message, details? }` envelope.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | `/users/{userId}/shelves` | Shelves visible to the caller, pinned first. `me` = caller. `page`, `limit`, `profile=true` (the profile tab: leaves out the shelves a locadora hides from the profile, for the owner too) |
| PUT    | `/users/me/shelves/order` | `{ shelfIds, pinnedIds? }` |
| POST   | `/shelves` | Create `{ name, description?, color?, visibility? }` |
| GET    | `/shelves/search` | Search my shelves: `q`, `page`, `limit` |
| GET/PUT/DELETE | `/shelves/{id}` | Detail with `totalMovies`/`totalWatched`, `owner`, `saves` and `saved`; edit; delete (403 `system_shelf_rename` / `system_shelf_delete`). A GET with a token from someone other than the owner counts as a visit |
| POST/DELETE | `/shelves/{id}/save` | Save / unsave a shelf of someone else; idempotent, 204. My own shelf or Já assisti / Quero ver is 400 `validation_failed` |
| GET    | `/users/me/shelves/saved` | Shelves I saved and can still see, the last saved first. `page`, `limit` |
| GET    | `/shelves/{id}/movies` | `page`, `limit`, `watched`, `genre` (TMDB id), `decade` (e.g. 1980), `rating` (a label) |
| POST   | `/shelves/{id}/movies` | Add `{ movieId }` or `{ tmdbId, title }` |
| DELETE | `/shelves/{id}/movies/{movieId}` | Remove |
| POST   | `/shelves/{id}/movies/transfer` | `{ targetShelfId, mode: move\|copy, movieIds? }` (all when empty) |
| PUT    | `/shelves/{id}/order` | `{ sortMode, applyOnce? }` or `{ movieIds }` (manual order) |
| GET/POST | `/movies/{id}/shelves`, `/movies/tmdb/{tmdbId}/shelves` | My shelves holding the movie; POST `{ shelfIds, title? }` adds it to all of them |
| GET    | `/movies/{id}/shelves/public` | Public. Public custom shelves of anyone holding the movie, last updated first: `{ shelf, owner }`. `page`, `limit` |

### Locadoras

A locadora groups shelves of its owner. Deleting it keeps the shelves. Its visibility follows the shelf rules, and inside a visible locadora each shelf keeps its own visibility: a secret shelf in a public locadora stays hidden from others, in the list and in the counters (`totalShelves`, `totalMovies`, `totalWatched`). With `hideShelvesInProfile`, other users no longer see its shelves in `GET /users/{id}/shelves`, and nobody sees them there with `profile=true` nor in the profile shelf counter. Name 1–150 characters, description up to 150.

| Method | Path | Description |
|--------|------|-------------|
| GET    | `/users/{userId}/locadoras` | Locadoras visible to the caller, pinned first. `me` = caller. `page`, `limit` |
| PUT    | `/users/me/locadoras/order` | `{ locadoraIds, pinnedIds? }` |
| POST   | `/locadoras` | Create `{ name, description?, visibility?, hideShelvesInProfile?, hideShelvesInFilters? }` |
| GET/PUT/DELETE | `/locadoras/{id}` | Detail with owner and progress; edit (same body); delete (403 `not_locadora_owner` for others) |
| GET    | `/locadoras/{id}/shelves` | Shelves in the locadora order, each with its first 10 movies. `page`, `limit` |
| PUT    | `/locadoras/{id}/shelves` | `{ shelfIds }` replaces the set (only my shelves, up to 200). Shelves already in keep their order |
| PUT    | `/locadoras/{id}/shelves/order` | `{ shelfIds }`, all already in the locadora |

### Watch sessions, rating and comments

A watch log is one session: `mode` `watched` (the whole movie), `minutes` or `percent`. Minutes cannot pass the TMDB runtime (600 when unknown). A completed session puts the movie on "Já assisti" and takes it off "Quero ver"; deleting a session recomputes the progress but keeps the movie on "Já assisti". A rewatch opens a new `cycle`. The rating is one label per movie: `favorite`, `loved`, `liked`, `neutral`, `not_for_me`, `abandoned` or `on_hold` (in that order for sorting). It never moves the movie between shelves; `abandoned` stops "assistindo agora". `{id}` is the movie's database id (the `id` of `GET /movies/{tmdbId}`).

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST   | `/movies/{id}/logs` | Log a session `{ watchedOn?, mode?, minutesWatched?, percent?, venue?, providerName?, emotions?, gif?, comment?, hasSpoiler?, visibility? }`. `{}` is the one-tap "Já assisti" |
| POST   | `/movies/tmdb/{tmdbId}/logs` | Same, for a movie known only on TMDB: `{ title?, log }` |
| GET    | `/movies/{id}/logs` | `{ summary, logs }` of `userId` (default `me`), visible to the caller. `page`, `limit` |
| PUT/DELETE | `/logs/{id}` | Edit / delete a session (owner only) |
| POST   | `/movies/{id}/rewatch` | Open the next cycle; 409 `cycle_not_completed` while the current one is unfinished |
| PUT/DELETE | `/movies/{id}/rating` | `{ rating, review?, hasSpoiler?, emotions?, createLog?, visibility? }` → `{ id, rating, review, hasSpoiler, ratedAt }`; `createLog` adds a 100% session (with the emotions and visibility) and is 400 with `abandoned` or `on_hold`; DELETE removes it |
| GET    | `/movies/{id}/me` | The caller's state for the movie (same as `me` in the detail) |
| GET    | `/movies/{id}/community` | Public. `{ ratings: { label: count }, watched, wantToWatch, shelves, emotions: [{ emotion, count }] }`; only public Já assisti (ratings, watched), Quero ver, custom shelves and sessions count. `emotions` is the full distribution of the public sessions, most frequent first (ties by emotion id) |
| GET    | `/movies/{id}/events` | Public. Open maratonas the movie completes a challenge of (with the challenges) and open CineClubes that screen it |
| GET    | `/movies/{id}/cineclubes` | Public. Page of every CineClube with a session of the movie, finished included, cancelled excluded; same rows as `/cineclubes` (with a token, `joined`). `page`, `limit` |
| GET    | `/movies/{id}/maratonas` | Public. Page of the maratonas (upcoming, active or finished; not cancelled) where the movie completed a challenge for someone; same rows and order as `/maratonas` (with a token, `joined`). `page`, `limit` |
| GET    | `/movies/{id}/timeline` | Public. `{ months: [{ month: "YYYY-MM", sessions, ratings }] }`, oldest first, for the last `months` (default 12, 1–24, else 400) months including the current one, zeros included. `sessions` counts public sessions by watched day; `ratings` counts ratings given that month by people whose Já assisti is public |
| PUT    | `/users/me/watching-now` | `{ movieId }`, or `{}` to clear |
| GET    | `/users/{userId}/watching-now` | Movie and progress, or 204 |
| GET    | `/users/me/emotions/recent`, `/users/me/gifs/recent` | Last picks for the pickers |
| GET    | `/gifs/search` | `q`, `cursor` → `{ results, nextCursor }` (Tenor proxy) |
| GET/POST | `/comments` | GET `targetType` (`watch_log`\|`rating`), `targetId`, `page`; POST `{ targetType, targetId, parentId?, body, hasSpoiler? }`. A target the caller cannot see is 404 |
| DELETE | `/comments/{id}` | Author or target owner |

### Imports

An import is a background job: `pending → reading → preview → importing → done` (or `failed` / `cancelled`). Nothing is written before the preview is confirmed. Reimporting is idempotent: watched movies go to "Já assisti", the watchlist to "Quero ver", favorites get the `favorite` label, the stars become a label (4.5–5 loved, 3.5–4 liked, 2.5–3 neutral, below not_for_me), dated sessions become watch logs and lists become shelves (matched by source id, so a reimport only adds movies). A movie the user already has in the Muuvie is left as it is. Each change is pushed on the WebSocket as `import_progress` with `{ jobId, source, status, processed, total }`. Only Filmow is available; `letterboxd` answers 503 `import_source_unavailable`. Filmow cookies are used to read the profile and never stored.

Imported movies become catalog movies of our own and are linked to TMDB in a cascade: the IMDb link, then an exact title and year, then a title confirmed by the director (localized, English, original and TMDB alternative titles, year ±1). Linking runs when the user opens the movie, right after the import for its movies, and in the background enrichment loop; a TMDB error retries with backoff (2 min doubling up to a day) and a movie nothing matches with confidence becomes `unmatched` and leaves the queue (`movies.match_status`, `match_method`, V19). The scraper skips the Filmow detail page of movies the catalog already resolves, unless it needs the user's watched date. See `research/features/filmow-movie-enrichment/spec.md` in the meta-repo.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST   | `/imports` | `{ source: filmow, username, cookies? }` → 202 with the job; 409 `import_in_progress` |
| GET    | `/imports` | History, newest first. `page`, `limit` |
| GET    | `/imports/{id}` | Status, progress, `preview` (counts and lists found) and the final `counts` |
| POST   | `/imports/{id}/confirm` | `{ listsToImport?, defaultVisibility? }` → 202; 409 `import_not_in_preview` |
| POST   | `/imports/{id}/cancel` | Before writing only; 409 `import_not_cancellable` |
| GET    | `/imports/{id}/unmatched` | Movies TMDB did not match with confidence, with up to 3 candidates. `page`, `limit` |
| POST   | `/imports/{id}/unmatched/{itemId}` | `{ tmdbId, title? }` or `{ skip: true }`; 409 `already_resolved` |

### Profiles, highlights and discovery

`{userId}` takes a user id or a handle with the @ (`/users/@cinefila`). The handle follows `^[a-z0-9_.]{5,30}$` and is unique; accounts get their username as the handle when it already fits. `stats` only count what the caller can see. Avatar and banner are URLs (there is no upload yet, B0.8).

| Method | Path | Description |
|--------|------|-------------|
| GET/PUT | `/users/me` | Own profile with `stats` and `isScraping`. PUT `{ displayName?, handle?, pronouns?, bio?, links?, accentColor?, avatarUrl?, bannerUrl? }` replaces the fields (a missing `handle` keeps the current one); 409 `handle_taken` |
| GET    | `/handles/{handle}/available` | `{ handle, available, reason? }`, reason `invalid` or `taken`. The caller's own handle is available |
| GET    | `/users/{userId}` | Profile with `isFollowing`, `followsMe`, `mutual` and `self` |
| GET    | `/users/{userId}/stats` | Shelves, locadoras, sessions, watched, maratonas, participations, cineclubes, followers, following |
| GET    | `/users/{userId}/logs` | Every visible session, newest first, each with its movie. `me` = caller. `page`, `limit` |
| GET    | `/users/{userId}/highlights` | Enabled highlights the caller can open. `/users/me/highlights` returns all of mine |
| PUT    | `/users/me/highlights` | `{ highlights: [{ type, enabled, movieIds? \| shelfId? \| locadoraId? \| soundtrackUrl? }] }` replaces them in this order. One per type: `top4` (1–4 movie ids), `shelf` and `locadora` (mine), `soundtrack` (`https://open.spotify.com/...` or `https://music.youtube.com/...`) |
| GET    | `/discover/shelves` | "Estantes mais populares": public shelves of other users with movies, by `3 × saves + 2 × people who loved a public activity about the shelf + visitors in the last 30 days + the owner's followers` (the owner never counts), then movies added in the last 30 days. `q`, `page`, `limit` |
| GET    | `/discover/locadoras` | Public locadoras of other users with a public shelf, curated owners first, then activity in the last 30 days. `q`, `page`, `limit` |
| GET    | `/discover/profiles` | Curated first, then the most active. `q` (username, @handle or display name), `page`, `limit` |
| GET    | `/discover/movies/trending` | "Em alta no Muuvie": movies by distinct people with a public session in the last `days` (default 7, 1–30, else 400). `page`, `limit` |
| GET    | `/discover/movies/loved` | "Mais amados no Muuvie": movies by Favoritado + Adorei labels of people whose Já assisti is public, at least 2. `page`, `limit` |

Set `users.featured = true` to curate a profile; its locadoras also come first.

### Feed, follows, Sussurros and push

The feed (B6) is made of activities that the app's actions publish: sessions (`registro_sessao`, `concluiu_filme`), ratings (`avaliacao`), new shelves and locadoras, the featured shelf, "assistindo agora", imports, the maratonas (`criou_maratona`, `entrou_maratona`, `concluiu_desafio`, `concluiu_maratona`, with a `maratona` block) and the CineClubes (`criou_cineclube`, `entrou_cineclube`, with a `cineclube` block). Sessions and the rating of one movie in 10 minutes share one activity whose `payload` has the `movie`, `log` and `rating` blocks; it is as visible as its most private part. Editing or deleting the session, shelf or locadora updates or removes the activity. Each activity carries `actor`, `type`, `visibility`, `payload`, `loves`, `loved`, `self`, `mutual` and `hidden`.

Lists of the feed and of the Sussurros use the cursor envelope `{ results, nextCursor }` (`cursor`, `limit` up to 50). Visibility follows the shared policy: everything of mine, public and `mutual` content of mutuals, public content of anyone else; hidden activities only reach their author.

| Method | Path | Description |
|--------|------|-------------|
| GET    | `/feed` | `scope=all` (me and who I follow, default), `following` or `me` (includes my hidden ones) |
| GET    | `/users/{userId}/activities` | A profile's activities as the caller may see them. `me` = caller |
| GET    | `/movies/{movieId}/activities` | Activities about a movie (database id). `scope`: `following` (default) or `me` need login; `all` (login optional) is everything the caller may see. `type`: `ratings` (anything with a rating block) or `updates` (sessions and "assistindo agora"); none is both |
| PUT/DELETE | `/activities/{id}/love` | "Amei"; answers `{ loves, loved }`. The author is told (WebSocket + push) |
| PUT    | `/activities/{id}/hidden` | `{ hidden }`; only the author (403 `not_activity_owner`) |
| POST/DELETE | `/users/{userId}/follow` | Idempotent; answers `{ following, followedBy, mutual }`. 429 `rate_limited` above 10 follows a minute |
| GET    | `/users/{userId}/follow-status` | `{ following, followedBy, mutual }` |
| GET    | `/users/{userId}/followers`, `/users/{userId}/following` | Page of `{ user, isFollowing, followsMe, mutual, self, followedAt }`, newest follow first. `/users/me/followers/recent` = my followers |
| GET    | `/conversations`, `/conversations/{id}` | Conversations with `other`, `enabled` (only while mutuals), `lastMessage` and `unread` |
| GET    | `/conversations/{id}/messages` | Newest first. System messages have no `senderId` and their `body` is a code: `mutual_lost`, `mutual_restored` |
| POST   | `/users/{userId}/messages` | `{ body }` (1–2000 characters); creates the conversation. 403 `not_mutual`, 429 `rate_limited` above 30 a minute |
| GET    | `/users/{userId}/conversation` | The conversation with a user; 404 before the first message |
| PUT    | `/conversations/{id}/read` | Marks it read; answers the new `{ conversations, messages }` badge |
| GET    | `/users/me/unread-count` | `{ conversations, messages }` |
| PUT/DELETE | `/users/me/devices` | `{ platform: android\|ios\|web, token, locale? }` registers a push token; DELETE takes `{ token }` or `?token=` |
| GET/PUT | `/users/me/notification-prefs` | `{ messages, newFollowers, loves, maratonas, cineclubes }`, all on by default |

On the WebSocket (`/api/v1/ws`, ticket from `POST /api/v1/ws/token`), the user gets `message` (`conversationId`, `messageId`, `senderId`, `name`, `body`, `system`, `createdAt`), `unread` (`conversations`, `messages`), `new_follower` (`userId`, `name`) and `activity_loved` (`activityId`, `userId`, `name`). Push notifications (FCM, Android channel `social`) carry the same fields plus `type`, in the device's language (pt, en or es).

### Maratonas

A maratona (B7) has a period, categories of challenges, levels (`minChallenges` done challenges complete it at that level), teams and prizes. Its `status` is `upcoming` (open to join, before `startsOn`), `active`, `finished` or `cancelled`; a scheduler moves it along the period every hour and gives a `concluiu_maratona` activity to whoever reached their level. A challenge with `criteria` is completed by itself when a completed session of a matching movie is logged within the period (imports never count); any challenge can also be marked by hand. Joining creates the shelf "Fila: <title>"; leaving deletes it and the challenge links.

Criteria are `{ "all": [ { field, op, value } ] }` and every rule must match: `genre` (in, TMDB ids), `release_year` (between, `[from, to]`), `decade` (in, `[1970]`), `country` (in, ISO 3166-1), `original_language` (in, ISO 639-1), `director` and `cast` (in, TMDB person ids), `runtime_min` (gte), `runtime_max` (lte), `keyword` (in, TMDB ids) and `vote_average_min` (gte). The movie enrichment saves the original language, production countries and keywords these read; migration V11 queues the enriched movies again to fetch them.

| Method | Path | Description |
|--------|------|-------------|
| GET    | `/maratonas` | Public. `q`, `open` (no password), `includeFinished`, `trusted`, `mine` (owner or co-owner, needs login, any status), `participant` (a user id, or `me`: the maratonas they joined, any status but cancelled), `page`, `limit`. Rows carry `joined` |
| GET    | `/maratonas/{slug}` | Public. Detail with categories, challenges, levels, teams, prizes, `owner`, `coOwners`; with a token, `me` (level, team, `queueShelfId`, `completed`) and `canEdit` |
| GET    | `/maratonas/slug-available?slug=` | `{ slug, valid, available }` |
| POST   | `/maratonas` | Creates it (201). Slug 3–50 of `a-z 0-9 -`, 1–20 categories, 3–200 challenges, up to 10 levels (none = one level asking for every challenge), 20 teams, 20 prizes, `password` 4–72. 409 `slug_taken` |
| PUT    | `/maratonas/{slug}` | Owners and co-owners (403 `not_maratona_editor`). Before the start everything but the slug; after it only texts, logo, color, link and prizes. Items with an `id` are kept. 409 `maratona_closed` once finished or cancelled |
| POST   | `/maratonas/{slug}/cancel` | Only the creator (403 `not_maratona_owner`); the maratona becomes read only |
| PUT    | `/maratonas/{slug}/owners` | `{ userIds }` co-owners, up to 10; only the creator |
| POST/DELETE | `/maratonas/{slug}/participation` | Join `{ levelId?, teamId?, password? }` (403 `wrong_password`, 409 `already_participating` / `maratona_closed`) or leave |
| GET    | `/maratonas/{slug}/challenges?teamOnly=` | Categories with challenges and, for the caller, `completed` and the tied `links` |
| GET    | `/maratonas/{slug}/challenges/{id}/suggestions?page=` | Movies that fit the criteria (TMDB discover); empty for a challenge without criteria |
| PUT/DELETE | `/maratonas/{slug}/challenges/{id}/links/{movieId}` | Ties a movie (database id) to a challenge by hand or unties it; only participants, only while `active` (409 `maratona_not_active`) |
| GET    | `/maratonas/{slug}/ranking?by=user\|team` | Page of participants (`position`, `user`, `teamId`, `completed`, `lastCompletedAt`) or of teams. More challenges first, then who got there earlier; a tie shares the position |
| GET    | `/maratonas/{slug}/progress?cursor=` | Cursor of the maratona's activities: created, joined, challenge done, finished |
| GET    | `/maratona-templates` | Ready-made maratonas (F7.9) with their challenges and length in days |
| POST   | `/maratonas/from-template/{id}` | `{ slug, startsOn, title? }` creates a maratona from a template |

The profile counters (`GET /users/{id}/stats`) count `maratonas` created (not cancelled), `participations` in maratonas and `cineclubes` the user takes part in (not cancelled).

### Charts, retrospective and yearly goals

The charts (B9) are computed from the user's sessions of a year, counting only the sessions the viewer can see (public, mutual for mutuals, everything for the owner). `movies` are the distinct movies finished in the year; `minutes` add every session, finished or not. Ratings only show when the viewer can see the owner's Já assisti. `{userId}` takes a user id, an `@handle` or `me`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | `/users/{userId}/stats/years` | `{ years }` with visible sessions, newest first |
| GET    | `/users/{userId}/stats/{year}` | `movies`, `sessions`, `minutes`, `rewatches`, `months[12]` and the breakdowns `genres`, `decades`, `countries`, `languages`, `venues`, `directors`, `emotions` and `ratings` (each `{ key, label?, movies, minutes }`, largest first; `ratings` in the order of the labels) |
| GET    | `/users/{userId}/stats/{year}/calendar` | Days with sessions: `{ date, sessions, minutes }` (heatmap) |
| GET    | `/users/me/retrospectives` | Years with finished movies: `{ year, movies }` |
| GET    | `/users/me/retrospectives/{year}` | The stories: totals, `topGenres`, `topDirectors`, `topActors`, `emotion`, `favorite` and `favoriteRating` (the best label of the year), `maratonasFinished`, `cineclubes`, `bestMonth`, `goal`. 404 without finished movies |
| GET    | `/users/{userId}/goals/{year}` | `{ year, targetMovies, watched }`; 404 without a goal |
| PUT/DELETE | `/users/me/goals/{year}` | `{ targetMovies }` (1–5000), only for this year or the next |

### Bichinho, missions and Caderninho

The bichinho (B10) lives in the shelter: first the user picks one of the three starters (`pipoca-doce`, `pipoca-salgada`, `pipoca-caramelo`) for good, then the others unlock by what they watch (movies, minutes, horror or sci-fi movies, movies before 1970, a CineClube joined, a maratona finished, missions done). The catalog and its rules are in `PetCatalog`. The pinned bichinho gets the XP of each session the user logs (20 for a finished movie plus 1 per 10 minutes, up to 30); imported sessions give nothing. Level *n* needs 50 × *n* × (*n* − 1) XP, up to 50. Unlocking publishes `desbloquear_bichinho` with a `bichinho` block.

Each week (from Monday) has three missions, the same for everyone, picked from `MissionCatalog`; their progress comes from the week's own sessions, and a mission gives its XP once when it is reached.

The Caderninho is private. Reading a day never creates it; saving creates it, and saving it with no mood, note or goals deletes it. A day has up to 6 goals (`movie`, `short`, `series`, `study`, `other`), optionally tied to a movie.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | `/users/me/pets` | The shelter: `starterChosen`, `pets` (locked ones with their `rule` and `progress`; unlocked ones with `xp`, `level`, `levelXp`, `nextLevelXp`, `starter`, `pinned`) and `pinned` |
| POST   | `/users/me/pets/starter` | `{ slug }` one of the starters (201). 409 `starter_chosen` the second time |
| PUT    | `/users/me/pets/{slug}/pin` | Pins an unlocked bichinho (404 otherwise) |
| GET    | `/users/{userId}/pets` | Public: the unlocked bichinhos of a profile |
| GET    | `/users/me/missions` | `{ weekStart, weekEnd, missions[] }` with `type`, `target`, `progress`, `xp`, `completed` |
| GET    | `/users/me/diary/{date}` | The day, or an empty one with `saved: false` |
| PUT    | `/users/me/diary/{date}` | `{ mood?, note?, tasks[] }`; mood `great`, `good`, `okay`, `bad` or `awful` |
| GET    | `/users/me/diary?month=YYYY-MM` | The days written in the month: `date`, `mood`, `tasks`, `done`, `hasNote` |
| GET    | `/users/me/diary/summary/{year}` | Moods per month and the `topMood` of the year |

### Watch parties and the poster game

A watch party (B10.4) is a room in memory, gone after a restart or six hours: the host gets a six-letter `code`, friends join with it, chat, and the host starts a countdown (5 to 3600 seconds) so everybody presses play at `startAt`, a time of the server (`serverTime` lets the app correct its clock). Every change sends a `watch_party` event `{ code, event }` (`join`, `leave`, `message`, `start`, `closed`) on the WebSocket to the other members, who read the room again; it is never pushed. One backend instance only: with more, the rooms move to Redis.

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST   | `/watch-parties` | `{ title?, movieId? }` creates a room (201); a title or a movie is needed |
| GET    | `/watch-parties/{code}` | Members only (403 `not_in_party`): host, movie, members, last 100 messages, `startAt` |
| POST   | `/watch-parties/{code}/members` | Joins (up to 30, 409 `party_full`) |
| DELETE | `/watch-parties/{code}/members/me` | Leaves; the host leaving closes the room |
| POST   | `/watch-parties/{code}/start` | `{ inSeconds }`, host only (403 `not_party_host`) |
| POST   | `/watch-parties/{code}/messages` | `{ body }` up to 500 characters |
| GET    | `/games/poster/round` | Public. `{ movie, options[4] }` from what is trending, with the answer among the options (solo game, F10.5) |

### CineClubes

A CineClube (B8) is a group that watches a movie in a period and meets on a debate day. It has one session (one movie) or several in sequence (a cycle, `isCycle`), each with `startsOn`, `endsOn` and `debateOn`. Its `status` is `upcoming`, `active`, `finished` or `cancelled`; a scheduler moves it every hour, puts the movie of a session that starts on every participant's Quero ver and pushes the debate day (preference `cineclubes`). The owner takes part from the start. Joining puts the movie of the current session on Quero ver unless the user already watched it; leaving keeps it there. The progress of a participant comes from their watch logs of the movie between the start and the end or the debate, whichever is later. The `currentSessionId` is the session running today, else the next one, else the last.

**Spoiler gate:** in `/progress`, a log with a spoiler, a comment or a GIF comes with `hidden: true`, `hiddenReason: "spoiler"` and without the comment and the GIF to a viewer who has not finished the movie, until the debate day. The author always sees their own.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | `/cineclubes` | Public. `q`, `open` (no password), `includeFinished`, `mine` (owner, needs login, any status), `participant` (a user id, or `me`), `page`, `limit`. Rows carry the current `movie`, `debateOn`, `totalSessions`, `joined` |
| GET    | `/cineclubes/{slug}` | Public. Detail with `sessions`, `currentSessionId`, `watchProviders` of the current movie, `owner`; with a token, `me` (`progress`, `completedSessions`) and `canEdit` |
| GET    | `/cineclubes/slug-available?slug=` | `{ slug, valid, available }` |
| POST   | `/cineclubes` | Creates it (201). 1–52 sessions, each with `movieId` or `tmdbId` (+ `title`), up to 90 days, `debateOn` from the start to 30 days after the end (default: `endsOn`), no overlaps, at most 366 days in all. 409 `slug_taken` |
| PUT    | `/cineclubes/{slug}` | Only the owner (403 `not_cineclube_owner`). Sessions that started stay as they are; the others change and new ones start after today. 409 `cineclube_closed` once finished or cancelled |
| POST   | `/cineclubes/{slug}/cancel` | Only the owner; read only from then on |
| POST/DELETE | `/cineclubes/{slug}/participation` | Join `{ password? }` (403 `wrong_password`, 409 `already_participating` / `cineclube_closed`) or leave |
| GET    | `/cineclubes/{slug}/progress?session&page` | Page of the participants' logs of the session (default: current), newest first, through the spoiler gate |
| GET    | `/cineclubes/{slug}/participants?session&status&completedOnly&page` | Participants with `progress` (`pending`, `watching`, `completed`), `completedSessions` and `lastWatchedOn` |

The activities `criou_cineclube` and `entrou_cineclube` carry a `cineclube` block and the `movie` of the current session.

### News

`/articles?source&page&limit` uses the Page envelope, `GET /articles/sources` lists the outlets, and every article carries `movies[]`: the movies it quotes, linked after each scrape when a quoted title matches TMDB exactly (with the year when the text gives one). With a token, `GET /api/v1/articles/{id}` adds `me: { inQueroVer, watched }` to each movie.

### Health and observability

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | `/health/live` | 200 while the process runs |
| GET    | `/health/ready` | 200 when the database answers and `TMDB_API_KEY` is set; 503 otherwise, with `checks` |
| GET    | `/metrics` | Prometheus scrape (HTTP latency per route and JVM), only with `METRICS_TOKEN` |

Every response carries an `X-Request-Id` (the one the client sent, or a new one), and the log lines of the request show it. One log line is written per `/api/v1` request.

## Project Structure

```
src/main/kotlin/org/mobyle/
├── Main.kt                         # Entry point & server config
├── routing/                        # Route definitions
│   ├── MoviesRouting.kt
│   ├── ProfileRouting.kt
│   └── ...
├── di/                             # Koin modules
│   ├── AppModule.kt
│   └── Utils.kt
├── domain/
│   ├── model/                      # Domain models
│   ├── repository/                 # Repository interfaces
│   └── usecase/                    # Use cases (movies, profile, activities)
├── data/
│   ├── di/                         # Data layer DI
│   ├── local/
│   │   ├── database/              # Exposed tables & DB config
│   │   ├── oauth/                 # OAuth state storage
│   │   └── user/                  # Local user storage
│   ├── remote/                     # TMDB API client & mappers
│   └── repository/                 # Repository implementations
└── model/                          # Response/listing models
```

## CORS

By default, `localhost:5173` and `127.0.0.1:5173` are allowed for local development. Add extra origins via the `CORS_ORIGINS` env var:

```bash
export CORS_ORIGINS="https://myapp.com,https://staging.myapp.com"
```

## Deployment

The included `Dockerfile` uses a multi-stage build (JDK 17 Alpine) and runs as a non-root user. Compatible with Railway, Fly.io, Render, or any container platform: set at least `TMDB_API_KEY`, `DATABASE_URL` and `JWT_SECRET`, and point the liveness and readiness probes at `/health/live` and `/health/ready`.

## Continuous Integration

`.github/workflows/ci.yml` runs on every pull request to `dev` and `main`:

1. `./gradlew test` (includes migration tests on an embedded PostgreSQL)
2. `./gradlew buildFatJar`

On push to `main` it also builds the Docker image. Nothing is pushed to a registry yet (the registry is still to be decided), so no secrets are required.
