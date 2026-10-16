package io.legado.app.help

/** All bounds are in screen coordinates. Kept pure for edge and overlap regression tests. */
object HighlightPopupPosition {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
    data class Position(val x: Int, val y: Int)

    fun resolve(frame: Bounds, selection: Bounds, width: Int, height: Int, gap: Int): Position {
        val maxX = (frame.right - width).coerceAtLeast(frame.left)
        val maxY = (frame.bottom - height).coerceAtLeast(frame.top)
        val x = ((selection.left + selection.right - width) / 2).coerceIn(frame.left, maxX)
        val above = selection.top - gap - height
        val below = selection.bottom + gap
        val y = when {
            above >= frame.top -> above
            below + height <= frame.bottom -> below
            (selection.top - frame.top) >= (frame.bottom - selection.bottom) -> frame.top
            else -> maxY
        }.coerceIn(frame.top, maxY)
        return Position(x, y)
    }
}
