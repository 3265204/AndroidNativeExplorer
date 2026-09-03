package com.ane.filemanager.update

/** Version comparison for GitHub tags such as v0.4.0 and 0.4.0-beta.1. */
internal object AppVersion {
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    fun compare(left: String, right: String): Int {
        val leftVersion = parse(left)
        val rightVersion = parse(right)
        val componentCount = maxOf(leftVersion.numbers.size, rightVersion.numbers.size)
        repeat(componentCount) { index ->
            val result = (leftVersion.numbers.getOrNull(index) ?: 0)
                .compareTo(rightVersion.numbers.getOrNull(index) ?: 0)
            if (result != 0) return result
        }
        if (leftVersion.preRelease == null && rightVersion.preRelease != null) return 1
        if (leftVersion.preRelease != null && rightVersion.preRelease == null) return -1
        return comparePreRelease(leftVersion.preRelease, rightVersion.preRelease)
    }

    private fun parse(value: String): ParsedVersion {
        val normalized = value.trim().removePrefix("v").removePrefix("V").substringBefore('+')
        val core = normalized.substringBefore('-')
        return ParsedVersion(
            numbers = core.split('.').map { component ->
                component.takeWhile(Char::isDigit).toIntOrNull() ?: 0
            },
            preRelease = normalized.substringAfter('-', "").takeIf(String::isNotEmpty)
        )
    }

    private fun comparePreRelease(left: String?, right: String?): Int {
        if (left == null || right == null) return 0
        val leftParts = left.split('.')
        val rightParts = right.split('.')
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val leftPart = leftParts.getOrNull(index) ?: return -1
            val rightPart = rightParts.getOrNull(index) ?: return 1
            val leftNumber = leftPart.toIntOrNull()
            val rightNumber = rightPart.toIntOrNull()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> leftPart.compareTo(rightPart, ignoreCase = true)
            }
            if (result != 0) return result
        }
        return 0
    }

    private data class ParsedVersion(val numbers: List<Int>, val preRelease: String?)
}
