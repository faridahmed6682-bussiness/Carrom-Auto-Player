package com.example.agent

/**
 * SLIMMED STUB of Auto-Agent5's AgentSettings
 * (app/src/main/java/com/example/agent/AgentModels.kt) at commit ea02d43.
 *
 * The real AgentSettings is the whole TapPilot agent's settings object
 * (AI providers, API keys, messaging, safety policy, …). This repo only
 * needs the handful of fields the carrom engine reads, so only those are
 * kept here — with the SAME field names and SAME defaults as the original,
 * so carrom code written against the original compiles unchanged.
 *
 * Fields and defaults (verbatim from the original):
 * - smartTextFastPath = true — used by the vision-strategy test
 *   (GeminiAiPlanner.resolveVisionStrategy).
 * - carromPrecisionSolver = true, carromRuleMode = FREESTYLE,
 *   carromDifficulty = MEDIUM — the solver's rule/difficulty settings.
 * - carromSelfPlayEnabled = false, carromSelfPlayShots = 120 — the
 *   self-play trainer switch (OFF by default; one idle sweep runs when on).
 * - usePhysicsRollout = true, useSubPixelCircleRefinement = true,
 *   useClosedLoopSliderVerify = true — solver/vision quality switches.
 *
 * Everything else from the original (providers, keys, messaging, safety,
 * step delays, …) is NOT carrom and is deliberately left out. If the host
 * app grows its own settings, keep these field names and defaults.
 */
data class AgentSettings(
    val smartTextFastPath: Boolean = true,
    val carromPrecisionSolver: Boolean = true,
    val carromRuleMode: CarromRuleMode = CarromRuleMode.FREESTYLE,
    val carromDifficulty: CarromDifficulty = CarromDifficulty.MEDIUM,
    // Self-play trainer: when on, one offline sweep runs while the app is
    // idle (launcher / settings screen) so the learned sure-shot table is
    // ready before the next match. Shots per sweep (direct + bank + combo).
    val carromSelfPlayEnabled: Boolean = false,
    val carromSelfPlayShots: Int = 120,
    val usePhysicsRollout: Boolean = true,
    val useSubPixelCircleRefinement: Boolean = true,
    val useClosedLoopSliderVerify: Boolean = true
)
