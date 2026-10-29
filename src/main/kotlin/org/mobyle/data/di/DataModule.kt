package org.mobyle.data.di

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import org.mobyle.data.local.auth.RefreshTokenDataSource
import org.mobyle.data.local.auth.RefreshTokenDataSourceImpl
import org.mobyle.data.local.auth.TokenBlocklistDataSource
import org.mobyle.data.local.discover.DiscoverDataSource
import org.mobyle.data.local.discover.DiscoverDataSourceImpl
import org.mobyle.data.local.profile.UserProfileDataSource
import org.mobyle.data.local.profile.UserProfileDataSourceImpl
import org.mobyle.data.repository.DiscoverRepositoryImpl
import org.mobyle.data.repository.UserProfileRepositoryImpl
import org.mobyle.data.service.ArticleMovieLinker
import org.mobyle.domain.repository.DiscoverRepository
import org.mobyle.domain.repository.UserProfileRepository
import org.mobyle.data.local.locadoras.LocadoraDataSource
import org.mobyle.data.local.locadoras.LocadoraDataSourceImpl
import org.mobyle.data.local.shelves.ShelfDataSource
import org.mobyle.data.local.shelves.ShelfDataSourceImpl
import org.mobyle.data.repository.LocadoraRepositoryImpl
import org.mobyle.data.repository.ShelfRepositoryImpl
import org.mobyle.data.local.comments.CommentThreadDataSource
import org.mobyle.data.local.comments.CommentThreadDataSourceImpl
import org.mobyle.data.local.watch.WatchDataSource
import org.mobyle.data.local.watch.WatchDataSourceImpl
import org.mobyle.data.remote.gifs.GifDataSource
import org.mobyle.data.remote.gifs.TenorGifDataSource
import org.mobyle.data.remote.translation.DeepLTranslationDataSource
import org.mobyle.data.remote.translation.TranslationDataSource
import org.mobyle.data.service.OverviewTranslator
import org.mobyle.data.repository.CommentThreadRepositoryImpl
import org.mobyle.data.repository.GifRepositoryImpl
import org.mobyle.data.repository.WatchRepositoryImpl
import org.mobyle.domain.repository.CommentThreadRepository
import org.mobyle.domain.repository.GifRepository
import org.mobyle.domain.repository.WatchRepository
import org.mobyle.domain.repository.LocadoraRepository
import org.mobyle.domain.repository.ShelfRepository
import org.mobyle.data.local.oauth.OAuthStateDataSource
import org.mobyle.data.local.oauth.OAuthStateDataSourceImpl
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.movies.MovieCatalogDataSourceImpl
import org.mobyle.data.local.movies.PersonDataSource
import org.mobyle.data.local.movies.PersonDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.user.UserLocalDataSource
import org.mobyle.data.local.user.UserLocalDataSourceImpl
import org.mobyle.data.service.MovieEnrichmentService
import org.mobyle.data.remote.articles.ArticlesDataSource
import org.mobyle.data.remote.articles.ArticlesDataSourceImpl
import org.mobyle.data.remote.filmow.FilmowDataSource
import org.mobyle.data.remote.filmow.FilmowDataSourceImpl
import org.mobyle.data.service.WebSocketManager
import org.mobyle.data.service.WsTokenManager
import org.mobyle.data.remote.oauth.OAuthDataSource
import org.mobyle.data.remote.oauth.OAuthDataSourceImpl
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSourceImpl
import org.mobyle.data.repository.AuthRepositoryImpl
import org.mobyle.data.repository.ArticlesRepositoryImpl
import org.mobyle.data.repository.MoviesRepositoryImpl
import org.mobyle.data.repository.ProfileRepositoryImpl
import org.mobyle.data.repository.UserRepositoryImpl
import org.mobyle.data.remote.auth.JWTUtil
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.ArticlesRepository
import org.mobyle.domain.repository.ImportJobQueue
import org.mobyle.domain.repository.ImportRepository
import org.mobyle.data.local.imports.ImportDataSource
import org.mobyle.data.local.imports.ImportDataSourceImpl
import org.mobyle.data.repository.FilmowImportReader
import org.mobyle.data.repository.ImportRepositoryImpl
import org.mobyle.data.repository.TmdbMovieCandidateSearch
import org.mobyle.data.repository.TmdbMovieResolver
import org.mobyle.domain.repository.MovieResolver
import org.mobyle.data.service.ImportJobRunner
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.domain.repository.ProfileRepository
import org.mobyle.domain.repository.UserRepository
import org.mobyle.data.local.feed.ActivityDataSource
import org.mobyle.data.local.feed.ActivityDataSourceImpl
import org.mobyle.data.local.feed.ConversationDataSource
import org.mobyle.data.local.feed.ConversationDataSourceImpl
import org.mobyle.data.local.feed.FollowDataSource
import org.mobyle.data.local.feed.FollowDataSourceImpl
import org.mobyle.data.local.feed.NotificationDataSource
import org.mobyle.data.local.feed.NotificationDataSourceImpl
import org.mobyle.data.remote.push.DisabledPushSender
import org.mobyle.data.remote.push.FcmPushSender
import org.mobyle.data.remote.push.PushSender
import org.mobyle.data.repository.ActivityRepositoryImpl
import org.mobyle.data.repository.ConversationRepositoryImpl
import org.mobyle.data.repository.FollowRepositoryImpl
import org.mobyle.data.repository.NotificationRepositoryImpl
import org.mobyle.data.service.InMemoryRateLimiter
import org.mobyle.data.service.BcryptPasswordHasher
import org.mobyle.data.local.gamification.GamificationDataSource
import org.mobyle.data.local.gamification.GamificationDataSourceImpl
import org.mobyle.data.repository.GamificationRepositoryImpl
import org.mobyle.domain.repository.GamificationRepository
import org.mobyle.data.local.stats.StatsDataSource
import org.mobyle.data.local.stats.StatsDataSourceImpl
import org.mobyle.data.repository.StatsRepositoryImpl
import org.mobyle.domain.repository.StatsRepository
import org.mobyle.data.local.cineclubes.CineClubeDataSource
import org.mobyle.data.local.cineclubes.CineClubeDataSourceImpl
import org.mobyle.data.repository.CineClubeCatalogImpl
import org.mobyle.data.repository.CineClubeRepositoryImpl
import org.mobyle.domain.repository.CineClubeCatalog
import org.mobyle.domain.repository.CineClubeRepository
import org.mobyle.data.local.maratonas.MaratonaDataSource
import org.mobyle.data.local.maratonas.MaratonaDataSourceImpl
import org.mobyle.data.repository.MaratonaCatalogImpl
import org.mobyle.data.repository.MaratonaRepositoryImpl
import org.mobyle.domain.repository.MaratonaCatalog
import org.mobyle.domain.repository.MaratonaRepository
import org.mobyle.domain.repository.PasswordHasher
import org.mobyle.data.service.SocialNotifierImpl
import org.mobyle.domain.repository.ActivityRepository
import org.mobyle.domain.repository.ConversationRepository
import org.mobyle.domain.repository.FollowRepository
import org.mobyle.domain.repository.NotificationRepository
import org.mobyle.domain.repository.RateLimiter
import org.mobyle.domain.repository.SocialNotifier
import java.io.File
import org.koin.dsl.module

