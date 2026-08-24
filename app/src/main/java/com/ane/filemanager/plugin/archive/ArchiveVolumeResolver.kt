package com.ane.filemanager.plugin.archive

import java.io.File

internal data class ResolvedArchiveSource(
    val primary: File,
    val parts: List<File>,
    val outputName: String
)

/** Archive-plugin-owned recognition for common multi-volume naming conventions. */
internal object ArchiveVolumeResolver {
    private val numberedArchive = Regex("(?i)^(.+\\.(?:zip|7z))\\.(\\d{3,})$")
    private val standardZipPart = Regex("(?i)^(.+)\\.z(\\d{2,})$")
    private val newRarPart = Regex("(?i)^(.+)\\.part(\\d+)\\.rar$")
    private val oldRarPart = Regex("(?i)^(.+)\\.r(\\d{2,})$")

    fun matchesName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(SINGLE_SUFFIXES) || numberedArchive.matches(name) ||
            standardZipPart.matches(name) || newRarPart.matches(name) || oldRarPart.matches(name)
    }

    fun resolve(file: File): ArchivePluginResult<ResolvedArchiveSource> {
        if (!file.exists()) return failure(ArchivePluginError.SOURCE_MISSING, file.name)
        val parent = file.parentFile ?: return failure(ArchivePluginError.UNSUPPORTED, file.name)
        val name = file.name
        numberedArchive.matchEntire(name)?.let { match ->
            val baseName = match.groupValues[1]
            val extensionWidth = match.groupValues[2].length
            val parts = numberedParts(parent, baseName, extensionWidth)
            validateNumbered(parts, 1) { number ->
                "$baseName.${number.padded(extensionWidth)}"
            }?.let { return it }
            val primary = parts.first().second
            return success(primary, parts.map(Pair<Int, File>::second),
                baseName.removeArchiveSuffix())
        }
        standardZipPart.matchEntire(name)?.let { match ->
            return resolveStandardZip(parent, match.groupValues[1])
        }
        newRarPart.matchEntire(name)?.let { match ->
            val baseName = match.groupValues[1]
            val pattern = Regex("(?i)^${Regex.escape(baseName)}\\.part(\\d+)\\.rar$")
            val parts = siblingParts(parent, pattern)
            val width = numberWidth(parts, pattern, match.groupValues[2].length)
            validateNumbered(parts, 1) { number ->
                "$baseName.part${number.padded(width)}.rar"
            }?.let { return it }
            return success(parts.first().second, parts.map(Pair<Int, File>::second), baseName)
        }
        oldRarPart.matchEntire(name)?.let { match ->
            return resolveOldRar(parent, match.groupValues[1])
        }

        val lower = name.lowercase()
        return when {
            lower.endsWith(".zip") && hasStandardZipParts(parent, name.dropLast(4)) ->
                resolveStandardZip(parent, name.dropLast(4))
            lower.endsWith(".rar") && hasOldRarParts(parent, name.dropLast(4)) ->
                resolveOldRar(parent, name.dropLast(4))
            lower.endsWith(SINGLE_SUFFIXES) -> success(
                file,
                listOf(file),
                ArchiveFormat.outputNameForSingle(name)
            )
            else -> failure(ArchivePluginError.UNSUPPORTED, name)
        }
    }

    private fun resolveStandardZip(parent: File, baseName: String): ArchivePluginResult<ResolvedArchiveSource> {
        val finalZip = findSibling(parent, "$baseName.zip")
            ?: return failure(ArchivePluginError.MISSING_VOLUME, "$baseName.zip")
        val pattern = Regex("(?i)^${Regex.escape(baseName)}\\.z(\\d{2,})$")
        val parts = siblingParts(parent, pattern)
        val width = numberWidth(parts, pattern, 2)
        validateNumbered(parts, 1) { number -> "$baseName.z${number.padded(width)}" }
            ?.let { return it }
        return success(finalZip, parts.map(Pair<Int, File>::second) + finalZip, baseName)
    }

    private fun resolveOldRar(parent: File, baseName: String): ArchivePluginResult<ResolvedArchiveSource> {
        val first = findSibling(parent, "$baseName.rar")
            ?: return failure(ArchivePluginError.MISSING_VOLUME, "$baseName.rar")
        val pattern = Regex("(?i)^${Regex.escape(baseName)}\\.r(\\d{2,})$")
        val following = siblingParts(parent, pattern)
        val width = numberWidth(following, pattern, 2)
        validateNumbered(following, 0) { number -> "$baseName.r${number.padded(width)}" }
            ?.let { return it }
        return success(first, listOf(first) + following.map(Pair<Int, File>::second), baseName)
    }

    private fun numberedParts(parent: File, baseName: String, width: Int): List<Pair<Int, File>> =
        siblingParts(parent, Regex("(?i)^${Regex.escape(baseName)}\\.(\\d{$width})$"))

    private fun siblingParts(parent: File, pattern: Regex): List<Pair<Int, File>> =
        parent.listFiles().orEmpty().mapNotNull { sibling ->
            val match = pattern.matchEntire(sibling.name) ?: return@mapNotNull null
            match.groupValues[1].toIntOrNull()?.let { it to sibling }
        }.sortedBy(Pair<Int, File>::first)

    private fun validateNumbered(
        parts: List<Pair<Int, File>>,
        firstNumber: Int,
        expectedName: (Int) -> String
    ): ArchivePluginResult.Failure? {
        if (parts.isEmpty() || parts.first().first != firstNumber) {
            return failure(ArchivePluginError.MISSING_VOLUME, expectedName(firstNumber))
        }
        parts.zipWithNext().firstOrNull { (left, right) -> right.first != left.first + 1 }
            ?.let { (left, _) ->
                return failure(ArchivePluginError.MISSING_VOLUME, expectedName(left.first + 1))
            }
        return null
    }

    private fun numberWidth(
        parts: List<Pair<Int, File>>,
        pattern: Regex,
        fallback: Int
    ): Int = parts.maxOfOrNull { (_, part) ->
        pattern.matchEntire(part.name)?.groupValues?.get(1)?.length ?: fallback
    } ?: fallback

    private fun hasStandardZipParts(parent: File, baseName: String) =
        parent.listFiles().orEmpty().any { standardZipPart.matches(it.name) &&
            standardZipPart.matchEntire(it.name)?.groupValues?.get(1).equals(baseName, true) }

    private fun hasOldRarParts(parent: File, baseName: String) =
        parent.listFiles().orEmpty().any { oldRarPart.matches(it.name) &&
            oldRarPart.matchEntire(it.name)?.groupValues?.get(1).equals(baseName, true) }

    private fun findSibling(parent: File, expectedName: String): File? =
        parent.listFiles().orEmpty().firstOrNull { it.name.equals(expectedName, ignoreCase = true) }

    private fun success(primary: File, parts: List<File>, outputName: String) =
        ArchivePluginResult.Success(ResolvedArchiveSource(primary, parts, outputName.ifBlank { "archive" }))

    private fun failure(error: ArchivePluginError, subject: String) =
        ArchivePluginResult.Failure(error, subject)

    private fun Int.padded(width: Int) = toString().padStart(width.coerceAtLeast(2), '0')

    private fun String.removeArchiveSuffix(): String = when {
        endsWith(".zip", true) -> dropLast(4)
        endsWith(".7z", true) -> dropLast(3)
        else -> this
    }

    private fun String.endsWith(suffixes: Set<String>) = suffixes.any { endsWith(it, true) }

    private val SINGLE_SUFFIXES = setOf(
        ".zip", ".zipx", ".7z", ".rar", ".tar", ".gz", ".gzip", ".tgz", ".tar.gz",
        ".bz2", ".bzip2", ".tbz", ".tbz2", ".tar.bz2", ".xz", ".txz", ".tar.xz"
    )
}
