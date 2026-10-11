package io.github.helliott20.dashlink.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Tabletop: [top] fills the upright half above the hinge, [bottom] the flat half below it.
 * The split follows the real hinge position, whatever sits above this layout.
 */
@Composable
fun HingeSplit(layout: LayoutInfo, top: @Composable (Modifier) -> Unit, bottom: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val hingeTop = layout.hingeTopPx ?: 0
    val hingeBottom = layout.hingeBottomPx ?: hingeTop
    Column(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val upper = with(density) { (hingeTop - origin.y).coerceAtLeast(0f).toDp() }
        val gap = with(density) { (hingeBottom - hingeTop).coerceAtLeast(0).toDp() }
        top(Modifier.fillMaxWidth().height(upper))
        Spacer(Modifier.height(gap))
        bottom(Modifier.fillMaxWidth().weight(1f))
    }
}

/**
 * Two panes side by side. Half-open like a book, the split sits exactly on the hinge so
 * neither pane is bent across it; otherwise the panes share the width by [firstWeight].
 */
@Composable
fun TwoPane(
    layout: LayoutInfo,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    firstWeight: Float = 1f,
) {
    val left = layout.hingeLeftPx
    if (layout.book && left != null) {
        val density = LocalDensity.current
        var origin by remember { mutableStateOf(Offset.Zero) }
        Row(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
            val firstWidth = with(density) { (left - origin.x).coerceAtLeast(0f).toDp() }
            val gap = with(density) { ((layout.hingeRightPx ?: left) - left).coerceAtLeast(0).toDp() }
            first(Modifier.width(firstWidth).fillMaxHeight())
            Spacer(Modifier.width(gap))
            second(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Row(modifier.fillMaxSize()) {
            first(Modifier.weight(firstWeight).fillMaxHeight())
            VerticalDivider()
            second(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** Helper for panes that just need a box. */
@Composable
fun Pane(modifier: Modifier, content: @Composable () -> Unit) = Box(modifier) { content() }
