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
import org.mobyle.data.local.oauth.OAuthStateDataSource
import org.mobyle.data.local.oauth.OAuthStateDataSourceImpl
import org.mobyle.data.local.movies.MovieCatalogDataSource
import org.mobyle.data.local.movies.MovieCatalogDataSourceImpl
import org.mobyle.data.local.movies.MovieLikesDataSource
import org.mobyle.data.local.movies.MovieLikesDataSourceImpl
import org.mobyle.data.local.movies.PersonDataSource
import org.mobyle.data.local.movies.PersonDataSourceImpl
import org.mobyle.data.local.movies.ReviewLikesDataSource
import org.mobyle.data.local.movies.ReviewLikesDataSourceImpl
import org.mobyle.data.local.user.UserDatabaseDataSource
import org.mobyle.data.local.user.UserDatabaseDataSourceImpl
import org.mobyle.data.local.user.UserLocalDataSource
import org.mobyle.data.local.user.UserLocalDataSourceImpl
import org.mobyle.data.service.MovieEnrichmentService
import org.mobyle.data.remote.articles.ArticlesDataSource
import org.mobyle.data.remote.articles.ArticlesDataSourceImpl
import org.mobyle.data.remote.comments.CommentsDataSource
import org.mobyle.data.remote.comments.CommentsDataSourceImpl
import org.mobyle.data.remote.filmow.FilmowDataSource
import org.mobyle.data.remote.filmow.FilmowDataSourceImpl
import org.mobyle.data.service.ScrapeStatusManager
import org.mobyle.data.service.WebSocketManager
import org.mobyle.data.service.WsTokenManager
import org.mobyle.data.remote.oauth.OAuthDataSource
import org.mobyle.data.remote.oauth.OAuthDataSourceImpl
import org.mobyle.data.remote.tmdb.TmdbDataSource
import org.mobyle.data.remote.tmdb.TmdbDataSourceImpl
import org.mobyle.data.repository.AuthRepositoryImpl
import org.mobyle.data.repository.ArticlesRepositoryImpl
import org.mobyle.data.repository.CommentsRepositoryImpl
import org.mobyle.data.repository.FilmowRepositoryImpl
import org.mobyle.data.repository.MoviesRepositoryImpl
import org.mobyle.data.repository.ProfileRepositoryImpl
import org.mobyle.data.repository.UserActivitiesRepositoryImpl
import org.mobyle.data.repository.UserRepositoryImpl
import org.mobyle.data.remote.auth.JWTUtil
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.ArticlesRepository
import org.mobyle.domain.repository.CommentsRepository
import org.mobyle.domain.repository.FilmowRepository
import org.mobyle.domain.repository.MoviesRepository
import org.mobyle.domain.repository.ProfileRepository
import org.mobyle.domain.repository.UserActivitiesRepository
import org.mobyle.domain.repository.UserRepository
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

    single<MovieEnrichmentService> {
        MovieEnrichmentService(
            catalogDataSource = get(),
            tmdbDataSource = get()
        )
    }

    single<PersonDataSource> {
        PersonDataSourceImpl()
    }

    single<MovieLikesDataSource> {
        MovieLikesDataSourceImpl()
    }

    single<ReviewLikesDataSource> {
        ReviewLikesDataSourceImpl()
    }

    single<MoviesRepository> {
        try {
            MoviesRepositoryImpl(
                tmdbDataSource = get(),
                userDatabaseDataSource = get(),
                movieCatalogDataSource = get(),
                enrichmentService = get(),
                reviewLikesDataSource = get(),
                movieLikesDataSource = get(),
                personDataSource = get()
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to create MoviesRepositoryImpl: ${e.message}", e)
        }
    }

    single<ProfileRepository> {
        ProfileRepositoryImpl(userDatabaseDataSource = get())
    }

    single<UserActivitiesRepository> {
        UserActivitiesRepositoryImpl(userDatabaseDataSource = get(), moviesRepository = get())
    }

    single<CommentsDataSource> {
        CommentsDataSourceImpl()
    }

    single<CommentsRepository> {
        CommentsRepositoryImpl(commentsDataSource = get())
    }

    single<ArticlesDataSource> {
        ArticlesDataSourceImpl()
    }

    single<ArticlesRepository> {
        ArticlesRepositoryImpl(articlesDataSource = get())
    }

    single<WebSocketManager> { WebSocketManager() }

    single<WsTokenManager> { WsTokenManager() }

    single<ScrapeStatusManager> { ScrapeStatusManager() }

    single<FilmowDataSource> {
        FilmowDataSourceImpl()
    }

    single<FilmowRepository> {
        FilmowRepositoryImpl(filmowDataSource = get())
    }

    // Auth-related datasources and repositories
    single<UserLocalDataSource> {
        UserLocalDataSourceImpl()
    }

    single<UserDatabaseDataSource> {
        UserDatabaseDataSourceImpl(movieCatalogDataSource = get())
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
