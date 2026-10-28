package org.mobyle.di

import org.mobyle.domain.usecase.feed.ActivityAccess
import org.mobyle.domain.usecase.feed.ActivityPublisher
import org.mobyle.domain.usecase.feed.GetFeed
import org.mobyle.domain.usecase.feed.GetMovieActivities
import org.mobyle.domain.usecase.feed.GetProfileActivities
import org.mobyle.domain.usecase.feed.HideActivity
import org.mobyle.domain.usecase.feed.LoveActivity
import org.mobyle.domain.usecase.feed.UnloveActivity
import org.mobyle.domain.usecase.games.GetPosterRound
import org.mobyle.domain.usecase.games.WatchParties
import org.mobyle.domain.usecase.gamification.ChooseStarterPet
import org.mobyle.domain.usecase.gamification.GamificationHook
import org.mobyle.domain.usecase.gamification.GetDiaryDay
import org.mobyle.domain.usecase.gamification.GetDiaryMonth
import org.mobyle.domain.usecase.gamification.GetDiaryYear
import org.mobyle.domain.usecase.gamification.GetPetShelter
import org.mobyle.domain.usecase.gamification.PetShelterBuilder
import org.mobyle.domain.usecase.gamification.PetUnlocker
import org.mobyle.domain.usecase.gamification.PinPet
import org.mobyle.domain.usecase.gamification.SaveDiaryDay
import org.mobyle.domain.usecase.gamification.WeeklyMissions
import org.mobyle.domain.usecase.stats.GetRetrospective
import org.mobyle.domain.usecase.stats.GetStatsCalendar
import org.mobyle.domain.usecase.stats.GetStatsYears
import org.mobyle.domain.usecase.stats.GetYearStats
import org.mobyle.domain.usecase.stats.GetYearlyGoal
import org.mobyle.domain.usecase.stats.SetYearlyGoal
import org.mobyle.domain.usecase.stats.StatsAccess
import org.mobyle.domain.usecase.cineclubes.AdvanceCineClubes
import org.mobyle.domain.usecase.cineclubes.CancelCineClube
import org.mobyle.domain.usecase.cineclubes.CheckCineClubeSlug
import org.mobyle.domain.usecase.cineclubes.CineClubeAccess
import org.mobyle.domain.usecase.cineclubes.CineClubeWatchlist
import org.mobyle.domain.usecase.cineclubes.CreateCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClube
import org.mobyle.domain.usecase.cineclubes.GetCineClubeParticipants
import org.mobyle.domain.usecase.cineclubes.GetCineClubeProgress
import org.mobyle.domain.usecase.cineclubes.JoinCineClube
import org.mobyle.domain.usecase.cineclubes.LeaveCineClube
import org.mobyle.domain.usecase.cineclubes.ListCineClubes
import org.mobyle.domain.usecase.cineclubes.UpdateCineClube
import org.mobyle.domain.usecase.maratonas.AdvanceMaratonas
import org.mobyle.domain.usecase.maratonas.CancelMaratona
import org.mobyle.domain.usecase.maratonas.CheckMaratonaSlug
import org.mobyle.domain.usecase.maratonas.CreateMaratona
import org.mobyle.domain.usecase.maratonas.CreateMaratonaFromTemplate
import org.mobyle.domain.usecase.maratonas.GetChallengeSuggestions
import org.mobyle.domain.usecase.maratonas.GetMaratona
import org.mobyle.domain.usecase.maratonas.GetMaratonaChallenges
import org.mobyle.domain.usecase.maratonas.GetMaratonaProgress
import org.mobyle.domain.usecase.maratonas.GetMaratonaRanking
import org.mobyle.domain.usecase.maratonas.GetMaratonaTemplates
import org.mobyle.domain.usecase.maratonas.JoinMaratona
import org.mobyle.domain.usecase.maratonas.LeaveMaratona
import org.mobyle.domain.usecase.maratonas.ListMaratonas
import org.mobyle.domain.usecase.maratonas.MaratonaAccess
import org.mobyle.domain.usecase.maratonas.MaratonaAutoLinker
import org.mobyle.domain.usecase.maratonas.SetChallengeLink
import org.mobyle.domain.usecase.maratonas.SetMaratonaCoOwners
import org.mobyle.domain.usecase.maratonas.UpdateMaratona
import org.mobyle.domain.usecase.follow.FollowAccount
import org.mobyle.domain.usecase.follow.GetFollowList
import org.mobyle.domain.usecase.follow.GetFollowStatus
import org.mobyle.domain.usecase.follow.MutualChangeNotice
import org.mobyle.domain.usecase.follow.UnfollowAccount
import org.mobyle.domain.usecase.messages.ConversationAccess
import org.mobyle.domain.usecase.messages.GetConversation
import org.mobyle.domain.usecase.messages.GetConversationWith
import org.mobyle.domain.usecase.messages.GetConversations
import org.mobyle.domain.usecase.messages.GetMessages
import org.mobyle.domain.usecase.messages.GetUnreadCount
import org.mobyle.domain.usecase.messages.MarkConversationRead
import org.mobyle.domain.usecase.messages.SendMessage
import org.mobyle.domain.usecase.notifications.GetNotificationPrefs
import org.mobyle.domain.usecase.notifications.RegisterDevice
import org.mobyle.domain.usecase.notifications.RemoveDevice
import org.mobyle.domain.usecase.notifications.SaveNotificationPrefs
import org.mobyle.domain.usecase.articles.GetArticle
import org.mobyle.domain.usecase.articles.GetArticleSources
import org.mobyle.domain.usecase.articles.GetArticlesPage
import org.mobyle.domain.usecase.discover.DiscoverLocadoras
import org.mobyle.domain.usecase.discover.DiscoverLovedMovies
import org.mobyle.domain.usecase.discover.DiscoverProfiles
import org.mobyle.domain.usecase.discover.DiscoverShelves
import org.mobyle.domain.usecase.discover.DiscoverTrendingMovies
import org.mobyle.domain.usecase.users.CheckHandleAvailability
import org.mobyle.domain.usecase.users.GetMyProfile
import org.mobyle.domain.usecase.users.GetProfile
import org.mobyle.domain.usecase.users.GetProfileHighlights
import org.mobyle.domain.usecase.users.GetProfileStats
import org.mobyle.domain.usecase.users.ProfileAccess
import org.mobyle.domain.usecase.users.SaveProfileHighlights
import org.mobyle.domain.usecase.users.UpdateMyProfile
import org.mobyle.domain.usecase.watch.GetUserDiary
import org.mobyle.domain.usecase.imports.CancelImport
import org.mobyle.domain.usecase.imports.ConfirmImport
import org.mobyle.domain.usecase.imports.GetImport
import org.mobyle.domain.usecase.imports.GetImports
import org.mobyle.domain.usecase.imports.GetUnmatchedItems
import org.mobyle.domain.usecase.imports.ImportAccess
import org.mobyle.domain.usecase.imports.ResolveUnmatchedItem
import org.mobyle.domain.usecase.imports.StartImport
import org.mobyle.domain.usecase.auth.LoginUser
import org.mobyle.domain.usecase.auth.LogoutUser
import org.mobyle.domain.usecase.auth.ProcessOAuthCallback
import org.mobyle.domain.usecase.auth.RefreshToken
import org.mobyle.domain.usecase.auth.SignUpUser
import org.mobyle.domain.usecase.auth.CheckNicknameAvailability
import org.mobyle.domain.usecase.auth.ValidateToken
import org.mobyle.domain.usecase.movies.*
import org.mobyle.domain.usecase.profile.UpdateUserProfile
import org.mobyle.domain.usecase.shelves.AddMovieToShelves
import org.mobyle.domain.usecase.shelves.DeleteShelf
import org.mobyle.domain.usecase.shelves.GetMovieShelves
import org.mobyle.domain.usecase.shelves.GetPublicShelvesWithMovie
import org.mobyle.domain.usecase.shelves.GetSavedShelves
import org.mobyle.domain.usecase.shelves.SaveShelf
import org.mobyle.domain.usecase.shelves.UnsaveShelf
import org.mobyle.domain.usecase.shelves.GetShelf
import org.mobyle.domain.usecase.shelves.GetShelfMovies
import org.mobyle.domain.usecase.shelves.RemoveMovieFromShelf
import org.mobyle.domain.usecase.shelves.ReorderShelves
import org.mobyle.domain.usecase.shelves.ShelfAccess
import org.mobyle.domain.usecase.shelves.SortShelf
import org.mobyle.domain.usecase.shelves.TransferShelfMovies
import org.mobyle.domain.usecase.shelves.UpdateShelf
import org.mobyle.domain.usecase.comments.CommentAccess
import org.mobyle.domain.usecase.comments.DeleteComment
import org.mobyle.domain.usecase.comments.GetThreadComments
import org.mobyle.domain.usecase.comments.PostComment
import org.mobyle.domain.usecase.watch.DeleteWatchLog
import org.mobyle.domain.usecase.watch.GetMovieLogs
import org.mobyle.domain.usecase.watch.GetMovieCommunity
import org.mobyle.domain.usecase.watch.GetMovieUserState
import org.mobyle.domain.usecase.watch.GetRecentEmotions
import org.mobyle.domain.usecase.watch.GetRecentGifs
import org.mobyle.domain.usecase.watch.GetWatchingNow
import org.mobyle.domain.usecase.watch.LogWatchSession
import org.mobyle.domain.usecase.watch.RemoveMovieRating
import org.mobyle.domain.usecase.watch.SearchGifs
import org.mobyle.domain.usecase.watch.SetMovieRating
import org.mobyle.domain.usecase.watch.SetWatchingNow
import org.mobyle.domain.usecase.watch.StartRewatch
import org.mobyle.domain.usecase.watch.UpdateWatchLog
import org.mobyle.domain.usecase.watch.WatchLogAccess
import org.mobyle.domain.usecase.shelves.CreateShelf as CreateShelfV1
import org.mobyle.domain.usecase.shelves.GetUserShelves as GetUserShelvesV1
import org.mobyle.domain.usecase.shelves.SearchShelves as SearchShelvesV1
import org.mobyle.domain.usecase.locadoras.CreateLocadora as CreateLocadoraV1
import org.mobyle.domain.usecase.locadoras.DeleteLocadora
import org.mobyle.domain.usecase.locadoras.GetLocadora
import org.mobyle.domain.usecase.locadoras.GetLocadoraShelves as GetLocadoraShelvesV1
import org.mobyle.domain.usecase.locadoras.GetUserLocadoras as GetUserLocadorasV1
import org.mobyle.domain.usecase.locadoras.LocadoraAccess
import org.mobyle.domain.usecase.locadoras.ReorderLocadoraShelves
import org.mobyle.domain.usecase.locadoras.ReorderLocadoras
import org.mobyle.domain.usecase.locadoras.SetLocadoraShelves
import org.mobyle.domain.usecase.locadoras.UpdateLocadora
import org.mobyle.domain.usecase.social.SearchUsers
import org.mobyle.domain.repository.AuthRepository
import org.mobyle.domain.repository.MoviesRepository
import org.koin.dsl.module

