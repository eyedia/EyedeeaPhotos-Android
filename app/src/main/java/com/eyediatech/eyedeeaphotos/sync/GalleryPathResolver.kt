package com.eyediatech.eyedeeaphotos.sync

object GalleryPathResolver {

    private val INVALID_SEGMENT_CHARS = Regex("""[<>:"|?*\\]""")

    /**
     * Converts a server curated path to MediaStore RELATIVE_PATH (includes Pictures/ prefix).
     *
     * Input:  "curated/2021-2025/2025/Weekend Trip"
     * Output: "Pictures/Eyedeea/2021-2025/2025/Weekend Trip"
     */
    fun curatedPathToRelativePath(curatedFolderPath: String): String {
        val segments = curatedPathToSegments(curatedFolderPath)
        return (listOf("Pictures", "Eyedeea") + segments).joinToString("/")
    }

    /**
     * Strips optional "curated/" prefix and returns sanitized path segments.
     */
    fun curatedPathToSegments(curatedFolderPath: String): List<String> {
        var path = curatedFolderPath.trim().replace('\\', '/')
        while (path.startsWith("/")) path = path.removePrefix("/")
        if (path.equals("curated", ignoreCase = true)) return emptyList()
        if (path.startsWith("curated/", ignoreCase = true)) {
            path = path.removePrefix("curated/")
        }
        return path.split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("curated", ignoreCase = true) }
            .map { sanitizeSegment(it) }
    }

    fun sanitizeSegment(segment: String): String {
        return segment
            .replace(INVALID_SEGMENT_CHARS, "_")
            .trim()
            .ifEmpty { "_" }
    }
}
