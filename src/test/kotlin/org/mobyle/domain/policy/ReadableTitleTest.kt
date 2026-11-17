package org.mobyle.domain.policy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadableTitleTest {

    @Test
    fun `latin titles are readable, accents, digits and punctuation included`() {
        assertTrue(ReadableTitle.isReadable("Sin City: A Cidade do Pecado"))
        assertTrue(ReadableTitle.isReadable("Amélie (2001) — Ça & ñ"))
        assertTrue(ReadableTitle.isReadable("2046"))
    }

    @Test
    fun `titles in other scripts are not`() {
        assertFalse(ReadableTitle.isReadable("アンダー・ユア・ベッド"))
        assertFalse(ReadableTitle.isReadable("기생충"))
        assertFalse(ReadableTitle.isReadable("Сталкер"))
        assertFalse(ReadableTitle.isReadable("Alien エイリアン"))
    }
}
