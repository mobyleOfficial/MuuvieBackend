package org.mobyle.domain.usecase.locadoras

import org.mobyle.domain.model.LocadoraDraft
import org.mobyle.domain.model.ValidationException
import org.mobyle.domain.model.vocabulary.Visibility

private const val NAME_MAX_LENGTH = 150
private const val DESCRIPTION_MAX_LENGTH = 150
private const val MAX_SHELVES = 200

/** Validates the editable fields of a locadora (F4.1). Limits follow the Maratona: name 1-150, description up to 150. */
fun locadoraDraftOf(
    name: String,
    description: String?,
    visibility: String?,
    hideShelvesInProfile: Boolean,
    hideShelvesInFilters: Boolean
): LocadoraDraft {
    val trimmedName = name.trim()
    if (trimmedName.isEmpty() || trimmedName.length > NAME_MAX_LENGTH) {
        throw ValidationException("name", "Name must have between 1 and $NAME_MAX_LENGTH characters")
    }
    val trimmedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
    if (trimmedDescription != null && trimmedDescription.length > DESCRIPTION_MAX_LENGTH) {
        throw ValidationException("description", "Description must have at most $DESCRIPTION_MAX_LENGTH characters")
    }
    val parsedVisibility = visibility?.let {
        Visibility.fromWireValue(it) ?: throw ValidationException("visibility", "Visibility must be public, mutual or secret")
    } ?: Visibility.DEFAULT
    return LocadoraDraft(trimmedName, trimmedDescription, parsedVisibility, hideShelvesInProfile, hideShelvesInFilters)
}

/** The shelf ids sent by the app, without repeats. */
fun locadoraShelfIdsOf(shelfIds: List<Long>): List<Long> {
    val distinct = shelfIds.distinct()
    if (distinct.size > MAX_SHELVES) {
        throw ValidationException("shelfIds", "A locadora holds at most $MAX_SHELVES shelves")
    }
    return distinct
}
