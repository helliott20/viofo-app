package io.github.helliott20.dashlink.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.window.core.layout.WindowHeightSizeClass
import androidx.window.core.layout.WindowWidthSizeClass

enum class WidthClass { Compact, Medium, Expanded }

/**
 * What the screens need to know about the window: its size classes and the fold posture.
 * Screens restructure on this rather than stretching the phone layout, and never look at
 * the device model, so any foldable or tablet gets the right layout.
 */
data class LayoutInfo(
    val width: WidthClass,
    /** Short window: landscape phone, or a split-screen half on an unfolded foldable. */
    val compactHeight: Boolean = false,
    /** Half-open with a horizontal hinge, like a laptop: e.g. stood on the dashboard. */
    val tabletop: Boolean = false,
    /** Half-open with a vertical hinge, like a book. */
    val book: Boolean = false,
    /** Horizontal hinge edges (tabletop) in window pixels. */
    val hingeTopPx: Int? = null,
    val hingeBottomPx: Int? = null,
    /** Vertical hinge edges (book) in window pixels. */
    val hingeLeftPx: Int? = null,
    val hingeRightPx: Int? = null,
) {
    val wide get() = width != WidthClass.Compact
}

/** Overridable for tests and previews; null means "ask the system". */
val LocalLayoutInfo = compositionLocalOf<LayoutInfo?> { null }

@Composable
fun layoutInfo(): LayoutInfo = LocalLayoutInfo.current ?: systemLayoutInfo()

@Composable
private fun systemLayoutInfo(): LayoutInfo {
    val info = currentWindowAdaptiveInfo()
    val width = when (info.windowSizeClass.windowWidthSizeClass) {
        WindowWidthSizeClass.EXPANDED -> WidthClass.Expanded
        WindowWidthSizeClass.MEDIUM -> WidthClass.Medium
        else -> WidthClass.Compact
    }
    val hinges = info.windowPosture.hingeList
    // Only a half-open (separating) hinge splits the content; a flat Fold is one seamless screen.
    val horizontal = hinges.firstOrNull { !it.isVertical && it.isSeparating }
    val vertical = hinges.firstOrNull { it.isVertical && it.isSeparating }
    return LayoutInfo(
        width = width,
        compactHeight = info.windowSizeClass.windowHeightSizeClass == WindowHeightSizeClass.COMPACT,
        tabletop = info.windowPosture.isTabletop && horizontal != null,
        book = vertical != null && !vertical.isFlat,
        hingeTopPx = horizontal?.bounds?.top?.toInt(),
        hingeBottomPx = horizontal?.bounds?.bottom?.toInt(),
        hingeLeftPx = vertical?.bounds?.left?.toInt(),
        hingeRightPx = vertical?.bounds?.right?.toInt(),
    )
}
