package io.legado.app.ui.book.read.page

/** Half-open chapter coordinates; a column can contain a surrogate pair or HTML text. */
internal data class SelectionSpan(val start: Int, val end: Int) {
    init {
        require(start >= 0 && end > start)
    }
}

internal class SelectionRange(start: SelectionSpan, end: SelectionSpan) {
    var first = start
        private set
    var last = end
        private set
    private var fixed = start
    private var movingStart = false
    val start get() = first.start
    val end get() = last.end
    val reversed get() = movingStart != (first !== fixed)

    fun begin(moveStart: Boolean) {
        movingStart = moveStart
        fixed = if (moveStart) last else first
    }

    fun move(point: SelectionSpan) {
        if (point.start < fixed.start) {
            first = point
            last = fixed
        } else {
            first = fixed
            last = point
        }
    }

    fun contains(point: SelectionSpan) = point.start >= start && point.end <= end
}

/** One pending deadline, independent of whether another ACTION_MOVE arrives. */
internal class SelectionEdgeWait {
    var direction = 0
        private set
    var deadline = 0L
        private set

    fun update(next: Int, now: Long) {
        if (next != direction) {
            direction = next
            deadline = if (next == 0) 0L else now + FIRST_WAIT
        }
    }

    fun repeat(now: Long, delay: Long = REPEAT_WAIT) {
        deadline = now + delay
    }

    fun reset() {
        direction = 0
        deadline = 0L
    }

    companion object {
        const val FIRST_WAIT = 800L
        const val REPEAT_WAIT = 1000L

        fun direction(
            x: Float, y: Float,
            left: Float, top: Float, right: Float, bottom: Float,
            cornerWidth: Float, cornerHeight: Float,
        ): Int {
            if (right <= left || bottom <= top ||
                x !in left..right || y !in top..bottom
            ) return 0
            val width = cornerWidth.coerceAtMost((right - left) / 2)
            val height = cornerHeight.coerceAtMost((bottom - top) / 2)
            return when {
                x <= left + width && y <= top + height -> -1
                x >= right - width && y >= bottom - height -> 1
                else -> 0
            }
        }
    }
}
