package com.ramen.god

import java.util.Locale

internal data class UnityExternalPathMatch<T>(
    val item: T?,
    val ambiguous: Boolean
)

/**
 * Resolves external SerializedFile paths against one pre-indexed inventory.
 *
 * The index preserves the previous conservative matching rules (full path or
 * unique suffix first, unique basename fallback) without rescanning the entire
 * inventory for every reference.
 */
internal object UnityExternalPathResolver {
    class Index<T> private constructor(
        private val candidates: List<T>,
        private val byPath: Map<String, Int>,
        private val byName: Map<String, Int>
    ) {
        fun exactPath(path: String): T? {
            val index = byPath[normalize(path)] ?: return null
            return if (index < 0) null else candidates[index]
        }

        fun resolve(
            externalPath: String,
            shouldCancel: () -> Boolean = { false }
        ): UnityExternalPathMatch<T> {
            var normalized = externalPath.trim().replace('\\', '/')
            val archiveMarker = normalized.indexOf("archive:/", ignoreCase = true)
            if (archiveMarker >= 0) {
                normalized = normalized.substring(archiveMarker + "archive:/".length)
            }
            normalized = normalize(normalized)
            if (normalized.isBlank()) return UnityExternalPathMatch(null, false)

            var matchedIndex: Int? = null
            var offset = 0
            while (offset <= normalized.length) {
                if (shouldCancel()) throw ScanCancelledException()
                val suffix = normalized.substring(offset)
                val index = byPath[suffix]
                if (index != null) {
                    if (index < 0) return UnityExternalPathMatch(null, true)
                    if (matchedIndex != null && matchedIndex != index) {
                        return UnityExternalPathMatch(null, true)
                    }
                    matchedIndex = index
                }
                val slash = normalized.indexOf('/', offset)
                if (slash < 0) break
                offset = slash + 1
            }
            if (matchedIndex != null) {
                return UnityExternalPathMatch(candidates[matchedIndex], false)
            }

            if (shouldCancel()) throw ScanCancelledException()
            val baseName = normalized.substringAfterLast('/')
            val nameIndex = byName[baseName] ?: return UnityExternalPathMatch(null, false)
            return if (nameIndex < 0) {
                UnityExternalPathMatch(null, true)
            } else {
                UnityExternalPathMatch(candidates[nameIndex], false)
            }
        }

        companion object {
            fun <T> create(
                candidates: List<T>,
                pathOf: (T) -> String,
                nameOf: (T) -> String,
                shouldCancel: () -> Boolean = { false }
            ): Index<T> {
                val byPath = HashMap<String, Int>(candidates.size)
                val byName = HashMap<String, Int>(candidates.size)

                fun recordUnique(map: MutableMap<String, Int>, key: String, index: Int) {
                    if (key.isBlank()) return
                    val previous = map[key]
                    if (previous == null) {
                        map[key] = index
                    } else if (previous != index) {
                        map[key] = AMBIGUOUS_INDEX
                    }
                }

                candidates.forEachIndexed { index, item ->
                    if (shouldCancel()) throw ScanCancelledException()
                    recordUnique(byPath, normalize(pathOf(item)), index)
                    recordUnique(byName, nameOf(item).lowercase(Locale.ROOT), index)
                }
                return Index(candidates, byPath, byName)
            }

            private const val AMBIGUOUS_INDEX = -1
        }
    }

    fun <T> resolve(
        externalPath: String,
        candidates: List<T>,
        pathOf: (T) -> String,
        nameOf: (T) -> String,
        shouldCancel: () -> Boolean = { false }
    ): UnityExternalPathMatch<T> =
        Index.create(candidates, pathOf, nameOf, shouldCancel)
            .resolve(externalPath, shouldCancel)

    private fun normalize(path: String): String =
        path.replace('\\', '/').trimStart('/').lowercase(Locale.ROOT)
}
