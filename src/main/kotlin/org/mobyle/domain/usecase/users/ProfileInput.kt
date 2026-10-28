package org.mobyle.domain.usecase.users

import org.mobyle.domain.model.HighlightDraft
import org.mobyle.domain.model.ProfileDraft
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.HighlightType

private val HANDLE_FORMAT = Regex("^[a-z0-9_.]{5,30}$")
private val COLOR_FORMAT = Regex("^#[0-9A-Fa-f]{6}$")
private val URL_FORMAT = Regex("^https?://\\S+$")
/** A Spotify or YouTube Music link; the app tells them apart by the host. */
private val SOUNDTRACK_FORMAT = Regex("^https://(open\\.spotify\\.com|music\\.youtube\\.com)/\\S+$")

private const val DISPLAY_NAME_MAX_LENGTH = 50
private const val PRONOUNS_MAX_LENGTH = 30
private const val BIO_MAX_LENGTH = 500
private const val MAX_LINKS = 5
private const val LINK_MAX_LENGTH = 200
private const val IMAGE_URL_MAX_LENGTH = 500
private const val SOUNDTRACK_MAX_LENGTH = 300
private const val TOP_MOVIES = 4

/** The handle as typed, without the leading @ and in lower case. */
fun normalizeHandle(handle: String): String = handle.trim().removePrefix("@").lowercase()

fun isValidHandle(handle: String): Boolean = HANDLE_FORMAT.matches(handle)

/**
 * Validates the editable fields of the own profile (F5.3). Blank optional fields become
 * null; the handle follows ^[a-z0-9_.]{5,30}$ (the Maratona asks for at least 5 characters).
 */
fun profileDraftOf(
    displayName: String?,
    handle: String?,
    pronouns: String?,
    bio: String?,
    links: List<String>,
    accentColor: String?,
    avatarUrl: String?,
    bannerUrl: String?
): ProfileDraft {
    val name = optional(displayName, "displayName", DISPLAY_NAME_MAX_LENGTH)
    val normalizedHandle = handle?.let(::normalizeHandle)?.also {
        if (!isValidHandle(it)) {
            throw ValidationException("handle", "Handle must have 5 to 30 lowercase letters, digits, dots or underscores")
        }
    }
    val cleanLinks = links.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    if (cleanLinks.size > MAX_LINKS) throw ValidationException("links", "A profile has at most $MAX_LINKS links")
    if (cleanLinks.any { it.length > LINK_MAX_LENGTH || !URL_FORMAT.matches(it) }) {
        throw ValidationException("links", "Links must be http(s) URLs up to $LINK_MAX_LENGTH characters")
    }
    val color = accentColor?.trim()?.takeIf { it.isNotEmpty() }?.also {
        if (!COLOR_FORMAT.matches(it)) throw ValidationException("accentColor", "Accent color must be #RRGGBB")
    }?.uppercase()
    return ProfileDraft(
        displayName = name,
        handle = normalizedHandle,
        pronouns = optional(pronouns, "pronouns", PRONOUNS_MAX_LENGTH),
        bio = optional(bio, "bio", BIO_MAX_LENGTH),
        links = cleanLinks,
        accentColor = color,
        avatarUrl = imageUrl(avatarUrl, "avatarUrl"),
        bannerUrl = imageUrl(bannerUrl, "bannerUrl")
    )
}

/** A highlight as the app sends it. */
data class HighlightInput(
    val type: String,
    val enabled: Boolean,
    val movieIds: List<Long>,
    val shelfId: Long?,
    val locadoraId: Long?,
    val soundtrackUrl: String?
)

/** Validates the shape of the highlights (F5.4): one of each type at most; ownership is checked by the use case. */
fun highlightDraftsOf(inputs: List<HighlightInput>): List<HighlightDraft> {
    val drafts = inputs.map { input ->
        val type = HighlightType.fromWireValue(input.type)
            ?: throw ValidationException("type", "Highlight type must be top4, shelf, locadora or soundtrack")
        when (type) {
            HighlightType.TOP4 -> {
                val movieIds = input.movieIds.distinct()
                if (movieIds.isEmpty() || movieIds.size > TOP_MOVIES) {
                    throw ValidationException("movieIds", "Top 4 needs between 1 and $TOP_MOVIES movies")
                }
                HighlightDraft(type, input.enabled, movieIds = movieIds)
            }
            HighlightType.SHELF -> HighlightDraft(
                type, input.enabled,
                shelfId = input.shelfId ?: throw ValidationException("shelfId", "Featured shelf needs a shelfId")
            )
            HighlightType.LOCADORA -> HighlightDraft(
                type, input.enabled,
                locadoraId = input.locadoraId ?: throw ValidationException("locadoraId", "Featured locadora needs a locadoraId")
            )
            HighlightType.SOUNDTRACK -> {
                val url = input.soundtrackUrl?.trim().orEmpty()
                if (url.length > SOUNDTRACK_MAX_LENGTH || !SOUNDTRACK_FORMAT.matches(url)) {
                    throw ValidationException("soundtrackUrl", "Soundtrack must be an open.spotify.com or music.youtube.com link")
                }
                HighlightDraft(type, input.enabled, soundtrackUrl = url)
            }
        }
    }
    if (drafts.map { it.type }.toSet().size != drafts.size) {
        throw ValidationException("type", "A profile has at most one highlight of each type")
    }
    return drafts
}

private fun optional(value: String?, field: String, maxLength: Int): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length > maxLength) throw ValidationException(field, "$field must have at most $maxLength characters")
    return trimmed
}

private fun imageUrl(value: String?, field: String): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.length > IMAGE_URL_MAX_LENGTH || !URL_FORMAT.matches(trimmed)) {
        throw ValidationException(field, "$field must be an http(s) URL")
    }
    return trimmed
}
