package io.legado.app.ui.about

/** The stored preference remains milliseconds; blank fields mean zero. */
internal fun readStatisticsMinimumMillis(hours: String, minutes: String, seconds: String): Long? {
    fun component(text: String): Long? {
        if (text.isEmpty()) return 0
        if (text.any { it !in '0'..'9' }) return null
        return text.toLongOrNull()
    }
    val h = component(hours) ?: return null
    val m = component(minutes)?.takeIf { it in 0..59 } ?: return null
    val s = component(seconds)?.takeIf { it in 0..59 } ?: return null
    val remainder = m * 60_000 + s * 1_000
    if (h > (Long.MAX_VALUE - remainder) / 3_600_000) return null
    return h * 3_600_000 + remainder
}
