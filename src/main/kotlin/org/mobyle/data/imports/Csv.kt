package org.mobyle.data.imports

/**
 * A small RFC 4180 reader: quoted fields with commas, escaped quotes ("") and line breaks,
 * CRLF or LF line ends, and a UTF-8 byte order mark at the start. Blank lines come back as
 * rows with one empty field, because Letterboxd's list files use one as a separator.
 */
object Csv {
    private const val BYTE_ORDER_MARK = '﻿'

    fun parse(text: String): List<List<String>> {
        val content = text.removePrefix(BYTE_ORDER_MARK.toString())
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var index = 0

        fun endField() {
            row.add(field.toString())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            rows.add(row)
            row = mutableListOf()
        }

        while (index < content.length) {
            val character = content[index]
            when {
                inQuotes && character == '"' && content.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                character == '"' && (inQuotes || field.isEmpty()) -> inQuotes = !inQuotes
                inQuotes -> field.append(character)
                character == ',' -> endField()
                character == '\r' && content.getOrNull(index + 1) == '\n' -> {
                    endRow()
                    index++
                }
                character == '\n' || character == '\r' -> endRow()
                else -> field.append(character)
            }
            index++
        }
        // The last line usually has no line break; a trailing break does not add an empty row.
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Header names compared loosely: "Letterboxd URI", "LetterboxdURI" and "letterboxd_uri" are one. */
    fun normalizeHeader(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
}
