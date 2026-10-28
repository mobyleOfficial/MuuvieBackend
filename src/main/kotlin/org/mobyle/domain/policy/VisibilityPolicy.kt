package org.mobyle.domain.policy

import org.mobyle.domain.model.vocabulary.Visibility

/** How the viewer relates to the owner of a resource. */
enum class ViewerRelation {
    OWNER,
    // Both follow each other.
    MUTUAL,
    // Anyone else, including logged-out viewers.
    OTHER
}

/** The single place that decides who sees public, mutual and secret resources (domain ADR, rule 7). */
object VisibilityPolicy {
    fun visibleTo(relation: ViewerRelation): Set<Visibility> = when (relation) {
        ViewerRelation.OWNER -> Visibility.entries.toSet()
        ViewerRelation.MUTUAL -> setOf(Visibility.PUBLIC, Visibility.MUTUAL)
        ViewerRelation.OTHER -> setOf(Visibility.PUBLIC)
    }

    fun canView(relation: ViewerRelation, visibility: Visibility): Boolean = visibility in visibleTo(relation)
}
