package com.hendo.hendomusic.library

object KoreanSearch {
    private val initials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    fun initialsOf(value: String): String = buildString {
        value.forEach { c ->
            val offset = c.code - 0xAC00
            append(if (offset in 0..11171) initials[offset / 588] else c.lowercaseChar())
        }
    }
    fun matches(query: String, vararg fields: String?): Boolean {
        val needle = query.lowercase().filterNot(Char::isWhitespace)
        if (needle.isEmpty()) return true
        return fields.filterNotNull().any { field ->
            val normalized = field.lowercase().filterNot(Char::isWhitespace)
            normalized.contains(needle) || initialsOf(normalized).contains(needle)
        }
    }
}
