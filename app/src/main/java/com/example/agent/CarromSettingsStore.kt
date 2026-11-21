package com.example.agent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Carrom-only settings store, extracted from TapPilot's
 * app/src/main/java/com/example/agent/AgentSettingsStore.kt (175 lines) at
 * commit ea02d43.
 *
 * ONLY the carrom settings were copied:
 * - the 5 carrom keys (~lines 36-40): carrom_precision_solver,
 *   carrom_rule_mode, carrom_difficulty, carrom_self_play_enabled,
 *   carrom_self_play_shots
 * - the 3 carrom solver/vision quality switches from the same save/load
 *   blocks: use_physics_rollout, use_subpixel_circle_refinement,
 *   use_closed_loop_slider_verify (these fields are part of the slimmed
 *   AgentSettings already in this repo)
 * - the carrom part of the save function (~lines 83-90) and of the load
 *   function (~lines 128-155), including the same fallbacks and the same
 *   24–480 clamp on carrom_self_play_shots.
 *
 * Key names and the DataStore file name ("tappilot_agent_settings") are
 * IDENTICAL to the original on purpose, so settings saved by TapPilot keep
 * working if this repo is used as the host. Everything non-carrom from the
 * original store (AI providers, API keys, messaging, safety policy, step
 * delays, overlays, …) stays out.
 *
 * The store works on the slimmed AgentSettings in this repo: non-carrom
 * fields simply keep their defaults, because this store never reads or
 * writes them.
 */
private val Context.carromSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tappilot_agent_settings"
)

class CarromSettingsStore(private val context: Context) {

    private object Keys {
        val CARROM_PRECISION_SOLVER = booleanPreferencesKey("carrom_precision_solver")
        val CARROM_RULE_MODE = stringPreferencesKey("carrom_rule_mode")
        val CARROM_DIFFICULTY = stringPreferencesKey("carrom_difficulty")
        val CARROM_SELF_PLAY_ENABLED = booleanPreferencesKey("carrom_self_play_enabled")
        val CARROM_SELF_PLAY_SHOTS = intPreferencesKey("carrom_self_play_shots")
        val USE_PHYSICS_ROLLOUT = booleanPreferencesKey("use_physics_rollout")
        val USE_SUBPIXEL_CIRCLE_REFINEMENT = booleanPreferencesKey("use_subpixel_circle_refinement")
        val USE_CLOSED_LOOP_SLIDER_VERIFY = booleanPreferencesKey("use_closed_loop_slider_verify")
    }

    /** Current carrom settings as a Flow (non-carrom fields stay default). */
    val settingsFlow: Flow<AgentSettings> = context.carromSettingsDataStore.data.map { prefs ->
        readCarromSettings(prefs)
    }

    /** Apply a transform to the carrom settings and persist only carrom keys. */
    suspend fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        context.carromSettingsDataStore.edit { prefs ->
            val current = readCarromSettings(prefs)
            val updated = transform(current)
            prefs[Keys.CARROM_PRECISION_SOLVER] = updated.carromPrecisionSolver
            prefs[Keys.CARROM_RULE_MODE] = updated.carromRuleMode.name
            prefs[Keys.CARROM_DIFFICULTY] = updated.carromDifficulty.name
            prefs[Keys.CARROM_SELF_PLAY_ENABLED] = updated.carromSelfPlayEnabled
            prefs[Keys.CARROM_SELF_PLAY_SHOTS] = updated.carromSelfPlayShots.coerceIn(24, 480)
            prefs[Keys.USE_PHYSICS_ROLLOUT] = updated.usePhysicsRollout
            prefs[Keys.USE_SUBPIXEL_CIRCLE_REFINEMENT] = updated.useSubPixelCircleRefinement
            prefs[Keys.USE_CLOSED_LOOP_SLIDER_VERIFY] = updated.useClosedLoopSliderVerify
        }
    }

    private fun readCarromSettings(prefs: Preferences): AgentSettings {
        val carromRuleMode = prefs[Keys.CARROM_RULE_MODE]
            ?.let { runCatching { CarromRuleMode.valueOf(it) }.getOrNull() }
            ?: CarromRuleMode.FREESTYLE

        val carromDifficulty = prefs[Keys.CARROM_DIFFICULTY]
            ?.let { runCatching { CarromDifficulty.valueOf(it) }.getOrNull() }
            ?: CarromDifficulty.MEDIUM

        return AgentSettings(
            carromPrecisionSolver = prefs[Keys.CARROM_PRECISION_SOLVER] ?: true,
            carromRuleMode = carromRuleMode,
            carromDifficulty = carromDifficulty,
            carromSelfPlayEnabled = prefs[Keys.CARROM_SELF_PLAY_ENABLED] ?: false,
            carromSelfPlayShots = (prefs[Keys.CARROM_SELF_PLAY_SHOTS] ?: 120).coerceIn(24, 480),
            usePhysicsRollout = prefs[Keys.USE_PHYSICS_ROLLOUT] ?: true,
            useSubPixelCircleRefinement = prefs[Keys.USE_SUBPIXEL_CIRCLE_REFINEMENT] ?: true,
            useClosedLoopSliderVerify = prefs[Keys.USE_CLOSED_LOOP_SLIDER_VERIFY] ?: true
        )
    }
}
