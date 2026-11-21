package com.example.agent

/**
 * Screen-node types copied VERBATIM from Auto-Agent5
 * (app/src/main/java/com/example/agent/AgentModels.kt) at commit ea02d43.
 *
 * These are plain data classes describing one UI element on screen. The
 * carrom engine uses them only for one thing: telling a live match HUD
 * apart from a game lobby menu
 * (CarromContextHelper.isLikelyGameLobbyMenu). No accessibility service,
 * no screenshot capture — just the node list the host app passes in.
 *
 * NOT copied: UiSnapshot (needs android.graphics.Bitmap) — the host app
 * can define its own snapshot type and pass the node list directly.
 */

data class ScreenBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun toCompactString(): String = "[$left,$top,$right,$bottom]"
}

data class ScreenNode(
    val id: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val viewId: String,
    val bounds: ScreenBounds,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean
) {
    fun displayLabel(): String {
        return when {
            text.isNotBlank() -> text
            contentDesc.isNotBlank() -> contentDesc
            viewId.isNotBlank() -> viewId.substringAfterLast('/')
            else -> className.substringAfterLast('.')
        }
    }
}