val appModule = module {
    // Movies use cases
    factory { GetTrendingMovies(repository = get<MoviesRepository>()) }
    factory { LookupMovieDetail(repository = get<MoviesRepository>()) }
    factory { SearchMovies(repository = get<MoviesRepository>()) }
    factory { DiscoverMovies(repository = get<MoviesRepository>()) }
    factory { AttachMovieStates(repository = get()) }
    factory { GetGenres(repository = get<MoviesRepository>()) }
    factory { GetCountries(repository = get<MoviesRepository>()) }
    factory { GetLanguages(repository = get<MoviesRepository>()) }
    factory { GetPersonDetail(repository = get<MoviesRepository>()) }

    // Profile use cases
    factory { UpdateUserProfile(repository = get()) }

    // Shelves use cases (v1)
    factory { ShelfAccess(repository = get()) }
    factory { GetUserShelvesV1(repository = get(), access = get()) }
    factory { SearchShelvesV1(repository = get()) }
    factory { GetShelf(access = get(), repository = get()) }
    factory { CreateShelfV1(repository = get(), activities = get()) }
    factory { UpdateShelf(repository = get(), access = get(), activities = get()) }
    factory { DeleteShelf(repository = get(), access = get(), activities = get()) }
    factory { GetShelfMovies(repository = get(), access = get()) }
    factory { AddMovieToShelves(repository = get(), access = get()) }
    factory { RemoveMovieFromShelf(repository = get(), access = get()) }
    factory { TransferShelfMovies(repository = get(), access = get()) }
    factory { SortShelf(repository = get(), access = get()) }
    factory { ReorderShelves(repository = get(), access = get()) }
    factory { GetMovieShelves(repository = get()) }
    factory { GetPublicShelvesWithMovie(repository = get()) }
    factory { SaveShelf(repository = get(), access = get()) }
    factory { UnsaveShelf(repository = get(), access = get()) }
    factory { GetSavedShelves(repository = get()) }

    // Locadoras use cases (v1, B4)
    factory { LocadoraAccess(repository = get(), shelves = get()) }
    factory { GetUserLocadorasV1(repository = get(), shelfRepository = get(), shelves = get()) }
    factory { GetLocadora(access = get()) }
    factory { CreateLocadoraV1(repository = get(), activities = get()) }
    factory { UpdateLocadora(repository = get(), access = get(), activities = get()) }
    factory { DeleteLocadora(repository = get(), access = get(), activities = get()) }
    factory { GetLocadoraShelvesV1(repository = get(), access = get(), shelves = get()) }
    factory { SetLocadoraShelves(repository = get(), access = get()) }
    factory { ReorderLocadoraShelves(repository = get(), access = get()) }
    factory { ReorderLocadoras(repository = get(), access = get()) }

    // Profile, highlights and discovery (v1, B5)
    factory { ProfileAccess(repository = get(), shelves = get()) }
    factory { GetMyProfile(repository = get(), access = get()) }
    factory { GetProfile(access = get()) }
    factory { GetProfileStats(access = get()) }
    factory { UpdateMyProfile(repository = get(), getMyProfile = get()) }
    factory { CheckHandleAvailability(repository = get()) }
    factory { GetProfileHighlights(repository = get(), access = get(), shelves = get(), locadoras = get()) }
    factory {
        SaveProfileHighlights(repository = get(), shelves = get(), locadoras = get(), getHighlights = get(), activities = get())
    }
    factory { GetUserDiary(repository = get(), shelves = get(), access = get()) }
    factory { DiscoverShelves(repository = get()) }
    factory { DiscoverLocadoras(repository = get()) }
    factory { DiscoverProfiles(repository = get()) }
    factory { DiscoverTrendingMovies(repository = get()) }
    factory { DiscoverLovedMovies(repository = get()) }

    // Watch sessions, rating and comments (v1, B2)
    factory { WatchLogAccess(repository = get(), access = get()) }
    factory { LogWatchSession(repository = get(), shelves = get(), activities = get(), maratonas = get(), gamification = get()) }
    factory { GetMovieLogs(repository = get(), shelves = get(), access = get()) }
    factory { UpdateWatchLog(repository = get(), access = get(), activities = get()) }
    factory { DeleteWatchLog(repository = get(), access = get(), activities = get()) }
    factory { StartRewatch(repository = get()) }
    factory { SetMovieRating(repository = get(), activities = get()) }
    factory { RemoveMovieRating(repository = get(), activities = get()) }
    factory { GetMovieUserState(repository = get(), shelves = get()) }
    factory { GetMovieCommunity(repository = get()) }
    factory { GetMovieEvents(maratonas = get(), catalog = get(), cineclubes = get()) }
    factory { GetMovieCineClubes(repository = get()) }
    factory { GetMovieMaratonas(repository = get()) }
    factory { GetMovieTimeline(repository = get()) }
    factory { SetWatchingNow(repository = get(), activities = get()) }
    factory { GetWatchingNow(repository = get(), shelves = get()) }
    factory { GetRecentEmotions(repository = get()) }
    factory { GetRecentGifs(repository = get()) }
    factory { SearchGifs(repository = get()) }
    factory { CommentAccess(repository = get(), logAccess = get()) }
    factory { GetThreadComments(repository = get(), access = get()) }
    factory { PostComment(repository = get(), access = get()) }
    factory { DeleteComment(repository = get(), access = get()) }

    // Feed, follows, Sussurros and push (v1, B6)
    single { ActivityPublisher(repository = get()) }
    factory { ActivityAccess(repository = get(), follows = get()) }
    factory { GetFeed(repository = get()) }
    factory { GetProfileActivities(repository = get(), access = get()) }
    factory { GetMovieActivities(repository = get()) }
    factory { LoveActivity(repository = get(), access = get(), follows = get(), notifier = get()) }
    factory { UnloveActivity(repository = get(), access = get()) }
    factory { HideActivity(repository = get(), access = get()) }
    factory { MutualChangeNotice(conversations = get(), notifier = get()) }
    factory { FollowAccount(follows = get(), notifier = get(), rateLimiter = get(), mutualChange = get()) }
    factory { UnfollowAccount(follows = get(), mutualChange = get()) }
    factory { GetFollowStatus(follows = get()) }
    factory { GetFollowList(follows = get()) }
    factory { ConversationAccess(repository = get()) }
    factory { GetConversations(repository = get()) }
    factory { GetConversation(access = get()) }
    factory { GetConversationWith(repository = get()) }
    factory { GetMessages(repository = get(), access = get()) }
    factory { SendMessage(repository = get(), follows = get(), notifier = get(), rateLimiter = get()) }
    factory { MarkConversationRead(repository = get(), access = get(), notifier = get()) }
    factory { GetUnreadCount(repository = get()) }
    factory { RegisterDevice(repository = get()) }
    factory { RemoveDevice(repository = get()) }
    factory { GetNotificationPrefs(repository = get()) }
    factory { SaveNotificationPrefs(repository = get()) }

    // Social use cases
    factory { SearchUsers(repository = get()) }

    // Activities use cases

    // Comments use cases

    // Articles use cases
    factory { GetArticlesPage(repository = get()) }
    factory { GetArticle(repository = get()) }
    factory { GetArticleSources(repository = get()) }

    // Maratonas (B7)
    factory { MaratonaAccess(repository = get()) }
    factory { MaratonaAutoLinker(repository = get(), catalog = get(), activities = get()) }
    factory { ListMaratonas(repository = get()) }
    factory { GetMaratona(access = get()) }
    factory { CheckMaratonaSlug(repository = get()) }
    factory { CreateMaratona(repository = get(), access = get(), hasher = get(), activities = get()) }
    factory { UpdateMaratona(repository = get(), access = get(), hasher = get()) }
    factory { CancelMaratona(repository = get(), access = get()) }
    factory { SetMaratonaCoOwners(repository = get(), access = get(), shelves = get()) }
    factory { JoinMaratona(repository = get(), access = get(), shelves = get(), hasher = get(), activities = get()) }
    factory { LeaveMaratona(repository = get(), access = get(), shelves = get(), activities = get()) }
    factory { GetMaratonaChallenges(repository = get(), access = get()) }
    factory { GetChallengeSuggestions(access = get(), catalog = get()) }
    factory { SetChallengeLink(repository = get(), access = get(), shelves = get(), activities = get()) }
    factory { GetMaratonaRanking(repository = get(), access = get()) }
    factory { GetMaratonaProgress(access = get(), activities = get()) }
    factory { GetMaratonaTemplates() }
    factory { CreateMaratonaFromTemplate(create = get()) }
    factory { AdvanceMaratonas(repository = get(), activities = get(), notifier = get()) }

    // CineClubes (B8)
    factory { CineClubeAccess(repository = get()) }
    factory { CineClubeWatchlist(repository = get(), shelves = get()) }
    factory { ListCineClubes(repository = get()) }
    factory { GetCineClube(access = get(), catalog = get()) }
    factory { CheckCineClubeSlug(repository = get()) }
    factory {
        CreateCineClube(repository = get(), access = get(), shelves = get(), hasher = get(), watchlist = get(), activities = get())
    }
    factory { UpdateCineClube(repository = get(), access = get(), shelves = get(), hasher = get()) }
    factory { CancelCineClube(repository = get(), access = get()) }
    factory { JoinCineClube(repository = get(), access = get(), hasher = get(), watchlist = get(), activities = get()) }
    factory { LeaveCineClube(repository = get(), access = get(), activities = get()) }
    factory { GetCineClubeProgress(repository = get(), access = get()) }
    factory { GetCineClubeParticipants(repository = get(), access = get()) }
    factory { AdvanceCineClubes(repository = get(), watchlist = get(), notifier = get()) }

    // Charts, retrospective and goals (B9)
    factory { StatsAccess(profiles = get(), shelves = get()) }
    factory { GetStatsYears(repository = get(), access = get()) }
    factory { GetYearStats(repository = get(), access = get()) }
    factory { GetStatsCalendar(repository = get(), access = get()) }
    factory { GetRetrospective(repository = get()) }
    factory { GetYearlyGoal(repository = get(), access = get()) }
    factory { SetYearlyGoal(repository = get()) }

    // Bichinho, missions and Caderninho (B10)
    factory { PetShelterBuilder(repository = get()) }
    factory { PetUnlocker(repository = get(), activities = get()) }
    factory { GetPetShelter(shelter = get()) }
    factory { ChooseStarterPet(repository = get(), unlocker = get(), shelter = get(), activities = get()) }
    factory { PinPet(repository = get(), shelter = get()) }
    factory { WeeklyMissions(repository = get(), catalog = get()) }
    factory { GamificationHook(repository = get(), missions = get(), unlocker = get()) }
    factory { GetDiaryDay(repository = get()) }
    factory { SaveDiaryDay(repository = get(), shelves = get()) }
    factory { GetDiaryMonth(repository = get()) }
    factory { GetDiaryYear(repository = get()) }

    // Watch parties and the poster game (B10.4, B10.5). The rooms live in this single.
    single { WatchParties(profiles = get(), movies = get(), notifier = get()) }
    factory { GetPosterRound(movies = get()) }

    // Imports (v1, B3)
    factory { ImportAccess(repository = get()) }
    factory { StartImport(repository = get(), queue = get()) }
    factory { GetImport(access = get()) }
    factory { GetImports(repository = get()) }
    factory { ConfirmImport(repository = get(), access = get(), queue = get()) }
    factory { CancelImport(repository = get(), access = get()) }
    factory { GetUnmatchedItems(repository = get(), access = get()) }
    factory { ResolveUnmatchedItem(repository = get(), access = get()) }

    // Auth use cases
    factory { ProcessOAuthCallback(authRepository = get<AuthRepository>()) }
    factory { ValidateToken(authRepository = get<AuthRepository>()) }
    factory { RefreshToken(authRepository = get<AuthRepository>()) }
    factory { LoginUser(authRepository = get<AuthRepository>()) }
    factory { SignUpUser(authRepository = get<AuthRepository>()) }
    factory { LogoutUser(authRepository = get<AuthRepository>()) }
    factory { CheckNicknameAvailability(authRepository = get<AuthRepository>()) }
}
