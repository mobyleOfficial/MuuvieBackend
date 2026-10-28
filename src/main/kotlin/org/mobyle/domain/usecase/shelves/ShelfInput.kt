package org.mobyle.domain.usecase.shelves

import org.mobyle.domain.model.ShelfDraft
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.Visibility

private const val NAME_MAX_LENGTH = 150
private const val DESCRIPTION_MAX_LENGTH = 150
private val COLOR_PATTERN = Regex("^#[0-9A-Fa-f]{6}$")

/** Validates the editable fields of a shelf (F1.1). Limits follow the Maratona: name 1-150, description up to 150. */
fun shelfDraftOf(name: String, description: String?, color: String?, visibility: String?): ShelfDraft {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty() || trimmedName.length > NAME_MAX_LENGTH) {
        throw ValidationException("name", "Name must have between 1 and $NAME_MAX_LENGTH characters")
    }
    val trimmedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmedDescription != null && trimmedDescription.length > DESCRIPTION_MAX_LENGTH) {
        throw ValidationException("description", "Description must have at most $DESCRIPTION_MAX_LENGTH characters")
    }
    val normalizedColor = color?.takeIf { it.isNotBlank() }
    if (normalizedColor != null && !COLOR_PATTERN.matches(normalizedColor)) {
        throw ValidationException("color", "Color must be #RRGGBB")
    }
    val parsedVisibility = visibility?.let {
        Visibility.fromWireValue(it) ?: throw ValidationException("visibility", "Visibility must be public, mutual or secret")
    } ?: Visibility.DEFAULT
    return ShelfDraft(trimmedName, trimmedDescription, normalizedColor, parsedVisibility)
}
