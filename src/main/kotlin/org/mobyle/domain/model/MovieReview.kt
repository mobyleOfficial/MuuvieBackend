package org.mobyle.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class MovieReview(
    val id: String,
    val title: String,
    val date: String? = null,
    val rating: Double = 0.0,
    val author: String? = null,
    val content: String? = null,
    val likeCount: Int = 0,
    val likedByMe: Boolean = false
)
