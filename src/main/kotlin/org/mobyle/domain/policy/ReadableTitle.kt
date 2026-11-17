package org.mobyle.domain.policy

/**
 * Whether a title can be read by the app's audience, which reads Latin script. TMDB answers a
 * pt-BR request with the original title when a movie has no Portuguese one, so a Japanese or
 * Korean movie can come as "アンダー・ユア・ベッド"; such a title is worth swapping for the English one.
 */
object ReadableTitle {
    fun isReadable(title: String): Boolean = title.codePoints().allMatch { codePoint ->
        !Character.isLetter(codePoint) || Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
    }
}
