package org.mobyle.di

import org.mobyle.domain.usecase.GetCommentsUseCase
import org.mobyle.domain.usecase.activities.GetFriendsActivities
import org.mobyle.domain.usecase.articles.GetArticleDetail
import org.mobyle.domain.usecase.articles.GetArticles
import org.mobyle.domain.usecase.filmow.ImportFilmowData
import org.mobyle.domain.usecase.filmow.ScrapeFilmowProfile
import org.mobyle.domain.usecase.activities.GetUserActivities
import org.mobyle.domain.usecase.activities.SubmitReview
import org.mobyle.domain.usecase.auth.LoginUser
import org.mobyle.domain.usecase.auth.LogoutUser
import org.mobyle.domain.usecase.auth.ProcessOAuthCallback
import org.mobyle.domain.usecase.auth.RefreshToken
import org.mobyle.domain.usecase.auth.SignUpUser
import org.mobyle.domain.usecase.auth.CheckNicknameAvailability
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.movies.*
import org.mobyle.domain.usecase.profile.GetPublicProfile
import org.mobyle.domain.usecase.profile.GetUserProfile
import org.mobyle.domain.usecase.profile.UpdateUserProfile
import org.mobyle.domain.usecase.social.FollowUser
import org.mobyle.domain.usecase.social.GetFollowers
import org.mobyle.domain.usecase.social.GetFollowing
import org.mobyle.domain.usecase.social.GetMyFollowing
import org.mobyle.domain.usecase.social.SearchUsers
import org.mobyle.domain.usecase.social.UnfollowUser
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.MoviesRepository
import org.koin.dsl.module

val appModule = module {
    // Movies use cases
    factory { GetTrendingMovies(repository = get<MoviesRepository>()) }
    factory { GetMovieDetail(repository = get<MoviesRepository>()) }
    factory { LookupMovieDetail(repository = get<MoviesRepository>()) }
    factory { SearchMovies(repository = get<MoviesRepository>()) }
    factory { DiscoverMovies(repository = get<MoviesRepository>()) }
    factory { GetGenres(repository = get<MoviesRepository>()) }
    factory { GetCountries(repository = get<MoviesRepository>()) }
    factory { GetLanguages(repository = get<MoviesRepository>()) }
    factory { GetMovieReviews(repository = get<MoviesRepository>()) }
    factory { GetUserFavoriteMovies(repository = get<MoviesRepository>()) }
    factory { GetUserWatchList(repository = get<MoviesRepository>()) }
    factory { GetUserWatchedMovies(repository = get<MoviesRepository>()) }
    factory { GetMovieLists(repository = get<MoviesRepository>()) }
    factory { GetUserMovieLists(repository = get<MoviesRepository>()) }
    factory { GetMovieListDetail(repository = get<MoviesRepository>()) }
    factory { GetFeaturedLists(repository = get<MoviesRepository>()) }
    factory { GetRecentMovies(repository = get<MoviesRepository>()) }
    factory { LikeReview(repository = get<MoviesRepository>()) }
    factory { UnlikeReview(repository = get<MoviesRepository>()) }
    factory { LikeMovie(repository = get<MoviesRepository>()) }
    factory { UnlikeMovie(repository = get<MoviesRepository>()) }
    factory { GetPersonDetail(repository = get<MoviesRepository>()) }
    factory { CreateMovieList(repository = get<MoviesRepository>()) }
    factory { DeleteMovieList(repository = get<MoviesRepository>()) }
    factory { AddMovieToList(repository = get<MoviesRepository>()) }
    factory { RemoveMovieFromList(repository = get<MoviesRepository>()) }

    // Profile use cases
    factory { GetUserProfile(repository = get()) }
    factory { UpdateUserProfile(repository = get()) }
    factory { GetPublicProfile(repository = get()) }

    // Social use cases
    factory { FollowUser(repository = get()) }
    factory { UnfollowUser(repository = get()) }
    factory { GetFollowers(repository = get()) }
    factory { GetFollowing(repository = get()) }
    factory { GetMyFollowing(repository = get()) }
    factory { SearchUsers(repository = get()) }

    // Activities use cases
    factory { GetUserActivities(repository = get()) }
    factory { GetFriendsActivities(repository = get()) }
    factory { SubmitReview(repository = get()) }

    // Comments use cases
    factory { GetCommentsUseCase(commentsRepository = get()) }

    // Articles use cases
    factory { GetArticles(repository = get()) }
    factory { GetArticleDetail(repository = get()) }

    // Filmow use cases
    factory { ScrapeFilmowProfile(repository = get()) }
    factory { ImportFilmowData(userDatabaseDataSource = get(), movieCatalogDataSource = get()) }

    // Auth use cases
    factory { ProcessOAuthCallback(authRepository = get<AuthRepository>()) }
    factory { ValidateToken(authRepository = get<AuthRepository>()) }
    factory { RefreshToken(authRepository = get<AuthRepository>()) }
    factory { LoginUser(authRepository = get<AuthRepository>()) }
    factory { SignUpUser(authRepository = get<AuthRepository>()) }
    factory { LogoutUser(authRepository = get<AuthRepository>()) }
    factory { CheckNicknameAvailability(authRepository = get<AuthRepository>()) }
}
