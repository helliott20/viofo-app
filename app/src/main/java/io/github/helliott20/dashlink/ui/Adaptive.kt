package io.github.helliott20.dashlink.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.window.core.layout.WindowWidthSizeClass

enum class WidthClass { Compact, Medium, Expanded }

/**
 * What the screens need to know about the window: its width class (phone, unfolded
 * foldable / small tablet, large tablet) and the fold posture. Screens restructure on
 * this rather than stretching the phone layout.
 */
data class LayoutInfo(
    val width: WidthClass,
    /** Half-folded with a horizontal hinge, like a laptop: e.g. stood on the dashboard. */
    val tabletop: Boolean = false,
    /** Hinge top/bottom edges in window pixels, when there is one. */
    val hingeTopPx: Int? = null,
    val hingeBottomPx: Int? = null,
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
    val hinge = info.windowPosture.hingeList.firstOrNull { !it.isVertical }
    return LayoutInfo(
        width = width,
        tabletop = info.windowPosture.isTabletop,
        hingeTopPx = hinge?.bounds?.top?.toInt(),
        hingeBottomPx = hinge?.bounds?.bottom?.toInt(),
    )
}
