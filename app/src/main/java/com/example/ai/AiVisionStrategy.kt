package com.example.ai

import com.example.agent.AgentSettings

/**
 * EXTRACTED STUB from Auto-Agent5's AI planner at commit ea02d43.
 *
 * - `AiTaskProfile` is copied VERBATIM from
 *   app/src/main/java/com/example/ai/ApiKeyFailoverManager.kt.
 * - `GeminiAiPlanner.resolveVisionStrategy` is copied VERBATIM from
 *   app/src/main/java/com/example/ai/GeminiAiPlanner.kt (companion
 *   function, lines ~611-636). It decides whether a step needs a
 *   HIGH_ACCURACY screenshot or can go FAST_UI without one.
 *
 * Why only this function: the carrom live loop forces vision on during a
 * match (`forceVision = true`) so the board screenshot is always taken
 * even when the HUD has many buttons — a normal UI screen with 8 buttons
 * would skip the screenshot. The rest of GeminiAiPlanner (prompt
 * building, model calls, key failover, planning) is the TapPilot agent's
 * AI brain and is NOT carrom — it stays in Auto-Agent5.
 *
 * NOTE: this stub's `resolveVisionStrategy` takes the repo's slimmed
 * `AgentSettings` (agent/AgentSettings.kt) — it only reads
 * `settings.smartTextFastPath`, exactly like the original.
 */

enum class AiTaskProfile {
    FAST_UI,
    BALANCED,
    HIGH_ACCURACY
}

object GeminiAiPlanner {

    fun resolveVisionStrategy(
        settings: AgentSettings,
        interactiveNodes: Int,
        lastOutcomeHint: String,
        stepNumber: Int,
        forceVision: Boolean
    ): Pair<AiTaskProfile, Boolean> {
        val wasPreviousStepFailed = lastOutcomeHint.contains("[status=FAILED]", ignoreCase = true) ||
            lastOutcomeHint.contains("[NO SCREEN CHANGE", ignoreCase = true) ||
            lastOutcomeHint.contains("foul", ignoreCase = true)
        val isSparseCanvasOrGame = interactiveNodes < 5 || forceVision
        val taskProfile = if (stepNumber <= 1 || isSparseCanvasOrGame || wasPreviousStepFailed) {
            AiTaskProfile.HIGH_ACCURACY
        } else {
            AiTaskProfile.FAST_UI
        }
        val attachScreenshot = !(
            !forceVision &&
                settings.smartTextFastPath &&
                taskProfile == AiTaskProfile.FAST_UI &&
                interactiveNodes >= 5 &&
                !wasPreviousStepFailed
            )
        return taskProfile to attachScreenshot
    }
}
