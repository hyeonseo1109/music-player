package com.hendo.hendomusic.library

/** Small, dependency-free reader for Samsung Music-style playlist exports. */
data class ImportedPlaylist(val name: String, val entries: List<String>)

object PlaylistImportCodec {
    fun parse(fileName: String, contents: String): ImportedPlaylist {
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

    fun fileName(entry: String): String = entry
        .substringBefore('?')
        .replace('\\', '/')
        .substringAfterLast('/')
        .replace("%20", " ")
        .trim()
}