val dataModule = module {
    single<HttpClient> {
        val apiKey = System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "TMDB_API_KEY environment variable is not set. Set it to your TMDB API Bearer token."
            )

        HttpClient(CIO) {
            defaultRequest {
                url("https://api.themoviedb.org/3/")
                headers.append(HttpHeaders.Authorization, "Bearer $apiKey")
                headers.append(HttpHeaders.Accept, "application/json")
                url.parameters.append("language", "pt-BR")
            }
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = false
                    isLenient = true
                    ignoreUnknownKeys = true
                })
            }
            install(Logging) {
                level = LogLevel.INFO
            }
        }
    }

    single<TmdbDataSource> {
        try {
            TmdbDataSourceImpl(httpClient = get())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create TmdbDataSourceImpl: ${e.message}", e)
        }
    }

    single<MovieCatalogDataSource> {
        MovieCatalogDataSourceImpl()
    }

    single<MovieResolver> {
        TmdbMovieResolver(tmdbDataSource = get(), candidateSearch = TmdbMovieCandidateSearch(tmdbDataSource = get()))
    }

    // Overviews TMDB only has in another language stay untranslated until DEEPL_API_KEY is set.
    System.getenv("DEEPL_API_KEY")?.takeIf { it.isNotBlank() }?.let { apiKey ->
        single<TranslationDataSource> {
            val translationHttpClient = HttpClient(CIO) {
                // Non-2xx (e.g. an invalid key or the quota spent) throws; the overview stays as it is.
                expectSuccess = true
                install(HttpTimeout) { requestTimeoutMillis = 10_000 }
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            DeepLTranslationDataSource(httpClient = translationHttpClient, apiKey = apiKey)
        }
    }

    single { OverviewTranslator(translationDataSource = getOrNull(), catalogDataSource = get()) }

    single<MovieEnrichmentService> {
        MovieEnrichmentService(
            catalogDataSource = get(),
            tmdbDataSource = get(),
            resolver = get(),
            overviewTranslator = get()
        )
    }

    single<PersonDataSource> {
        PersonDataSourceImpl()
    }

    single<MoviesRepository> {
        try {
            MoviesRepositoryImpl(
                tmdbDataSource = get(),
                movieCatalogDataSource = get(),
                enrichmentService = get(),
                personDataSource = get(),
                overviewTranslator = get()
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create MoviesRepositoryImpl: ${e.message}", e)
        }
    }

    single<ShelfDataSource> { ShelfDataSourceImpl() }

    single<ShelfRepository> { ShelfRepositoryImpl(shelfDataSource = get()) }

    single<LocadoraDataSource> { LocadoraDataSourceImpl(shelves = get()) }

    single<LocadoraRepository> { LocadoraRepositoryImpl(locadoraDataSource = get()) }

    single<UserProfileDataSource> { UserProfileDataSourceImpl() }

    single<UserProfileRepository> {
        UserProfileRepositoryImpl(userProfileDataSource = get(), importRepository = get())
    }

    single<DiscoverDataSource> { DiscoverDataSourceImpl() }

    single<DiscoverRepository> {
        DiscoverRepositoryImpl(
            discoverDataSource = get(),
            shelfDataSource = get(),
            locadoraDataSource = get(),
            userProfileDataSource = get(),
            movieCatalogDataSource = get()
        )
    }

    single<WatchDataSource> { WatchDataSourceImpl() }

    single<WatchRepository> { WatchRepositoryImpl(watchDataSource = get()) }

    single<CommentThreadDataSource> { CommentThreadDataSourceImpl() }

    single<CommentThreadRepository> { CommentThreadRepositoryImpl(commentThreadDataSource = get()) }

    // GIF search is off (503) until TENOR_API_KEY is set.
    System.getenv("TENOR_API_KEY")?.takeIf { it.isNotBlank() }?.let { apiKey ->
        single<GifDataSource> {
            val gifHttpClient = HttpClient(CIO) {
                // Non-2xx (e.g. an invalid key) throws, so the repository answers 503.
                expectSuccess = true
                install(HttpTimeout) { requestTimeoutMillis = 5_000 }
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            TenorGifDataSource(httpClient = gifHttpClient, apiKey = apiKey)
        }
    }

    single<GifRepository> { GifRepositoryImpl(gifDataSource = getOrNull()) }

    single<ProfileRepository> {
        ProfileRepositoryImpl(userDatabaseDataSource = get())
    }

    single<ArticlesDataSource> {
        ArticlesDataSourceImpl()
    }

    single<ArticlesRepository> {
        ArticlesRepositoryImpl(articlesDataSource = get())
    }

    single { ArticleMovieLinker(articlesDataSource = get(), tmdbDataSource = get(), shelfDataSource = get()) }

    single<WebSocketManager> { WebSocketManager() }

    single<WsTokenManager> { WsTokenManager() }

    // Social (B6): feed, follows, Sussurros and push.
    single<ActivityDataSource> { ActivityDataSourceImpl() }
    single<ActivityRepository> { ActivityRepositoryImpl(activityDataSource = get()) }
    single<FollowDataSource> { FollowDataSourceImpl() }
    single<FollowRepository> { FollowRepositoryImpl(followDataSource = get()) }
    single<ConversationDataSource> { ConversationDataSourceImpl() }
    single<ConversationRepository> { ConversationRepositoryImpl(conversationDataSource = get()) }
    single<NotificationDataSource> { NotificationDataSourceImpl() }
    single<NotificationRepository> { NotificationRepositoryImpl(notificationDataSource = get()) }
    single<RateLimiter> { InMemoryRateLimiter() }

    // Push is off until FCM_SERVICE_ACCOUNT (the service account JSON) or FCM_SERVICE_ACCOUNT_FILE is set.
    single<PushSender> {
        val key = System.getenv("FCM_SERVICE_ACCOUNT")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FCM_SERVICE_ACCOUNT_FILE")?.takeIf { it.isNotBlank() }?.let { File(it).readText() }
        if (key == null) {
            DisabledPushSender()
        } else {
            val pushHttpClient = HttpClient(CIO) {
                install(HttpTimeout) { requestTimeoutMillis = 10_000 }
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            FcmPushSender(httpClient = pushHttpClient, account = FcmPushSender.ServiceAccount.parse(key))
        }
    }

    single<SocialNotifier> {
        SocialNotifierImpl(webSocketManager = get(), notifications = get(), pushSender = get())
    }

    // Maratonas (B7).
    single<MaratonaDataSource> { MaratonaDataSourceImpl() }
    single<MaratonaRepository> { MaratonaRepositoryImpl(maratonaDataSource = get()) }
    single<MaratonaCatalog> { MaratonaCatalogImpl(tmdb = get(), catalog = get(), enrichment = get()) }
    single<PasswordHasher> { BcryptPasswordHasher() }

    // CineClubes (B8).
    single<CineClubeDataSource> { CineClubeDataSourceImpl() }
    single<CineClubeRepository> { CineClubeRepositoryImpl(cineclubeDataSource = get()) }
    single<CineClubeCatalog> { CineClubeCatalogImpl(movies = get()) }

    // Charts, retrospective and goals (B9).
    single<StatsDataSource> { StatsDataSourceImpl() }
    single<StatsRepository> { StatsRepositoryImpl(statsDataSource = get()) }

    // Bichinho, missions and Caderninho (B10).
    single<GamificationDataSource> { GamificationDataSourceImpl() }
    single<GamificationRepository> { GamificationRepositoryImpl(gamificationDataSource = get()) }

    single<FilmowDataSource> {
        FilmowDataSourceImpl()
    }

    // Imports (B3). Letterboxd gets its reader when its input format is decided (A1).
    single<ImportDataSource> { ImportDataSourceImpl(catalog = get()) }

    single<ImportRepository> { ImportRepositoryImpl(importDataSource = get()) }

    single<ImportJobQueue> {
        ImportJobRunner(
            repository = get(),
            readers = listOf(FilmowImportReader(filmowDataSource = get(), catalog = get())),
            resolver = get(),
            enrichment = get(),
            webSocketManager = get(),
            activities = get()
        )
    }

    // Auth-related datasources and repositories
    single<UserLocalDataSource> {
        UserLocalDataSourceImpl()
    }

    single<UserDatabaseDataSource> {
        UserDatabaseDataSourceImpl()
    }

    single<TokenBlocklistDataSource> {
        TokenBlocklistDataSource()
    }

    single<RefreshTokenDataSource> {
        RefreshTokenDataSourceImpl()
    }

    single<OAuthStateDataSource> {
        OAuthStateDataSourceImpl()
    }

    run {
        val clientId = System.getenv("OAUTH_CLIENT_ID")?.takeIf { it.isNotBlank() }
        val clientSecret = System.getenv("OAUTH_CLIENT_SECRET")?.takeIf { it.isNotBlank() }
        val providerUrl = System.getenv("OAUTH_PROVIDER_URL")?.takeIf { it.isNotBlank() }

        if (clientId != null && clientSecret != null && providerUrl != null) {
            single<OAuthDataSource> {
                val redirectUri = System.getenv("OAUTH_REDIRECT_URI")?.takeIf { it.isNotBlank() }
                    ?: "http://localhost:8080/auth/oauth/callback"

                val oauthHttpClient = HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json {
                            prettyPrint = false
                            isLenient = true
                            ignoreUnknownKeys = true
                        })
                    }
                    install(Logging) {
                        level = LogLevel.INFO
                    }
                }

                OAuthDataSourceImpl(
                    httpClient = oauthHttpClient,
                    oauthClientId = clientId,
                    oauthClientSecret = clientSecret,
                    oauthProviderUrl = providerUrl,
                    oauthRedirectUri = redirectUri
                )
            }
        }
    }

    single<JWTUtil> {
        val secret = System.getenv("JWT_SECRET")?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "JWT_SECRET environment variable is not set. Generate one with: openssl rand -base64 32"
            )
        val expirySeconds = System.getenv("JWT_EXPIRY_SECONDS")?.toLongOrNull() ?: 86400
        val issuer = System.getenv("JWT_ISSUER")?.takeIf { it.isNotBlank() } ?: "moovie-backend"

        JWTUtil(
            jwtSecret = secret,
            jwtExpirySeconds = expirySeconds,
            jwtIssuer = issuer
        )
    }

    single<UserRepository> {
        UserRepositoryImpl(userLocalDataSource = get())
    }

    single<AuthRepository> {
        AuthRepositoryImpl(
            oauthDataSource = getOrNull(),
            oauthStateDataSource = get(),
            userRepository = get(),
            jwtUtil = get(),
            userDatabaseDataSource = get(),
            tokenBlocklistDataSource = get(),
            userLocalDataSource = get(),
            refreshTokenDataSource = get()
        )
    }
}
