package io.legado.app.help

/** Small, bounded character diff used by the transient reading-note task view. */
object ReadingNoteDiff {
    enum class Type { EQUAL, DELETE, INSERT }

    data class Part(val type: Type, val text: String)

    fun meaningfulChange(before: String, after: String): Boolean =
        compact(before) != compact(after)

    fun parts(before: String, after: String, limit: Int = 700): List<Part> {
        val left = before.take(limit)
        val right = after.take(limit)
        val dp = Array(left.length + 1) { IntArray(right.length + 1) }
        for (i in left.length - 1 downTo 0) {
            for (j in right.length - 1 downTo 0) {
                dp[i][j] = if (left[i] == right[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        val result = ArrayList<Part>()
        fun append(type: Type, char: Char) {
            val previous = result.lastOrNull()
            if (previous?.type == type) {
                result[result.lastIndex] = previous.copy(text = previous.text + char)
            } else {
                result += Part(type, char.toString())
            }
        }
        var i = 0
        var j = 0
        while (i < left.length && j < right.length) {
            when {
                left[i] == right[j] -> {
                    append(Type.EQUAL, left[i])
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    append(Type.DELETE, left[i++])
                }
                else -> {
                    append(Type.INSERT, right[j++])
                }
            }
        }
        while (i < left.length) append(Type.DELETE, left[i++])
        while (j < right.length) append(Type.INSERT, right[j++])
        return result
    }

    fun before(parts: List<Part>): String = buildString {
        parts.filter { it.type != Type.INSERT }.forEach { append(it.text) }
    }

    fun after(parts: List<Part>): String = buildString {
        parts.filter { it.type != Type.DELETE }.forEach { append(it.text) }
    }

    private fun compact(text: String) =
        text.filterNot { it.isWhitespace() || it == '\u00a0' }
}
