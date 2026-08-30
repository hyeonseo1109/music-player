package com.hendo.hendomusic.library

/** Small, dependency-free reader for Samsung Music-style playlist exports. */
data class ImportedPlaylist(val name: String, val entries: List<String>)

object PlaylistImportCodec {
    fun parse(fileName: String, contents: String): ImportedPlaylist {
        if (fileName.endsWith(".smpl", ignoreCase = true)) return parseSamsungSmpl(fileName, contents)
        val lines = contents.lineSequence().map { it.trim().removePrefix("\uFEFF") }.toList()
        val entries = if (fileName.endsWith(".pls", ignoreCase = true)) {
            lines.mapNotNull { line ->
                line.substringAfter('=', missingDelimiterValue = "")
                    .takeIf { line.startsWith("File", ignoreCase = true) && it.isNotBlank() }
            }
        } else {
            lines.filter { it.isNotBlank() && !it.startsWith('#') }
        }
        return ImportedPlaylist(fileName.substringBeforeLast('.', fileName).ifBlank { "가져온 재생목록" }, entries)
    }

    /** Samsung Music SMPL is a small JSON document. Its member records have no nested arrays. */
    private fun parseSamsungSmpl(fileName: String, contents: String): ImportedPlaylist {
        val membersJson = jsonArrayContent(contents, "members")
            ?: error("Samsung Music 재생목록에 곡 목록이 없습니다.")
        val entries = Regex("\\{(.*?)\\}", setOf(RegexOption.DOT_MATCHES_ALL)).findAll(membersJson)
            .mapIndexedNotNull { index, match ->
                val member = match.groupValues[1]
                val info = jsonString(member, "info")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                (jsonNumber(member, "order") ?: index) to info
            }
            .sortedBy { it.first }
            .map { it.second }
            .toList()
        val name = jsonString(contents, "name")
            ?.ifBlank { null }
            ?: fileName.substringBeforeLast('.').ifBlank { "Samsung Music 재생목록" }
        return ImportedPlaylist(name, entries)
    }

    private fun jsonString(json: String, key: String): String? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
        .find(json)?.groupValues?.get(1)
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")

    private fun jsonNumber(json: String, key: String): Int? = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(-?\\d+)")
        .find(json)?.groupValues?.get(1)?.toIntOrNull()

    /** Returns the JSON array body while ignoring brackets that are part of quoted filenames. */
    private fun jsonArrayContent(json: String, key: String): String? {
        val keyMatch = Regex("\\\"${Regex.escape(key)}\\\"\\s*:").find(json) ?: return null
        val start = json.indexOf('[', keyMatch.range.last + 1).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until json.length) {
            val char = json[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '[' -> depth++
                    ']' -> if (--depth == 0) return json.substring(start + 1, index)
                }
            }
        }
        return null
    }

    fun fileName(entry: String): String = entry
        .substringBefore('?')
        .replace('\\', '/')
        .substringAfterLast('/')
        .replace("%20", " ")
        .trim()

    fun normalizedPath(value: String): String = value.substringBefore('?').replace('\\', '/').replace("%20", " ").trim().lowercase()
}
