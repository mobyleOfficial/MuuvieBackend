package org.mobyle.domain.model

import kotlinx.serialization.Serializable

/**
 * A profile of the v1 API (B5): the "vitrine" of the cinéfilo. [displayName] falls back to
 * the username on the client; [handle] is null until the user picks one.
 * The relation flags are about the viewer: [isFollowing] (viewer → owner), [followsMe]
 * (owner → viewer) and [mutual] (both).
 */
@Serializable
data class Profile(
    val id: String,
    val username: String,
    val handle: String?,
    val displayName: String?,
    val pronouns: String?,
    val bio: String?,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val accentColor: String?,
    val links: List<String>,
    val featured: Boolean,
    val stats: ProfileStats,
    val isFollowing: Boolean = false,
    val followsMe: Boolean = false,
    val mutual: Boolean = false,
    val self: Boolean = false,
    // Only on GET /users/me.
    val isScraping: Boolean = false
)

/**
 * Counters of the profile header (F5.1). Shelves, locadoras and logs only count what the
 * viewer can see. Maratonas are the ones the user created and participations the ones they
 * joined (B7); CineClubes are the ones they take part in (B8).
 */
@Serializable
data class ProfileStats(
    val shelves: Int,
    val locadoras: Int,
    val watchLogs: Int,
    val watched: Int,
    val maratonas: Int = 0,
    val cineclubes: Int = 0,
    val participations: Int = 0,
    val followers: Int,
    val following: Int
)

/** The profile row, before the viewer-dependent parts are added. */
data class ProfileRecord(
    val id: String,
    val username: String,
    val handle: String?,
    val displayName: String?,
    val pronouns: String?,
    val bio: String?,
    val avatarUrl: String?,
    val bannerUrl: String?,
    val accentColor: String?,
    val links: List<String>,
    val featured: Boolean
)

/** Editable fields of the own profile, already validated. A null [handle] keeps the current one. */
data class ProfileDraft(
    val displayName: String?,
    val handle: String?,
    val pronouns: String?,
    val bio: String?,
    val links: List<String>,
    val accentColor: String?,
    val avatarUrl: String?,
    val bannerUrl: String?
)

/** Answer of GET /handles/{handle}/available. [reason] is `invalid` or `taken` when not available. */
@Serializable
data class HandleAvailability(val handle: String, val available: Boolean, val reason: String? = null)

/** A profile card of the discovery hub (F5.6). */
@Serializable
data class ProfileCard(
    val id: String,
    val username: String,
    val handle: String?,
    val displayName: String?,
    val avatarUrl: String?,
    val accentColor: String?,
    val bio: String?,
    val featured: Boolean,
    val watched: Int,
    val isFollowing: Boolean = false
)
