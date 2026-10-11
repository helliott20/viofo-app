package io.github.helliott20.dashlink.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI state that has to survive moving between screens, chiefly folding and unfolding: the
 * clip being watched and how far into it you are. Unfolded it plays in the Files pane;
 * folded it plays full screen; either way it carries on from the same moment.
 */
class UiState {
    private val _clip = MutableStateFlow<String?>(null)
    val clip: StateFlow<String?> = _clip.asStateFlow()

    /**
     * Playback position of [clip]. Kept when switching lens (front and rear show the same
     * moment) and when the clip moves between the full-screen player and the Files pane.
     */
    @Volatile var positionMs: Long = 0
        private set

    fun select(fpath: String?, keepPosition: Boolean = false) {
        if (!keepPosition || fpath == null) positionMs = 0
        _clip.value = fpath
    }

    fun clear() = select(null)

    fun report(ms: Long) { positionMs = ms }
}
