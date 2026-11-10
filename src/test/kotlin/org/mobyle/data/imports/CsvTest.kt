package org.mobyle.data.imports

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvTest {

    @Test
    fun `reads quoted commas, escaped quotes and line breaks inside quotes`() {
        val rows = Csv.parse("Name,Review\r\n\"Crouching Tiger, Hidden Dragon\",\"Said \"\"wow\"\"\nthen left\"\r\n")

        assertEquals(listOf(listOf("Name", "Review"), listOf("Crouching Tiger, Hidden Dragon", "Said \"wow\"\nthen left")), rows)
    }

    @Test
    fun `strips the byte order mark and keeps empty fields and blank lines`() {
        val rows = Csv.parse("﻿a,b,c\n1,,3\n\nx,y,z")

        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "", "3"), listOf(""), listOf("x", "y", "z")), rows)
    }

    @Test
    fun `headers compare loosely`() {
        assertEquals(Csv.normalizeHeader("LetterboxdURI"), Csv.normalizeHeader(" Letterboxd URI "))
        assertEquals("watcheddate", Csv.normalizeHeader("Watched_Date"))
    }
}
