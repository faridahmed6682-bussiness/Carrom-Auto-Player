package com.example.game

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.example.agent.AgentKnowledgeStore
import com.example.agent.TaskPlaybook
import java.io.File
import java.util.Locale

private const val CARROM_MEMORY_FILE = "tappilot_carrom_memory.json"
private const val CARROM_BUNDLE_FORMAT = "tappilot_carrom_training_bundle_v1"
private const val MAX_RECENT_LESSONS = 16
private const val MAX_FOUL_SIGNATURES = 250
private const val MAX_CLEAN_SHOT_PATTERNS = 120
private const val MAX_COLLISION_RISKS = 140

enum class CarromAngleBucket(val label: String) {
    STRAIGHT("Straight (0-14°)"),
    MEDIUM_CUT("Medium Cut (15-34°)"),
    SHARP_CUT("Sharp Cut (35-65°)");

    companion object {
        fun fromAngle(cutAngleDeg: Float): CarromAngleBucket = when {
            cutAngleDeg < 15f -> STRAIGHT
            cutAngleDeg < 35f -> MEDIUM_CUT
            else -> SHARP_CUT
        }
    }
}

/**
 * Total shot distance (striker -> coin -> pocket) as a fraction of board width.
 * Learned power/confidence is tracked per (pocket, angle, distance) band, because
 * the same pocket + cut needs very different power at short vs long range.
 * distRatio comes from PendingCarromShotRecord (0.15..1.8, ~0.7 typical).
 */
enum class CarromDistanceBand(val label: String) {
    SHORT("Short (<0.55)"),
    MEDIUM("Medium (0.55-1.0)"),
    LONG("Long (>1.0)");

    companion object {
        fun fromDistRatio(distRatio: Float): CarromDistanceBand = when {
            distRatio < 0.55f -> SHORT
            distRatio <= 1.0f -> MEDIUM
            else -> LONG
        }
    }
}

enum class CarromPowerStyle(val label: String) {
    SOFT("Soft / Gentle"),
    MEDIUM("Medium"),
    HARD("Hard / Power");

    companion object {
        fun fromPullMultiplier(effectiveMultiplier: Float): CarromPowerStyle = when {
            effectiveMultiplier < 0.88f -> SOFT
            effectiveMultiplier > 1.15f -> HARD
            else -> MEDIUM
        }
    }
}

enum class CarromShotOutcomeType {
    POCKETED_MY_COIN,
    POCKETED_QUEEN,
    FOUL_SCRATCH_OR_PENALTY,
    MISSED_OR_NO_POT
}

data class PocketShotStats(
    val key: String,
    val pocketIndex: Int,
    val angleBucket: CarromAngleBucket,
    val attempts: Int = 0,
    val successes: Int = 0,
    val fouls: Int = 0,
    val misses: Int = 0,
    val learnedPowerScale: Float = 1.0f
) {
    fun confidenceAdjustment(): Float {
        if (attempts == 0) return 0f
        val successRate = successes.toFloat() / attempts.toFloat()
        val foulRate = fouls.toFloat() / attempts.toFloat()
        return ((successRate - 0.35f) * 0.24f - foulRate * 0.32f).coerceIn(-0.24f, 0.20f)
    }
}

data class StrikerPlacementStats(
    val movedAttempts: Int = 0,
    val movedSuccesses: Int = 0,
    val movedFouls: Int = 0,
    val inPlaceAttempts: Int = 0,
    val inPlaceSuccesses: Int = 0,
    val inPlaceFouls: Int = 0
) {
    fun inPlaceBonusAdjustment(): Float {
        if (inPlaceAttempts < 2 && movedAttempts < 2) return 0f
        val inPlaceRate = if (inPlaceAttempts > 0) {
            (inPlaceSuccesses - inPlaceFouls * 1.3f) / inPlaceAttempts.toFloat()
        } else {
            0f
        }
        val movedRate = if (movedAttempts > 0) {
            (movedSuccesses - movedFouls * 1.3f) / movedAttempts.toFloat()
        } else {
            0f
        }
        return ((inPlaceRate - movedRate) * 0.14f).coerceIn(-0.10f, 0.14f)
    }
}

data class ScratchZoneHeatmap(
    val zoneRiskByKey: Map<String, Float> = emptyMap()
) {
    fun riskFor(
        pocketIndex: Int,
        cutAngleDeg: Float,
        strikerNormalizedX: Float
    ): Float {
        val zoneKey = zoneKey(pocketIndex, cutAngleDeg, strikerNormalizedX)
        val pocketKey = "P${pocketIndex.coerceIn(0, 3)}"
        val zoneRisk = zoneRiskByKey[zoneKey] ?: 0f
        val pocketBaseRisk = (zoneRiskByKey[pocketKey] ?: 0f) * 0.45f
        return (zoneRisk + pocketBaseRisk).coerceIn(0f, 0.85f)
    }

    fun recordFoul(
        pocketIndex: Int,
        cutAngleDeg: Float,
        strikerNormalizedX: Float
    ): ScratchZoneHeatmap {
        val next = zoneRiskByKey.toMutableMap()
        val zKey = zoneKey(pocketIndex, cutAngleDeg, strikerNormalizedX)
        val pKey = "P${pocketIndex.coerceIn(0, 3)}"
        next[zKey] = ((next[zKey] ?: 0f) + 0.28f).coerceIn(0f, 0.85f)
        next[pKey] = ((next[pKey] ?: 0f) + 0.14f).coerceIn(0f, 0.55f)
        return copy(zoneRiskByKey = next)
    }

    fun recordCleanSuccess(
        pocketIndex: Int,
        cutAngleDeg: Float,
        strikerNormalizedX: Float
    ): ScratchZoneHeatmap {
        val next = zoneRiskByKey.toMutableMap()
        val zKey = zoneKey(pocketIndex, cutAngleDeg, strikerNormalizedX)
        val pKey = "P${pocketIndex.coerceIn(0, 3)}"
        next[zKey]?.let { prev ->
            val decayed = (prev - 0.10f).coerceAtLeast(0f)
            if (decayed <= 0.01f) next.remove(zKey) else next[zKey] = decayed
        }
        next[pKey]?.let { prev ->
            val decayed = (prev - 0.05f).coerceAtLeast(0f)
            if (decayed <= 0.01f) next.remove(pKey) else next[pKey] = decayed
        }
        return copy(zoneRiskByKey = next)
    }

    companion object {
        fun zoneKey(pocketIndex: Int, cutAngleDeg: Float, strikerNormalizedX: Float): String {
            val sector = when {
                strikerNormalizedX < 0.36f -> "LEFT"
                strikerNormalizedX > 0.64f -> "RIGHT"
                else -> "MID"
            }
            val bucket = CarromAngleBucket.fromAngle(cutAngleDeg).name
            return "P${pocketIndex.coerceIn(0, 3)}_${sector}_$bucket"
        }
    }
}

data class MatchPhysicsCalibration(
    val frictionScale: Float = 1.0f,
    val pocketJawSafetyBuffer: Float = 0f,
    val sliderGain: Float = 1.0f,
    val shotPowerGain: Float = 1.0f,
    val sliderSamples: Int = 0,
    val shotsThisMatch: Int = 0
)

data class CarromFoulSignature(
    val signatureKey: String,
    val pocketIndex: Int,
    val strikerSector: String,
    val coinRowZone: Int,
    val coinColZone: Int,
    val angleBucket: CarromAngleBucket,
    val powerStyle: CarromPowerStyle,
    val foulCount: Int = 1,
    val cleanSuccessCount: Int = 0,
    val lastFoulMs: Long = 0L
) {
    fun isBlocked(): Boolean = foulCount >= 1 && foulCount > cleanSuccessCount
}

data class CorridorObstacleObservation(
    val segment: String, // "STRIKER_TO_COIN" or "COIN_TO_POCKET"
    val alongRatio: Float,
    val lateralOffsetInRadii: Float,
    val displacedOrKissed: Boolean,
    val obstacleColor: CoinColor = CoinColor.BLACK
)

data class CleanShotPattern(
    val patternId: String,
    val pocketIndex: Int,
    val strikerNormalizedX: Float,
    val coinNormX: Float,
    val coinNormY: Float,
    val cutAngleDeg: Float,
    val distRatio: Float,
    val powerStyle: CarromPowerStyle,
    val observedPullScale: Float = 1.0f,
    val minCorridorClearanceRadii: Float = 3.5f,
    val wasCollisionAssisted: Boolean = false,
    val successCount: Int = 1,
    val lastUsedMs: Long = 0L,
    // Shot kind: a bank pot must never be matched as a direct pot (and vice
    // versa) — the geometry looks similar but the physics is different.
    val isBankShot: Boolean = false,
    val isComboShot: Boolean = false,
    // Board profile this pattern was learned on ("" = global / learned
    // before profiles existed). Patterns only match on their own board.
    val profileName: String = ""
)

data class CollisionRiskPattern(
    val riskKey: String,
    val pocketIndex: Int,
    val angleBucket: CarromAngleBucket,
    val corridorSegment: String,
    val blockerAlongBucket: Int, // 0..3 along segment
    val blockerLateralInRadii: Float, // 0..3.5R
    val foulOrMissCount: Int = 1,
    val cleanPotDespiteNearCount: Int = 0,
    val lastSeenMs: Long = 0L
) {
    fun riskScore(): Float {
        val net = foulOrMissCount - cleanPotDespiteNearCount * 0.8f
        if (net <= 0f) return 0f
        return (0.28f + net * 0.16f).coerceIn(0f, 0.92f)
    }
}

data class TrainingMatchResult(
    val matchScore: Float,
    val pattern: CleanShotPattern,
    val collisionRiskPenalty: Float,
    val recommendedPullScale: Float
)

data class CarromMemoryData(
    val statsByBucket: Map<String, PocketShotStats> = emptyMap(),
    val placementStats: StrikerPlacementStats = StrikerPlacementStats(),
    val scratchHeatmap: ScratchZoneHeatmap = ScratchZoneHeatmap(),
    val matchCalibration: MatchPhysicsCalibration = MatchPhysicsCalibration(),
    val foulSignatures: Map<String, CarromFoulSignature> = emptyMap(),
    val cleanShotPatterns: List<CleanShotPattern> = emptyList(),
    val collisionRisks: List<CollisionRiskPattern> = emptyList(),
    val boardCalibration: BoardCalibrationProfile = BoardCalibrationProfile(),
    // Named board profiles saved from the Board Calibration & Aim Lab
    // (screenshot + pocket alignment + colour picking + Test Aim).
    // boardCalibration above is the ACTIVE profile (for backwards compat).
    val boardProfiles: List<BoardCalibrationProfile> = emptyList(),
    val activeBoardProfileName: String = "",
    // Learned shot table PER board profile: profile name -> (bucket key ->
    // stats). A shot that was sure on one board's friction is never trusted
    // blindly on another board — each board learns its own table, and the
    // live match calibration still scales power/confidence at shot time.
    val profileStats: Map<String, Map<String, PocketShotStats>> = emptyMap(),
    val selfPlayShotsSimulated: Int = 0,
    val selfPlaySureShotsFound: Int = 0,
    val straightFoulCount: Int = 0,
    val recentTactics: List<String> = emptyList(),
    val recentFouls: List<String> = emptyList(),
    val lastUpdatedMs: Long = 0L
) {
    fun blockedFoulCount(): Int = foulSignatures.values.count { it.isBlocked() }

    fun isFoulBlacklisted(
        board: CarromBoardLayout,
        coinCenter: Point2D,
        strikerPos: Point2D,
        pocketIndex: Int,
        cutAngleDeg: Float,
        powerStyle: CarromPowerStyle
    ): Boolean {
        val (rowZone, colZone) = coinZoneFor(coinCenter, board)
        val normStrikerX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
        return isFoulBlacklisted(
            pocketIndex = pocketIndex,
            strikerNormalizedX = normStrikerX,
            coinRowZone = rowZone,
            coinColZone = colZone,
            cutAngleDeg = cutAngleDeg,
            powerStyle = powerStyle
        )
    }

    fun isFoulBlacklisted(
        pocketIndex: Int,
        strikerNormalizedX: Float,
        coinRowZone: Int,
        coinColZone: Int,
        cutAngleDeg: Float,
        powerStyle: CarromPowerStyle
    ): Boolean {
        if (foulSignatures.isEmpty() || coinRowZone !in 0..3 || coinColZone !in 0..3) return false
        val sector = strikerSectorFor(strikerNormalizedX)
        val bucket = CarromAngleBucket.fromAngle(cutAngleDeg)
        val exactKey = foulSignatureKey(
            pocketIndex = pocketIndex,
            strikerSector = sector,
            coinRowZone = coinRowZone,
            coinColZone = coinColZone,
            angleBucket = bucket,
            powerStyle = powerStyle
        )
        if (foulSignatures[exactKey]?.isBlocked() == true) {
            return true
        }
        // If repeated spatial fouls (>= 2 net fouls) happen across powers at this exact board zone & cut bucket, block all powers.
        var spatialFouls = 0
        var spatialClean = 0
        for (pwr in CarromPowerStyle.entries) {
            val k = foulSignatureKey(pocketIndex, sector, coinRowZone, coinColZone, bucket, pwr)
            foulSignatures[k]?.let { sig ->
                spatialFouls += sig.foulCount
                spatialClean += sig.cleanSuccessCount
            }
        }
        return spatialFouls >= 2 && spatialFouls > spatialClean + 1
    }
    /**
     * The learned table for the active board profile, if any. Lookups check
     * this table FIRST and fall back to the global table, so a board that
     * has no learned data yet still benefits from older/global learning —
     * but a board WITH its own data is never polluted by another board's.
     */
    fun activeProfileStats(): Map<String, PocketShotStats> =
        if (activeBoardProfileName.isNotBlank()) {
            profileStats[activeBoardProfileName] ?: emptyMap()
        } else {
            emptyMap()
        }

    fun statsFor(pocketIndex: Int, cutAngleDeg: Float, distRatio: Float = 0.7f): PocketShotStats? {
        val bucket = CarromAngleBucket.fromAngle(cutAngleDeg)
        val bandedKey = bucketKey(pocketIndex, bucket, distRatio)
        val legacyKey = "P${pocketIndex.coerceIn(0, 3)}_${bucket.name}"
        // Active board profile first, then the global table.
        val profileMap = activeProfileStats()
        return profileMap[bandedKey] ?: profileMap[legacyKey]
            // Exact (pocket, angle, distance-band) bucket first; fall back to the
            // legacy unbanded key so stats learned before distance banding still count.
            ?: statsByBucket[bandedKey]
            ?: statsByBucket[legacyKey]
    }

    /**
     * Global table overlaid with the active profile's table (profile wins
     * per bucket key). Used for sibling-band fallbacks so a bucket is never
     * counted twice.
     */
    fun mergedStatsValues(): Collection<PocketShotStats> {
        val merged = statsByBucket.toMutableMap()
        merged.putAll(activeProfileStats())
        return merged.values
    }

    fun learnedPowerScaleFor(pocketIndex: Int, cutAngleDeg: Float, distRatio: Float = 0.7f): Float {
        val exact = statsFor(pocketIndex, cutAngleDeg, distRatio)
        if (exact != null && exact.attempts > 0) return exact.learnedPowerScale
        // No data for this distance band yet: average the other bands of the
        // same pocket + angle bucket (weighted by attempts) instead of 1.0.
        // Sibling search covers the active profile table AND the global one.
        val bucket = CarromAngleBucket.fromAngle(cutAngleDeg)
        val siblings = mergedStatsValues().filter {
            it.pocketIndex == pocketIndex.coerceIn(0, 3) && it.angleBucket == bucket && it.attempts > 0
        }
        if (siblings.isEmpty()) return 1.0f
        val weighted = siblings.sumOf { it.learnedPowerScale.toDouble() * it.attempts }
        val total = siblings.sumOf { it.attempts }
        return (weighted / total.toDouble()).toFloat().coerceIn(0.72f, 1.38f)
    }

    fun confidenceBiasFor(pocketIndex: Int, cutAngleDeg: Float, distRatio: Float = 0.7f): Float {
        val exact = statsFor(pocketIndex, cutAngleDeg, distRatio)
        if (exact != null && exact.attempts > 0) return exact.confidenceAdjustment()
        val bucket = CarromAngleBucket.fromAngle(cutAngleDeg)
        val siblings = mergedStatsValues().filter {
            it.pocketIndex == pocketIndex.coerceIn(0, 3) && it.angleBucket == bucket && it.attempts > 0
        }
        if (siblings.isEmpty()) return 0f
        return siblings.map { it.confidenceAdjustment() }.average().toFloat().coerceIn(-0.24f, 0.20f)
    }

    fun needsExtraScratchGuard(): Boolean = straightFoulCount >= 1

    fun matchCleanShotPattern(
        board: CarromBoardLayout,
        strikerPos: Point2D,
        coinCenter: Point2D,
        pocketIndex: Int,
        cutAngleDeg: Float,
        distRatio: Float,
        obstacles: List<CarromPiece>,
        targetCoin: CarromPiece,
        ghostBall: Point2D,
        pocketPos: Point2D,
        // Shot kind being solved for: a bank pattern must never be offered
        // for a direct shot (and vice versa). Defaults keep the two existing
        // direct-shot call sites unchanged.
        isBankShot: Boolean = false,
        isComboShot: Boolean = false
    ): TrainingMatchResult? {
        if (cleanShotPatterns.isEmpty()) return null
        val strikerNormX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
        val coinNormX = ((coinCenter.x - board.left) / board.width).coerceIn(0f, 1f)
        val coinNormY = ((coinCenter.y - board.top) / board.height).coerceIn(0f, 1f)

        val collisionPenalty = corridorCollisionRiskPenalty(
            board = board,
            strikerPos = strikerPos,
            ghostBall = ghostBall,
            coinCenter = coinCenter,
            pocketPos = pocketPos,
            pocketIndex = pocketIndex,
            cutAngleDeg = cutAngleDeg,
            obstacles = obstacles,
            excludedCoin = targetCoin
        )

        val minCurrentClearance = computeMinCorridorClearanceRadii(
            board = board,
            strikerPos = strikerPos,
            ghostBall = ghostBall,
            coinCenter = coinCenter,
            pocketPos = pocketPos,
            obstacles = obstacles,
            excludedCoin = targetCoin
        )

        var best: TrainingMatchResult? = null
        for (pattern in cleanShotPatterns) {
            if (pattern.pocketIndex != pocketIndex) continue
            // Same shot kind only: bank/combo patterns never match a direct
            // shot, and direct patterns never match a bank/combo.
            if (pattern.isBankShot != isBankShot || pattern.isComboShot != isComboShot) continue
            // Same board only: a pattern learned on one board never matches
            // on another. "" = global pattern from before profiles existed.
            if (pattern.profileName.isNotBlank() && pattern.profileName != activeBoardProfileName) continue
            val angleDiff = kotlin.math.abs(pattern.cutAngleDeg - cutAngleDeg)
            if (angleDiff > 16f) continue
            val coinDist = kotlin.math.hypot(pattern.coinNormX - coinNormX, pattern.coinNormY - coinNormY)
            if (coinDist > 0.28f) continue
            val strikerDiff = kotlin.math.abs(pattern.strikerNormalizedX - strikerNormX)
            if (strikerDiff > 0.26f) continue

            val angleSim = (1f - angleDiff / 16f).coerceIn(0f, 1f)
            val coinSim = (1f - coinDist / 0.28f).coerceIn(0f, 1f)
            val strikerSim = (1f - strikerDiff / 0.26f).coerceIn(0f, 1f)
            val distSim = (1f - (kotlin.math.abs(pattern.distRatio - distRatio) / 0.65f)).coerceIn(0f, 1f)
            val repBonus = (pattern.successCount.coerceAtMost(5) * 0.04f)
            val clearanceBonus = if (minCurrentClearance >= pattern.minCorridorClearanceRadii * 0.9f) 0.08f else -0.14f

            val rawScore = (angleSim * 0.36f + coinSim * 0.26f + strikerSim * 0.22f + distSim * 0.16f + repBonus + clearanceBonus - collisionPenalty * 0.55f)
                .coerceIn(0f, 1f)
            // Combos stay lower-confidence than direct sure shots: a small
            // contact-point error changes a combo outcome, so a combo pattern
            // can never outrank a strong direct pattern. Banks sit between.
            val cappedScore = when {
                isComboShot -> rawScore.coerceAtMost(0.80f)
                isBankShot -> rawScore.coerceAtMost(0.88f)
                else -> rawScore
            }

            if (cappedScore >= 0.44f && (best == null || cappedScore > best.matchScore)) {
                best = TrainingMatchResult(
                    matchScore = cappedScore,
                    pattern = pattern,
                    collisionRiskPenalty = collisionPenalty,
                    recommendedPullScale = pattern.observedPullScale
                )
            }
        }
        return best
    }

    fun corridorCollisionRiskPenalty(
        board: CarromBoardLayout,
        strikerPos: Point2D,
        ghostBall: Point2D,
        coinCenter: Point2D,
        pocketPos: Point2D,
        pocketIndex: Int,
        cutAngleDeg: Float,
        obstacles: List<CarromPiece>,
        excludedCoin: CarromPiece?
    ): Float {
        if (collisionRisks.isEmpty() || obstacles.isEmpty()) return 0f
        val bucket = CarromAngleBucket.fromAngle(cutAngleDeg)
        val relevant = collisionRisks.filter { it.pocketIndex == pocketIndex && it.angleBucket == bucket && it.riskScore() > 0.05f }
        if (relevant.isEmpty()) return 0f

        val coinR = board.coinRadiusPx.coerceAtLeast(10f)
        var maxPenalty = 0f

        for (obs in obstacles) {
            if (excludedCoin != null &&
                kotlin.math.abs(obs.center.x - excludedCoin.center.x) < 2f &&
                kotlin.math.abs(obs.center.y - excludedCoin.center.y) < 2f
            ) continue

            val s1 = projectOntoSegment(obs.center, strikerPos, ghostBall, coinR)
            val s2 = projectOntoSegment(obs.center, coinCenter, pocketPos, coinR)

            for (risk in relevant) {
                val segProj = if (risk.corridorSegment == "STRIKER_TO_COIN") s1 else s2
                if (segProj != null) {
                    val alongBucket = (segProj.first * 4f).toInt().coerceIn(0, 3)
                    if (alongBucket == risk.blockerAlongBucket &&
                        segProj.second <= (risk.blockerLateralInRadii + 0.65f)
                    ) {
                        maxPenalty = maxOf(maxPenalty, risk.riskScore() * 0.72f)
                    }
                }
            }
        }
        return maxPenalty.coerceIn(0f, 0.70f)
    }

    private fun computeMinCorridorClearanceRadii(
        board: CarromBoardLayout,
        strikerPos: Point2D,
        ghostBall: Point2D,
        coinCenter: Point2D,
        pocketPos: Point2D,
        obstacles: List<CarromPiece>,
        excludedCoin: CarromPiece
    ): Float {
        val coinR = board.coinRadiusPx.coerceAtLeast(10f)
        var minRadii = 5.0f
        for (obs in obstacles) {
            if (kotlin.math.abs(obs.center.x - excludedCoin.center.x) < 2f &&
                kotlin.math.abs(obs.center.y - excludedCoin.center.y) < 2f
            ) continue
            projectOntoSegment(obs.center, strikerPos, ghostBall, coinR)?.let {
                if (it.second < minRadii) minRadii = it.second
            }
            projectOntoSegment(obs.center, coinCenter, pocketPos, coinR)?.let {
                if (it.second < minRadii) minRadii = it.second
            }
        }
        return minRadii
    }

    private fun projectOntoSegment(
        point: Point2D,
        start: Point2D,
        end: Point2D,
        coinRadiusPx: Float
    ): Pair<Float, Float>? {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1f) return null
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lenSq)
        if (t < 0.04f || t > 0.96f) return null
        val projX = start.x + dx * t
        val projY = start.y + dy * t
        val latRadii = kotlin.math.hypot(point.x - projX, point.y - projY) / coinRadiusPx
        return t to latRadii
    }

    fun formatForPrompt(): String {
        if (statsByBucket.isEmpty() && foulSignatures.isEmpty() && recentTactics.isEmpty() && recentFouls.isEmpty()) {
            return ""
        }
        val bestBuckets = statsByBucket.values
            .filter { it.successes > 0 }
            .sortedByDescending { it.successes - it.fouls }
            .take(4)
            .joinToString("\n") { st ->
                "- ${pocketName(st.pocketIndex)} | ${st.angleBucket.label}: ${st.successes}/${st.attempts} potted, powerScale=${String.format(Locale.US, "%.2f", st.learnedPowerScale)}"
            }
        val foulBuckets = statsByBucket.values
            .filter { it.fouls > 0 }
            .sortedByDescending { it.fouls }
            .take(4)
            .joinToString("\n") { st ->
                "- AVOID ${pocketName(st.pocketIndex)} | ${st.angleBucket.label}: ${st.fouls} foul(s)"
            }
        val blockedSigsText = foulSignatures.values
            .filter { it.isBlocked() }
            .sortedByDescending { it.foulCount }
            .take(5)
            .joinToString("\n") { sig ->
                "- BLOCKED: ${pocketName(sig.pocketIndex)} | Striker=${sig.strikerSector} | Zone=(r${sig.coinRowZone},c${sig.coinColZone}) | ${sig.angleBucket.label} | ${sig.powerStyle.label} (${sig.foulCount} foul(s))"
            }
        val placementNote = buildString {
            val ps = placementStats
            if (ps.movedAttempts + ps.inPlaceAttempts > 0) {
                append(
                    "Striker Placement History: Slide-Move ${ps.movedSuccesses}/${ps.movedAttempts} (${ps.movedFouls} fouls) vs Shoot-In-Place ${ps.inPlaceSuccesses}/${ps.inPlaceAttempts} (${ps.inPlaceFouls} fouls)."
                )
            }
        }
        val tacticsText = recentTactics.takeLast(5).joinToString("\n") { "+ $it" }
        val foulsText = recentFouls.takeLast(5).joinToString("\n") { "! $it" }

        val cleanPatternsNote = if (cleanShotPatterns.isNotEmpty()) {
            "Saved Clean Sure-Shot Patterns: ${cleanShotPatterns.size} (${cleanShotPatterns.sumOf { it.successCount }} clean training pots)"
        } else {
            null
        }
        val collisionRisksNote = if (collisionRisks.isNotEmpty()) {
            val activeRisks = collisionRisks.count { it.riskScore() > 0.2f }
            "Saved Corridor Collision Risks: $activeRisks active obstacle pattern(s)"
        } else {
            null
        }

        return listOfNotNull(
            "CARROM ADAPTIVE SHOT MEMORY (LEARNED FROM YOUR LIVE MATCHES):",
            cleanPatternsNote,
            collisionRisksNote,
            bestBuckets.takeIf { it.isNotBlank() }?.let { "Best Pockets & Angles:\n$it" },
            foulBuckets.takeIf { it.isNotBlank() }?.let { "Known Foul Patterns:\n$it" },
            blockedSigsText.takeIf { it.isNotBlank() }?.let { "HARD-BLOCKED FOUL SIGNATURES (NEVER SHOOT THESE):\n$it" },
            placementNote.takeIf { it.isNotBlank() },
            tacticsText.takeIf { it.isNotBlank() }?.let { "Recent Winning Adjustments:\n$it" },
            foulsText.takeIf { it.isNotBlank() }?.let { "Recent Foul/Power Warnings:\n$it" }
        ).joinToString("\n")
    }

    companion object {
        fun bucketKey(pocketIndex: Int, bucket: CarromAngleBucket, distRatio: Float = 0.7f): String {
            val band = CarromDistanceBand.fromDistRatio(distRatio)
            return "P${pocketIndex.coerceIn(0, 3)}_${bucket.name}_${band.name}"
        }

        fun strikerSectorFor(strikerNormalizedX: Float): String = when {
            strikerNormalizedX < 0.36f -> "LEFT"
            strikerNormalizedX > 0.64f -> "RIGHT"
            else -> "MID"
        }

        fun coinZoneFor(point: Point2D, board: CarromBoardLayout): Pair<Int, Int> {
            val normX = ((point.x - board.left) / board.width).coerceIn(0f, 0.999f)
            val normY = ((point.y - board.top) / board.height).coerceIn(0f, 0.999f)
            val row = (normY * 4f).toInt().coerceIn(0, 3)
            val col = (normX * 4f).toInt().coerceIn(0, 3)
            return row to col
        }

        fun foulSignatureKey(
            pocketIndex: Int,
            strikerSector: String,
            coinRowZone: Int,
            coinColZone: Int,
            angleBucket: CarromAngleBucket,
            powerStyle: CarromPowerStyle
        ): String {
            return "P${pocketIndex.coerceIn(0, 3)}_${strikerSector}_R${coinRowZone.coerceIn(0, 3)}C${coinColZone.coerceIn(0, 3)}_${angleBucket.name}_${powerStyle.name}"
        }

        fun pocketName(pocketIndex: Int): String = when (pocketIndex) {
            0 -> "Top-Left Pocket"
            1 -> "Top-Right Pocket"
            2 -> "Bottom-Left Pocket"
            3 -> "Bottom-Right Pocket"
            else -> "Pocket #$pocketIndex"
        }
    }
}

data class BoardPieceCounts(
    val myCount: Int,
    val opponentCount: Int,
    val queenCount: Int
)

/**
 * Why a shot missed, classified from BEFORE/AFTER coin positions
 * (board-normalised, 0-1) — not from piece counts alone. The cause decides
 * what the learned table changes, so the bot stops blaming power for a
 * deflection and stops blaming aim for an undershoot:
 * - UNDERSHOOT: coin stopped short of the pocket -> power +0.05.
 * - OVERSHOOT: coin passed the pocket / bounced out -> power -0.05
 *   (slight) or -0.08 (far past the pocket).
 * - DEFLECTION: coin veered off its line (kissed another coin) -> record
 *   a collision-risk pattern at that spot; power is NOT blamed.
 * - WHIFF: coin barely moved -> re-check aim/calibration; power is NOT
 *   adjusted.
 * - SCRATCH: striker potted (foul) -> scratch zone + foul signature;
 *   never repeat that striker placement/line.
 * Classification runs AFTER the shot, off the turn clock — it never
 * delays the next shot's 5s window.
 */
enum class CarromMissCause {
    UNDERSHOOT, OVERSHOOT, DEFLECTION, WHIFF, SCRATCH, UNKNOWN
}

/** Board-normalised geometry for one miss-cause classification. */
data class MissCauseInput(
    val preCoinNorm: Point2D,
    val postCoinNorm: Point2D?,
    val pocketNorm: Point2D,
    val coinRadiusNorm: Float,
    val pocketRadiusNorm: Float
)

data class MissCauseClassification(
    val cause: CarromMissCause,
    /** True when an overshoot went far past the pocket (bigger cut). */
    val overshootFar: Boolean = false,
    /** Deflection point along the coin->pocket line, 0..1 (risk pattern). */
    val deflectAlongRatio: Float = 0.5f,
    /** Deflection lateral deviation in coin radii (risk pattern). */
    val deflectLateralRadii: Float = 0f
)

data class PendingCarromShotRecord(
    val myCoinColor: CoinColor,
    val targetCoinColor: CoinColor,
    val pocketIndex: Int,
    val cutAngleDeg: Float,
    val strikerNormalizedX: Float = 0.5f,
    val coinRowZone: Int = -1,
    val coinColZone: Int = -1,
    val powerStyle: CarromPowerStyle,
    val movedStrikerWithSlider: Boolean,
    val isBankShot: Boolean,
    val isComboShot: Boolean,
    val isBreakShot: Boolean,
    val preCounts: BoardPieceCounts,
    val isFreestyle: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val coinNormX: Float = 0.5f,
    val coinNormY: Float = 0.5f,
    val distRatio: Float = 0.7f,
    val observedPullScale: Float = 1.0f,
    val minCorridorClearanceRadii: Float = 3.5f,
    val corridorObstacles: List<CorridorObstacleObservation> = emptyList(),
    /** True when the pre-shot detection was stable across 2 frames (vision-error guard). */
    val preDetectionStable: Boolean = false,
    /** True when this record carries real solver geometry (coinNormX/Y from a solved shot), so a position-based miss cause can be classified. False for AI-planned fallback drags. */
    val hasSolverGeometry: Boolean = false
)

data class CarromEvaluationResult(
    val outcomeType: CarromShotOutcomeType,
    val updatedMemory: CarromMemoryData,
    val learnedTacticLesson: String?,
    val learnedFoulLesson: String?,
    val summaryMessage: String,
    /** Why the shot missed, when classified from pre/post positions. */
    val missCause: CarromMissCause? = null
)

class CarromShotMemoryStore(context: Context) {

    private val storageFile = File(context.filesDir, CARROM_MEMORY_FILE)
    private val mutex = Mutex()

    private val _memory = MutableStateFlow(CarromMemoryData())
    val memory: StateFlow<CarromMemoryData> = _memory.asStateFlow()

    suspend fun loadInitial() {
        mutex.withLock {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    if (!storageFile.exists()) CarromMemoryData()
                    else parseJson(storageFile.readText())
                }.getOrDefault(CarromMemoryData())
            }
            _memory.value = loaded
        }
    }

    suspend fun recordShotOutcome(
        pendingShot: PendingCarromShotRecord,
        postCounts: BoardPieceCounts,
        /** Pre/post coin geometry for miss-cause classification (null = counts only). */
        missCauseInput: MissCauseInput? = null
    ): CarromEvaluationResult {
        return mutex.withLock {
            val evaluated = evaluateShotOutcome(
                current = _memory.value,
                pendingShot = pendingShot,
                postCounts = postCounts,
                missCauseInput = missCauseInput
            )
            // Bot shots feed the pattern library too: a clean pot reinforces
            // the pattern, and a miss/foul DECAYS it (see
            // updateTrainingPatternsFromShot). Without this, a bank/combo
            // pattern that fails in a real match would keep its stale
            // "sure shot" reputation forever.
            val withPatterns = updateTrainingPatternsFromShot(
                memory = evaluated.updatedMemory,
                pendingShot = pendingShot,
                outcomeType = evaluated.outcomeType,
                observedPowerHint = null
            )
            persistLocked(withPatterns)
            evaluated.copy(updatedMemory = withPatterns)
        }
    }

    suspend fun recordHumanTrainingShot(
        pendingShot: PendingCarromShotRecord,
        postCounts: BoardPieceCounts,
        observedPowerHint: Float? = null,
        isOpponentSide: Boolean = false
    ): CarromEvaluationResult {
        return mutex.withLock {
            val evaluated = evaluateShotOutcome(
                current = _memory.value,
                pendingShot = pendingShot,
                postCounts = postCounts,
                lessonPrefix = if (isOpponentSide) "[Opponent Coach] " else "[Human Coach] ",
                recordFoulSignatures = !isOpponentSide,
                recordPlacementStats = !isOpponentSide
            )
            var updatedMem = evaluated.updatedMemory
            if (observedPowerHint != null &&
                (evaluated.outcomeType == CarromShotOutcomeType.POCKETED_MY_COIN ||
                    evaluated.outcomeType == CarromShotOutcomeType.POCKETED_QUEEN)
            ) {
                val cal = updatedMem.matchCalibration
                val clampedHint = observedPowerHint.coerceIn(0.80f, 1.25f)
                val newGain = ((cal.shotPowerGain * 0.85f) + (clampedHint * 0.15f)).coerceIn(0.78f, 1.28f)
                updatedMem = updatedMem.copy(
                    matchCalibration = cal.copy(shotPowerGain = newGain)
                )
            }

            // Record both-sided training patterns:
            // 1) Clean foul-free pots -> CleanShotPattern library
            // 2) Nearby corridor obstacle collisions/misses -> CollisionRiskPattern map
            updatedMem = updateTrainingPatternsFromShot(
                memory = updatedMem,
                pendingShot = pendingShot,
                outcomeType = evaluated.outcomeType,
                observedPowerHint = observedPowerHint
            )

            persistLocked(updatedMem)
            evaluated.copy(updatedMemory = updatedMem)
        }
    }

    suspend fun updateSliderGain(observedRatio: Float) {
        val clamped = observedRatio.coerceIn(0.65f, 1.45f)
        mutex.withLock {
            val cur = _memory.value
            val prevCal = cur.matchCalibration
            val n = prevCal.sliderSamples
            val blended = ((prevCal.sliderGain * n.coerceAtMost(4)) + clamped) / (n.coerceAtMost(4) + 1)
            val updated = cur.copy(
                matchCalibration = prevCal.copy(
                    sliderGain = blended.coerceIn(0.65f, 1.45f),
                    sliderSamples = n + 1
                ),
                lastUpdatedMs = System.currentTimeMillis()
            )
            persistLocked(updated)
        }
    }

    suspend fun updateBoardCalibration(profile: BoardCalibrationProfile) {
        mutex.withLock {
            val cur = _memory.value
            persistLocked(
                cur.copy(
                    boardCalibration = profile,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Per-match calibration reset (board-variation rule): at the start of
     * EVERY match, friction, pocket-jaw buffer, power gain and slider gain
     * go back to neutral (1.0 / 0) and shotsThisMatch to 0, so the previous
     * board's tuning never leaks into the new board. The LEARNED tables
     * (statsByBucket / profileStats / patterns) are NOT touched — learned
     * confidence is a prior, and the first few shots of the new match
     * re-tune the live calibration on top of it. Live calibration always
     * wins at shot time: the solver multiplies the learned pull by
     * matchCalibration.shotPowerGain.
     */
    suspend fun resetMatchCalibration() {
        mutex.withLock {
            val cur = _memory.value
            persistLocked(
                cur.copy(
                    matchCalibration = MatchPhysicsCalibration(),
                    lastUpdatedMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Save a NAMED board profile from the Board Calibration & Aim Lab
     * (screenshot upload -> pocket alignment -> colour picking -> Test Aim).
     * Upserts by profileName and activates it: boardCalibration becomes
     * this profile and activeBoardProfileName switches, so the solver and
     * the learned tables immediately use this board.
     */
    suspend fun saveNamedBoardProfile(profile: BoardCalibrationProfile) {
        val name = profile.profileName.takeIf { it.isNotBlank() } ?: "Default"
        val named = profile.copy(profileName = name, enabled = true)
        mutex.withLock {
            val cur = _memory.value
            val updatedProfiles = cur.boardProfiles
                .filterNot { it.profileName == name }
                .toMutableList()
            updatedProfiles.add(named)
            persistLocked(
                cur.copy(
                    boardProfiles = updatedProfiles,
                    boardCalibration = named,
                    activeBoardProfileName = name,
                    lastUpdatedMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Activate a saved board profile by name (used by the match-start
     * Auto-Snap matcher). Returns false when no such profile exists — the
     * caller then keeps auto-detection and must NOT silently fall back to
     * a wrong board. Activation also resets the per-match calibration,
     * because a different board means different friction.
     */
    suspend fun activateBoardProfile(profileName: String): Boolean {
        return mutex.withLock {
            val cur = _memory.value
            val profile = cur.boardProfiles.firstOrNull { it.profileName == profileName }
                ?: return@withLock false
            persistLocked(
                cur.copy(
                    boardCalibration = profile.copy(enabled = true),
                    activeBoardProfileName = profileName,
                    matchCalibration = MatchPhysicsCalibration(),
                    lastUpdatedMs = System.currentTimeMillis()
                )
            )
            true
        }
    }

    // Self-play trainer guard: never run two training sweeps at once.
    @Volatile
    private var selfPlayRunning = false

    /**
     * Self-play trainer entry point (callable on demand, e.g. from Settings
     * or when the app is idle on the launcher). Runs the offline sweep on
     * a background dispatcher, records every simulated shot into the
     * learned tables, and persists once at the end — so the results
     * survive app restarts in the same tappilot_carrom_memory.json file.
     * Returns null when a sweep is already running.
     */
    suspend fun runSelfPlayTraining(
        config: SelfPlayConfig = SelfPlayConfig()
    ): SelfPlayReport? {
        if (selfPlayRunning) return null
        selfPlayRunning = true
        return try {
            // The whole sweep runs under the mutex: a live shot recorded
            // mid-sweep must never be overwritten by the trainer's persist
            // (the sweep starts from _memory.value). The mutex hold is
            // ~2s of background CPU; live recording serialises behind it
            // but no record is ever lost.
            mutex.withLock {
                val report = withContext(Dispatchers.Default) {
                    CarromSelfPlayTrainer.runSweep(
                        memory = _memory.value,
                        config = config
                    )
                }
                persistLocked(report.updatedMemory)
                report
            }
        } finally {
            selfPlayRunning = false
        }
    }

    suspend fun clearAll() {
        mutex.withLock {
            persistLocked(CarromMemoryData())
        }
    }

    suspend fun replaceWithImported(memoryData: CarromMemoryData) {
        mutex.withLock {
            persistLocked(memoryData)
        }
    }

    private suspend fun persistLocked(data: CarromMemoryData) {
        _memory.value = data
        withContext(Dispatchers.IO) {
            runCatching {
                storageFile.writeText(serializeJson(data))
            }
        }
    }

    companion object {
        fun countBoardPieces(pieces: List<CarromPiece>, myCoinColor: CoinColor): BoardPieceCounts {
            val opponentColor = if (myCoinColor == CoinColor.WHITE) CoinColor.BLACK else CoinColor.WHITE
            return BoardPieceCounts(
                myCount = pieces.count { it.color == myCoinColor },
                opponentCount = pieces.count { it.color == opponentColor },
                queenCount = pieces.count { it.color == CoinColor.QUEEN }
            )
        }

        /**
         * Classify WHY a shot missed from the target coin's pre/post
         * positions (board-normalised). Pure geometry, no I/O:
         * - Foul (striker potted / penalty) -> SCRATCH.
         * - Coin potted -> UNKNOWN (no miss to explain).
         * - Coin not found after the shot -> UNKNOWN (detection lost it;
         *   never blame power on a vision gap).
         * - Coin moved < 0.6 radii -> WHIFF (aim/calibration, not power).
         * - Coin veered > 1.6 radii sideways off its pocket line while
         *   travelling -> DEFLECTION (kiss/collision, not power).
         * - Coin stopped short of the pocket mouth -> UNDERSHOOT.
         * - Coin travelled past the pocket mouth -> OVERSHOOT (far when
         *   it went > 2 pocket-radii beyond the pocket centre).
         */
        fun classifyMissCause(
            outcomeType: CarromShotOutcomeType,
            input: MissCauseInput?
        ): MissCauseClassification {
            if (outcomeType == CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY) {
                return MissCauseClassification(CarromMissCause.SCRATCH)
            }
            if (outcomeType != CarromShotOutcomeType.MISSED_OR_NO_POT || input == null) {
                return MissCauseClassification(CarromMissCause.UNKNOWN)
            }
            val post = input.postCoinNorm ?: return MissCauseClassification(CarromMissCause.UNKNOWN)
            val r = input.coinRadiusNorm.coerceAtLeast(0.005f)
            val toPost = Point2D(post.x - input.preCoinNorm.x, post.y - input.preCoinNorm.y)
            val movedRadii = kotlin.math.hypot(toPost.x.toDouble(), toPost.y.toDouble()).toFloat() / r
            if (movedRadii < 0.6f) {
                return MissCauseClassification(CarromMissCause.WHIFF)
            }
            val toPocket = Point2D(input.pocketNorm.x - input.preCoinNorm.x, input.pocketNorm.y - input.preCoinNorm.y)
            val distToPocket = kotlin.math.hypot(toPocket.x.toDouble(), toPocket.y.toDouble()).toFloat().coerceAtLeast(r)
            val dirX = toPocket.x / distToPocket
            val dirY = toPocket.y / distToPocket
            val along = toPost.x * dirX + toPost.y * dirY
            val lateral = kotlin.math.abs(toPost.x * dirY - toPost.y * dirX)
            val lateralRadii = lateral / r
            if (lateralRadii > 1.6f && along > distToPocket * 0.35f) {
                return MissCauseClassification(
                    cause = CarromMissCause.DEFLECTION,
                    deflectAlongRatio = (along / distToPocket).coerceIn(0f, 1f),
                    deflectLateralRadii = lateralRadii
                )
            }
            if (along < distToPocket - input.pocketRadiusNorm) {
                return MissCauseClassification(CarromMissCause.UNDERSHOOT)
            }
            val beyond = along - distToPocket
            return MissCauseClassification(
                cause = CarromMissCause.OVERSHOOT,
                overshootFar = beyond > input.pocketRadiusNorm * 2f
            )
        }

        fun evaluateShotOutcome(
            current: CarromMemoryData,
            pendingShot: PendingCarromShotRecord,
            postCounts: BoardPieceCounts,
            lessonPrefix: String = "",
            recordFoulSignatures: Boolean = true,
            recordPlacementStats: Boolean = true,
            /** Pre/post coin geometry for miss-cause classification (null = counts only, coarse rules). */
            missCauseInput: MissCauseInput? = null
        ): CarromEvaluationResult {
            val pre = pendingShot.preCounts
            val myDelta = postCounts.myCount - pre.myCount
            val oppDelta = postCounts.opponentCount - pre.opponentCount
            val queenPocketed = pre.queenCount > 0 && postCounts.queenCount == 0
            val totalDelta = (postCounts.myCount + postCounts.opponentCount + postCounts.queenCount) -
                (pre.myCount + pre.opponentCount + pre.queenCount)

            val outcomeType = if (pendingShot.isFreestyle) {
                when {
                    // Freestyle: ONLY striker scratch (foul returning a penalty coin) is a foul; White, Black, and Red/Queen are all legal!
                    totalDelta > 0 -> CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY
                    queenPocketed -> CarromShotOutcomeType.POCKETED_QUEEN
                    myDelta < 0 || oppDelta < 0 -> CarromShotOutcomeType.POCKETED_MY_COIN
                    else -> CarromShotOutcomeType.MISSED_OR_NO_POT
                }
            } else {
                when {
                    // My coin count increased -> striker pocketed or foul returned a coin!
                    myDelta > 0 -> CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY
                    // Only opponent coin went in while none of mine did -> foul/wrong pot
                    oppDelta < 0 && myDelta >= 0 && !queenPocketed -> CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY
                    // Queen went in cleanly without penalty
                    queenPocketed -> CarromShotOutcomeType.POCKETED_QUEEN
                    // My coin decreased -> cleanly pocketed!
                    myDelta < 0 -> CarromShotOutcomeType.POCKETED_MY_COIN
                    else -> CarromShotOutcomeType.MISSED_OR_NO_POT
                }
            }

            val pocketIdx = pendingShot.pocketIndex.coerceIn(0, 3)
            val pocketLabel = CarromMemoryData.pocketName(pocketIdx)
            val angleBucket = CarromAngleBucket.fromAngle(pendingShot.cutAngleDeg)
            // Distance-banded key: the same pocket + cut learns different power at
            // short vs long range. pendingShot.distRatio is the real recorded ratio
            // (striker -> coin -> pocket) / board width, defaulting to 0.7 (Medium).
            val key = CarromMemoryData.bucketKey(pocketIdx, angleBucket, pendingShot.distRatio)
            // Profile-aware read: the active board's own bucket first, then
            // the global one — so a board's learned power is never mixed
            // with another board's.
            val existingStats = current.statsFor(pocketIdx, pendingShot.cutAngleDeg, pendingShot.distRatio)
                ?: PocketShotStats(
                key = key,
                pocketIndex = pocketIdx,
                angleBucket = angleBucket
            )

            val moveLabel = if (pendingShot.movedStrikerWithSlider) "Slider Reposition" else "In-Place Shot"
            val powerLabel = pendingShot.powerStyle.label
            val shotKind = when {
                pendingShot.isBreakShot -> "Cluster Break"
                pendingShot.isComboShot -> "2-Coin Combo"
                pendingShot.isBankShot -> "1-Rail Bank"
                else -> "${angleBucket.label} Cut"
            }

            var newPowerScale = existingStats.learnedPowerScale
            var tacticLesson: String? = null
            var foulLesson: String? = null
            var straightFouls = current.straightFoulCount

            // Miss-cause classification (pre/post coin positions): the
            // cause — not the raw miss — decides what the learned table
            // changes. Without geometry the old coarse rules apply.
            val missClassification = classifyMissCause(outcomeType, missCauseInput)
            val missCause = when {
                missCauseInput != null -> missClassification.cause
                outcomeType == CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY -> CarromMissCause.SCRATCH
                else -> CarromMissCause.UNKNOWN
            }

            val ps = current.placementStats
            val newPlacementStats = when (outcomeType) {
                CarromShotOutcomeType.POCKETED_MY_COIN, CarromShotOutcomeType.POCKETED_QUEEN -> {
                    val potNoun = when {
                        outcomeType == CarromShotOutcomeType.POCKETED_QUEEN -> "Queen"
                        pendingShot.isFreestyle -> "${pendingShot.targetCoinColor.name} (Freestyle)"
                        else -> pendingShot.myCoinColor.name
                    }
                    tacticLesson = "${lessonPrefix}$pocketLabel ($shotKind, $powerLabel, $moveLabel) pocketed $potNoun cleanly!"
                    // Slightly reinforce working power scale toward current effective scale
                    if (!recordPlacementStats) {
                        ps
                    } else if (pendingShot.movedStrikerWithSlider) {
                        ps.copy(
                            movedAttempts = ps.movedAttempts + 1,
                            movedSuccesses = ps.movedSuccesses + 1
                        )
                    } else {
                        ps.copy(
                            inPlaceAttempts = ps.inPlaceAttempts + 1,
                            inPlaceSuccesses = ps.inPlaceSuccesses + 1
                        )
                    }
                }
                CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY -> {
                    // Too hard or straight follow-through scratched striker or hit foul -> soften future power!
                    newPowerScale = (existingStats.learnedPowerScale - 0.08f).coerceIn(0.72f, 1.38f)
                    if (recordFoulSignatures && angleBucket == CarromAngleBucket.STRAIGHT) {
                        straightFouls += 1
                    }
                    foulLesson = if (pendingShot.isFreestyle) {
                        "${lessonPrefix}FOUL (Striker Scratch) on $pocketLabel ($shotKind, $powerLabel, $moveLabel): soften pull & verify striker scratch path."
                    } else {
                        "${lessonPrefix}FOUL on $pocketLabel ($shotKind, $powerLabel, $moveLabel): soften pull & verify striker scratch path."
                    }
                    if (!recordPlacementStats) {
                        ps
                    } else if (pendingShot.movedStrikerWithSlider) {
                        ps.copy(
                            movedAttempts = ps.movedAttempts + 1,
                            movedFouls = ps.movedFouls + 1
                        )
                    } else {
                        ps.copy(
                            inPlaceAttempts = ps.inPlaceAttempts + 1,
                            inPlaceFouls = ps.inPlaceFouls + 1
                        )
                    }
                }
                CarromShotOutcomeType.MISSED_OR_NO_POT -> {
                    // Miss-cause learning: the classified cause — not the
                    // raw miss — decides the power change, so the table
                    // stops blaming power for a deflection or a whiff.
                    // UNDERSHOOT +0.05, OVERSHOOT -0.05 (slight) / -0.08
                    // (far), DEFLECTION/WHIFF no power change. UNKNOWN
                    // (no geometry) keeps the old adaptive rules.
                    newPowerScale = when (missCause) {
                        CarromMissCause.UNDERSHOOT ->
                            (existingStats.learnedPowerScale + 0.05f).coerceIn(0.72f, 1.38f)
                        CarromMissCause.OVERSHOOT ->
                            (existingStats.learnedPowerScale - if (missClassification.overshootFar) 0.08f else 0.05f).coerceIn(0.72f, 1.38f)
                        CarromMissCause.DEFLECTION, CarromMissCause.WHIFF ->
                            existingStats.learnedPowerScale
                        else ->
                            when {
                                pendingShot.powerStyle == CarromPowerStyle.HARD && angleBucket == CarromAngleBucket.STRAIGHT ->
                                    (existingStats.learnedPowerScale - 0.05f).coerceIn(0.72f, 1.38f)
                                angleBucket == CarromAngleBucket.SHARP_CUT || pendingShot.isBankShot ->
                                    (existingStats.learnedPowerScale + 0.06f).coerceIn(0.72f, 1.38f)
                                else ->
                                    (existingStats.learnedPowerScale + 0.03f).coerceIn(0.72f, 1.38f)
                            }
                    }
                    val causeNote = when (missCause) {
                        CarromMissCause.UNDERSHOOT -> "Undershoot — coin stopped short; power +0.05 for this pocket/angle/distance."
                        CarromMissCause.OVERSHOOT -> "Overshoot — coin passed the pocket; power ${if (missClassification.overshootFar) "-0.08" else "-0.05"}."
                        CarromMissCause.DEFLECTION -> "Deflection — coin veered off its line (collision risk recorded); power unchanged."
                        CarromMissCause.WHIFF -> "Whiff — coin barely moved; re-check aim/calibration, power unchanged."
                        else -> null
                    }
                    if (causeNote != null) {
                        foulLesson = "${lessonPrefix}Missed $pocketLabel ($shotKind, $powerLabel) — $causeNote"
                    } else if (existingStats.misses >= 1 && (existingStats.misses + 1) % 2 == 0) {
                        val advice = if (newPowerScale > existingStats.learnedPowerScale) {
                            "increase drive power"
                        } else {
                            "shoot softer so coin doesn't bounce out"
                        }
                        foulLesson = "${lessonPrefix}Missed $pocketLabel ($shotKind, $powerLabel) — auto-adjusting to $advice."
                    }
                    if (!recordPlacementStats) {
                        ps
                    } else if (pendingShot.movedStrikerWithSlider) {
                        ps.copy(movedAttempts = ps.movedAttempts + 1)
                    } else {
                        ps.copy(inPlaceAttempts = ps.inPlaceAttempts + 1)
                    }
                }
            }

            val updatedBucket = existingStats.copy(
                attempts = existingStats.attempts + 1,
                successes = existingStats.successes + if (
                    outcomeType == CarromShotOutcomeType.POCKETED_MY_COIN ||
                    outcomeType == CarromShotOutcomeType.POCKETED_QUEEN
                ) 1 else 0,
                fouls = existingStats.fouls + if (outcomeType == CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY) 1 else 0,
                misses = existingStats.misses + if (outcomeType == CarromShotOutcomeType.MISSED_OR_NO_POT) 1 else 0,
                learnedPowerScale = newPowerScale
            )

            // Per-board table: when a board profile is active, this bucket
            // write goes to THAT board's own table, never the global one —
            // a shot learned on one board's friction must not leak into
            // another board's table. With no active profile it goes global.
            val activeProfile = current.activeBoardProfileName
            val updatedGlobalMap = current.statsByBucket.toMutableMap()
            val updatedProfileStats = current.profileStats.toMutableMap()
            if (activeProfile.isNotBlank()) {
                val profileMap = updatedProfileStats[activeProfile]?.toMutableMap() ?: mutableMapOf()
                profileMap[key] = updatedBucket
                updatedProfileStats[activeProfile] = profileMap
            } else {
                updatedGlobalMap[key] = updatedBucket
            }

            val newTactics = if (tacticLesson != null) {
                (current.recentTactics.filterNot { it.equals(tacticLesson, true) } + tacticLesson)
                    .takeLast(MAX_RECENT_LESSONS)
            } else {
                current.recentTactics
            }
            val newFouls = if (foulLesson != null) {
                (current.recentFouls.filterNot { it.equals(foulLesson, true) } + foulLesson)
                    .takeLast(MAX_RECENT_LESSONS)
            } else {
                current.recentFouls
            }

            val prevCal = current.matchCalibration
            var newHeatmap = current.scratchHeatmap
            var newFrictionScale = prevCal.frictionScale
            var newJawBuffer = prevCal.pocketJawSafetyBuffer
            var newShotPowerGain = prevCal.shotPowerGain

            when (outcomeType) {
                CarromShotOutcomeType.POCKETED_MY_COIN, CarromShotOutcomeType.POCKETED_QUEEN -> {
                    newHeatmap = newHeatmap.recordCleanSuccess(
                        pocketIdx,
                        pendingShot.cutAngleDeg,
                        pendingShot.strikerNormalizedX
                    )
                    newJawBuffer = (newJawBuffer - 0.8f).coerceIn(0f, 10f)
                    newShotPowerGain = ((newShotPowerGain * 0.94f) + 0.06f).coerceIn(0.78f, 1.28f)
                }
                CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY -> {
                    if (recordFoulSignatures) {
                        newHeatmap = newHeatmap.recordFoul(
                            pocketIdx,
                            pendingShot.cutAngleDeg,
                            pendingShot.strikerNormalizedX
                        )
                    }
                    newJawBuffer = (newJawBuffer + 2.2f).coerceIn(0f, 10f)
                    newShotPowerGain = (newShotPowerGain - 0.04f).coerceIn(0.78f, 1.28f)
                }
                CarromShotOutcomeType.MISSED_OR_NO_POT -> {
                    // Cause-aware live calibration: an undershoot means
                    // the board needs more drive, an overshoot less;
                    // a deflection or a whiff must NOT move friction or
                    // power gain (the cause was a kiss / aim, not speed).
                    // UNKNOWN keeps the old coarse rules.
                    when (missCause) {
                        CarromMissCause.UNDERSHOOT -> {
                            newFrictionScale = (newFrictionScale + 0.04f).coerceIn(0.80f, 1.28f)
                            newJawBuffer = (newJawBuffer + 1.4f).coerceIn(0f, 10f)
                            newShotPowerGain = (newShotPowerGain + 0.03f).coerceIn(0.78f, 1.28f)
                        }
                        CarromMissCause.OVERSHOOT -> {
                            newFrictionScale = (newFrictionScale - 0.03f).coerceIn(0.80f, 1.28f)
                            newShotPowerGain = (newShotPowerGain - 0.03f).coerceIn(0.78f, 1.28f)
                        }
                        CarromMissCause.DEFLECTION, CarromMissCause.WHIFF -> Unit
                        else -> {
                            if (pendingShot.isBankShot || angleBucket == CarromAngleBucket.SHARP_CUT) {
                                newFrictionScale = (newFrictionScale + 0.04f).coerceIn(0.80f, 1.28f)
                                newJawBuffer = (newJawBuffer + 1.4f).coerceIn(0f, 10f)
                                newShotPowerGain = (newShotPowerGain + 0.03f).coerceIn(0.78f, 1.28f)
                            } else if (pendingShot.powerStyle == CarromPowerStyle.HARD ||
                                (angleBucket == CarromAngleBucket.STRAIGHT && pendingShot.powerStyle != CarromPowerStyle.SOFT)
                            ) {
                                newFrictionScale = (newFrictionScale - 0.03f).coerceIn(0.80f, 1.28f)
                                newShotPowerGain = (newShotPowerGain - 0.03f).coerceIn(0.78f, 1.28f)
                            } else {
                                newShotPowerGain = (newShotPowerGain + 0.02f).coerceIn(0.78f, 1.28f)
                            }
                        }
                    }
                }
            }

            // Deflection: the coin veered off its pocket line, so a kiss
            // happened at that spot — record a collision-risk pattern on
            // the COIN_TO_POCKET corridor. The solver's
            // corridorCollisionRiskPenalty then steers future shots away
            // from that line; power is deliberately NOT blamed.
            var newRisks = current.collisionRisks
            if (missCause == CarromMissCause.DEFLECTION) {
                val alongB = (missClassification.deflectAlongRatio * 4f).toInt().coerceIn(0, 3)
                val rKey = "P${pocketIdx}_${angleBucket.name}_COIN_TO_POCKET_A$alongB"
                val prevRisk = newRisks.firstOrNull { it.riskKey == rKey }
                newRisks = if (prevRisk != null) {
                    newRisks.map {
                        if (it.riskKey == rKey) it.copy(
                            foulOrMissCount = it.foulOrMissCount + 1,
                            blockerLateralInRadii = maxOf(it.blockerLateralInRadii, missClassification.deflectLateralRadii),
                            lastSeenMs = pendingShot.timestampMs
                        ) else it
                    }
                } else {
                    newRisks + CollisionRiskPattern(
                        riskKey = rKey,
                        pocketIndex = pocketIdx,
                        angleBucket = angleBucket,
                        corridorSegment = "COIN_TO_POCKET",
                        blockerAlongBucket = alongB,
                        blockerLateralInRadii = missClassification.deflectLateralRadii,
                        foulOrMissCount = 1,
                        cleanPotDespiteNearCount = 0,
                        lastSeenMs = pendingShot.timestampMs
                    )
                }
            }

            val updatedFoulSignatures = current.foulSignatures.toMutableMap()
            if (recordFoulSignatures && !pendingShot.isBreakShot && pendingShot.coinRowZone in 0..3 && pendingShot.coinColZone in 0..3) {
                val sector = CarromMemoryData.strikerSectorFor(pendingShot.strikerNormalizedX)
                val sigKey = CarromMemoryData.foulSignatureKey(
                    pocketIndex = pocketIdx,
                    strikerSector = sector,
                    coinRowZone = pendingShot.coinRowZone,
                    coinColZone = pendingShot.coinColZone,
                    angleBucket = angleBucket,
                    powerStyle = pendingShot.powerStyle
                )
                val existingSig = updatedFoulSignatures[sigKey]
                when (outcomeType) {
                    CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY -> {
                        val nextSig = if (existingSig != null) {
                            existingSig.copy(
                                foulCount = existingSig.foulCount + 1,
                                lastFoulMs = pendingShot.timestampMs
                            )
                        } else {
                            CarromFoulSignature(
                                signatureKey = sigKey,
                                pocketIndex = pocketIdx,
                                strikerSector = sector,
                                coinRowZone = pendingShot.coinRowZone,
                                coinColZone = pendingShot.coinColZone,
                                angleBucket = angleBucket,
                                powerStyle = pendingShot.powerStyle,
                                foulCount = 1,
                                cleanSuccessCount = 0,
                                lastFoulMs = pendingShot.timestampMs
                            )
                        }
                        updatedFoulSignatures[sigKey] = nextSig
                        if (updatedFoulSignatures.size > MAX_FOUL_SIGNATURES) {
                            val trimmed = updatedFoulSignatures.values
                                .sortedWith(
                                    compareByDescending<CarromFoulSignature> { it.foulCount - it.cleanSuccessCount }
                                        .thenByDescending { it.lastFoulMs }
                                )
                                .take(MAX_FOUL_SIGNATURES)
                            updatedFoulSignatures.clear()
                            trimmed.forEach { updatedFoulSignatures[it.signatureKey] = it }
                        }
                    }
                    CarromShotOutcomeType.POCKETED_MY_COIN, CarromShotOutcomeType.POCKETED_QUEEN -> {
                        if (existingSig != null) {
                            val nextClean = existingSig.cleanSuccessCount + 1
                            if (nextClean >= existingSig.foulCount + 2) {
                                updatedFoulSignatures.remove(sigKey)
                            } else {
                                updatedFoulSignatures[sigKey] = existingSig.copy(cleanSuccessCount = nextClean)
                            }
                        }
                    }
                    CarromShotOutcomeType.MISSED_OR_NO_POT -> Unit
                }
            }

            val updatedMemory = current.copy(
                statsByBucket = updatedGlobalMap,
                profileStats = updatedProfileStats,
                placementStats = newPlacementStats,
                scratchHeatmap = newHeatmap,
                collisionRisks = newRisks,
                matchCalibration = prevCal.copy(
                    frictionScale = newFrictionScale,
                    pocketJawSafetyBuffer = newJawBuffer,
                    shotPowerGain = newShotPowerGain,
                    shotsThisMatch = prevCal.shotsThisMatch + 1
                ),
                foulSignatures = updatedFoulSignatures,
                straightFoulCount = straightFouls,
                recentTactics = newTactics,
                recentFouls = newFouls,
                lastUpdatedMs = System.currentTimeMillis()
            )

            val summary = when (outcomeType) {
                CarromShotOutcomeType.POCKETED_MY_COIN ->
                    "Carrom Outcome: POCKETED coin in $pocketLabel ($powerLabel, $moveLabel) — saved to memory!"
                CarromShotOutcomeType.POCKETED_QUEEN ->
                    "Carrom Outcome: POCKETED RED QUEEN in $pocketLabel ($powerLabel) — remember to cover Queen!"
                CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY ->
                    "Carrom Outcome: FOUL detected (penalty coin/opponent pot) on $pocketLabel — power softened & guard saved!"
                CarromShotOutcomeType.MISSED_OR_NO_POT ->
                    "Carrom Outcome: No coin potted on $pocketLabel ($powerLabel) — ${missCause.name.lowercase()} — powerScale calibrated to ${String.format(Locale.US, "%.2f", newPowerScale)}."
            }

            return CarromEvaluationResult(
                outcomeType = outcomeType,
                updatedMemory = updatedMemory,
                learnedTacticLesson = tacticLesson,
                learnedFoulLesson = foulLesson,
                summaryMessage = summary,
                missCause = missCause
            )
        }

        fun serializeJson(data: CarromMemoryData): String {
            val root = JSONObject()
            val bucketsArray = JSONArray()
            for (st in data.statsByBucket.values) {
                bucketsArray.put(
                    JSONObject()
                        .put("key", st.key)
                        .put("pocketIndex", st.pocketIndex)
                        .put("angleBucket", st.angleBucket.name)
                        .put("attempts", st.attempts)
                        .put("successes", st.successes)
                        .put("fouls", st.fouls)
                        .put("misses", st.misses)
                        .put("learnedPowerScale", st.learnedPowerScale.toDouble())
                )
            }
            val ps = data.placementStats
            val placementObj = JSONObject()
                .put("movedAttempts", ps.movedAttempts)
                .put("movedSuccesses", ps.movedSuccesses)
                .put("movedFouls", ps.movedFouls)
                .put("inPlaceAttempts", ps.inPlaceAttempts)
                .put("inPlaceSuccesses", ps.inPlaceSuccesses)
                .put("inPlaceFouls", ps.inPlaceFouls)

            val heatmapObj = JSONObject()
            for ((zKey, risk) in data.scratchHeatmap.zoneRiskByKey) {
                heatmapObj.put(zKey, risk.toDouble())
            }
            val cal = data.matchCalibration
            val calObj = JSONObject()
                .put("frictionScale", cal.frictionScale.toDouble())
                .put("pocketJawSafetyBuffer", cal.pocketJawSafetyBuffer.toDouble())
                .put("sliderGain", cal.sliderGain.toDouble())
                .put("shotPowerGain", cal.shotPowerGain.toDouble())
                .put("sliderSamples", cal.sliderSamples)
                .put("shotsThisMatch", cal.shotsThisMatch)

            val foulSigsArray = JSONArray()
            for (sig in data.foulSignatures.values) {
                foulSigsArray.put(
                    JSONObject()
                        .put("signatureKey", sig.signatureKey)
                        .put("pocketIndex", sig.pocketIndex)
                        .put("strikerSector", sig.strikerSector)
                        .put("coinRowZone", sig.coinRowZone)
                        .put("coinColZone", sig.coinColZone)
                        .put("angleBucket", sig.angleBucket.name)
                        .put("powerStyle", sig.powerStyle.name)
                        .put("foulCount", sig.foulCount)
                        .put("cleanSuccessCount", sig.cleanSuccessCount)
                        .put("lastFoulMs", sig.lastFoulMs)
                )
            }

            val cleanPatternsArr = JSONArray()
            for (pat in data.cleanShotPatterns) {
                cleanPatternsArr.put(
                    JSONObject()
                        .put("patternId", pat.patternId)
                        .put("pocketIndex", pat.pocketIndex)
                        .put("strikerNormalizedX", pat.strikerNormalizedX.toDouble())
                        .put("coinNormX", pat.coinNormX.toDouble())
                        .put("coinNormY", pat.coinNormY.toDouble())
                        .put("cutAngleDeg", pat.cutAngleDeg.toDouble())
                        .put("distRatio", pat.distRatio.toDouble())
                        .put("powerStyle", pat.powerStyle.name)
                        .put("observedPullScale", pat.observedPullScale.toDouble())
                        .put("minCorridorClearanceRadii", pat.minCorridorClearanceRadii.toDouble())
                        .put("wasCollisionAssisted", pat.wasCollisionAssisted)
                        .put("successCount", pat.successCount)
                        .put("lastUsedMs", pat.lastUsedMs)
                        .put("isBankShot", pat.isBankShot)
                        .put("isComboShot", pat.isComboShot)
                        .put("profileName", pat.profileName)
                )
            }

            val collisionRisksArr = JSONArray()
            for (risk in data.collisionRisks) {
                collisionRisksArr.put(
                    JSONObject()
                        .put("riskKey", risk.riskKey)
                        .put("pocketIndex", risk.pocketIndex)
                        .put("angleBucket", risk.angleBucket.name)
                        .put("corridorSegment", risk.corridorSegment)
                        .put("blockerAlongBucket", risk.blockerAlongBucket)
                        .put("blockerLateralInRadii", risk.blockerLateralInRadii.toDouble())
                        .put("foulOrMissCount", risk.foulOrMissCount)
                        .put("cleanPotDespiteNearCount", risk.cleanPotDespiteNearCount)
                        .put("lastSeenMs", risk.lastSeenMs)
                )
            }

            val boardProfilesArr = JSONArray()
            for (profile in data.boardProfiles) {
                boardProfilesArr.put(profile.toJson())
            }

            val profileStatsArr = JSONArray()
            for ((profileName, buckets) in data.profileStats) {
                val profileBucketsArr = JSONArray()
                for (st in buckets.values) {
                    profileBucketsArr.put(
                        JSONObject()
                            .put("key", st.key)
                            .put("pocketIndex", st.pocketIndex)
                            .put("angleBucket", st.angleBucket.name)
                            .put("attempts", st.attempts)
                            .put("successes", st.successes)
                            .put("fouls", st.fouls)
                            .put("misses", st.misses)
                            .put("learnedPowerScale", st.learnedPowerScale.toDouble())
                    )
                }
                profileStatsArr.put(
                    JSONObject()
                        .put("profileName", profileName)
                        .put("buckets", profileBucketsArr)
                )
            }

            root.put("buckets", bucketsArray)
            root.put("placementStats", placementObj)
            root.put("scratchHeatmap", heatmapObj)
            root.put("matchCalibration", calObj)
            root.put("foulSignatures", foulSigsArray)
            root.put("cleanShotPatterns", cleanPatternsArr)
            root.put("collisionRisks", collisionRisksArr)
            root.put("boardCalibration", data.boardCalibration.toJson())
            root.put("boardProfiles", boardProfilesArr)
            root.put("activeBoardProfileName", data.activeBoardProfileName)
            root.put("profileStats", profileStatsArr)
            root.put("selfPlayShotsSimulated", data.selfPlayShotsSimulated)
            root.put("selfPlaySureShotsFound", data.selfPlaySureShotsFound)
            root.put("straightFoulCount", data.straightFoulCount)
            root.put("recentTactics", JSONArray(data.recentTactics))
            root.put("recentFouls", JSONArray(data.recentFouls))
            root.put("lastUpdatedMs", data.lastUpdatedMs)
            return root.toString(2)
        }

        fun parseJson(raw: String): CarromMemoryData {
            if (raw.isBlank()) return CarromMemoryData()
            val root = JSONObject(raw)
            val bucketsArray = root.optJSONArray("buckets") ?: JSONArray()
            val map = LinkedHashMap<String, PocketShotStats>()
            for (i in 0 until bucketsArray.length()) {
                val obj = bucketsArray.optJSONObject(i) ?: continue
                val key = obj.optString("key").takeIf { it.isNotBlank() } ?: continue
                val pocketIndex = obj.optInt("pocketIndex", 0).coerceIn(0, 3)
                val angleBucket = runCatching {
                    CarromAngleBucket.valueOf(obj.optString("angleBucket", CarromAngleBucket.MEDIUM_CUT.name))
                }.getOrDefault(CarromAngleBucket.MEDIUM_CUT)
                map[key] = PocketShotStats(
                    key = key,
                    pocketIndex = pocketIndex,
                    angleBucket = angleBucket,
                    attempts = obj.optInt("attempts", 0).coerceAtLeast(0),
                    successes = obj.optInt("successes", 0).coerceAtLeast(0),
                    fouls = obj.optInt("fouls", 0).coerceAtLeast(0),
                    misses = obj.optInt("misses", 0).coerceAtLeast(0),
                    learnedPowerScale = obj.optDouble("learnedPowerScale", 1.0).toFloat().coerceIn(0.72f, 1.38f)
                )
            }

            val pObj = root.optJSONObject("placementStats")
            val placementStats = if (pObj != null) {
                StrikerPlacementStats(
                    movedAttempts = pObj.optInt("movedAttempts", 0).coerceAtLeast(0),
                    movedSuccesses = pObj.optInt("movedSuccesses", 0).coerceAtLeast(0),
                    movedFouls = pObj.optInt("movedFouls", 0).coerceAtLeast(0),
                    inPlaceAttempts = pObj.optInt("inPlaceAttempts", 0).coerceAtLeast(0),
                    inPlaceSuccesses = pObj.optInt("inPlaceSuccesses", 0).coerceAtLeast(0),
                    inPlaceFouls = pObj.optInt("inPlaceFouls", 0).coerceAtLeast(0)
                )
            } else {
                StrikerPlacementStats()
            }

            fun jsonList(key: String): List<String> {
                val arr = root.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { idx ->
                    arr.optString(idx).trim().takeIf { it.isNotBlank() }
                }
            }

            val heatmapObj = root.optJSONObject("scratchHeatmap")
            val heatmapMap = LinkedHashMap<String, Float>()
            if (heatmapObj != null) {
                val keysIter = heatmapObj.keys()
                while (keysIter.hasNext()) {
                    val k = keysIter.next()
                    heatmapMap[k] = heatmapObj.optDouble(k, 0.0).toFloat().coerceIn(0f, 0.85f)
                }
            }

            val calObj = root.optJSONObject("matchCalibration")
            val calibration = if (calObj != null) {
                MatchPhysicsCalibration(
                    frictionScale = calObj.optDouble("frictionScale", 1.0).toFloat().coerceIn(0.80f, 1.28f),
                    pocketJawSafetyBuffer = calObj.optDouble("pocketJawSafetyBuffer", 0.0).toFloat().coerceIn(0f, 10f),
                    sliderGain = calObj.optDouble("sliderGain", 1.0).toFloat().coerceIn(0.65f, 1.45f),
                    shotPowerGain = calObj.optDouble("shotPowerGain", 1.0).toFloat().coerceIn(0.78f, 1.28f),
                    sliderSamples = calObj.optInt("sliderSamples", 0).coerceAtLeast(0),
                    shotsThisMatch = calObj.optInt("shotsThisMatch", 0).coerceAtLeast(0)
                )
            } else {
                MatchPhysicsCalibration()
            }

            val foulSigsArr = root.optJSONArray("foulSignatures") ?: JSONArray()
            val foulSigsMap = LinkedHashMap<String, CarromFoulSignature>()
            for (i in 0 until foulSigsArr.length()) {
                val obj = foulSigsArr.optJSONObject(i) ?: continue
                val key = obj.optString("signatureKey").takeIf { it.isNotBlank() } ?: continue
                val angleBucket = runCatching {
                    CarromAngleBucket.valueOf(obj.optString("angleBucket", CarromAngleBucket.MEDIUM_CUT.name))
                }.getOrDefault(CarromAngleBucket.MEDIUM_CUT)
                val powerStyle = runCatching {
                    CarromPowerStyle.valueOf(obj.optString("powerStyle", CarromPowerStyle.MEDIUM.name))
                }.getOrDefault(CarromPowerStyle.MEDIUM)
                foulSigsMap[key] = CarromFoulSignature(
                    signatureKey = key,
                    pocketIndex = obj.optInt("pocketIndex", 0).coerceIn(0, 3),
                    strikerSector = obj.optString("strikerSector", "MID").ifBlank { "MID" },
                    coinRowZone = obj.optInt("coinRowZone", 0).coerceIn(0, 3),
                    coinColZone = obj.optInt("coinColZone", 0).coerceIn(0, 3),
                    angleBucket = angleBucket,
                    powerStyle = powerStyle,
                    foulCount = obj.optInt("foulCount", 1).coerceAtLeast(0),
                    cleanSuccessCount = obj.optInt("cleanSuccessCount", 0).coerceAtLeast(0),
                    lastFoulMs = obj.optLong("lastFoulMs", 0L)
                )
            }

            val cleanPatternsArr = root.optJSONArray("cleanShotPatterns") ?: JSONArray()
            val cleanPatternsList = ArrayList<CleanShotPattern>(cleanPatternsArr.length())
            for (i in 0 until cleanPatternsArr.length()) {
                val obj = cleanPatternsArr.optJSONObject(i) ?: continue
                val id = obj.optString("patternId").takeIf { it.isNotBlank() } ?: continue
                val pStyle = runCatching {
                    CarromPowerStyle.valueOf(obj.optString("powerStyle", CarromPowerStyle.MEDIUM.name))
                }.getOrDefault(CarromPowerStyle.MEDIUM)
                cleanPatternsList.add(
                    CleanShotPattern(
                        patternId = id,
                        pocketIndex = obj.optInt("pocketIndex", 0).coerceIn(0, 3),
                        strikerNormalizedX = obj.optDouble("strikerNormalizedX", 0.5).toFloat().coerceIn(0f, 1f),
                        coinNormX = obj.optDouble("coinNormX", 0.5).toFloat().coerceIn(0f, 1f),
                        coinNormY = obj.optDouble("coinNormY", 0.5).toFloat().coerceIn(0f, 1f),
                        cutAngleDeg = obj.optDouble("cutAngleDeg", 15.0).toFloat().coerceIn(0f, 68f),
                        distRatio = obj.optDouble("distRatio", 0.7).toFloat().coerceIn(0.15f, 1.8f),
                        powerStyle = pStyle,
                        observedPullScale = obj.optDouble("observedPullScale", 1.0).toFloat().coerceIn(0.72f, 1.38f),
                        minCorridorClearanceRadii = obj.optDouble("minCorridorClearanceRadii", 3.5).toFloat().coerceIn(0.5f, 6.0f),
                        wasCollisionAssisted = obj.optBoolean("wasCollisionAssisted", false),
                        successCount = obj.optInt("successCount", 1).coerceAtLeast(1),
                        lastUsedMs = obj.optLong("lastUsedMs", 0L),
                        isBankShot = obj.optBoolean("isBankShot", false),
                        isComboShot = obj.optBoolean("isComboShot", false),
                        profileName = obj.optString("profileName", "")
                    )
                )
            }

            val risksArr = root.optJSONArray("collisionRisks") ?: JSONArray()
            val risksList = ArrayList<CollisionRiskPattern>(risksArr.length())
            for (i in 0 until risksArr.length()) {
                val obj = risksArr.optJSONObject(i) ?: continue
                val key = obj.optString("riskKey").takeIf { it.isNotBlank() } ?: continue
                val angleBucket = runCatching {
                    CarromAngleBucket.valueOf(obj.optString("angleBucket", CarromAngleBucket.MEDIUM_CUT.name))
                }.getOrDefault(CarromAngleBucket.MEDIUM_CUT)
                risksList.add(
                    CollisionRiskPattern(
                        riskKey = key,
                        pocketIndex = obj.optInt("pocketIndex", 0).coerceIn(0, 3),
                        angleBucket = angleBucket,
                        corridorSegment = obj.optString("corridorSegment", "STRIKER_TO_COIN"),
                        blockerAlongBucket = obj.optInt("blockerAlongBucket", 1).coerceIn(0, 3),
                        blockerLateralInRadii = obj.optDouble("blockerLateralInRadii", 1.5).toFloat().coerceIn(0f, 4.0f),
                        foulOrMissCount = obj.optInt("foulOrMissCount", 1).coerceAtLeast(0),
                        cleanPotDespiteNearCount = obj.optInt("cleanPotDespiteNearCount", 0).coerceAtLeast(0),
                        lastSeenMs = obj.optLong("lastSeenMs", 0L)
                    )
                )
            }

            // Named board profiles (Aim Lab) — missing on old files.
            val boardProfilesArr = root.optJSONArray("boardProfiles") ?: JSONArray()
            val boardProfilesList = ArrayList<BoardCalibrationProfile>(boardProfilesArr.length())
            for (i in 0 until boardProfilesArr.length()) {
                val obj = boardProfilesArr.optJSONObject(i) ?: continue
                boardProfilesList.add(BoardCalibrationProfile.fromJson(obj))
            }

            // Per-board learned tables — missing on old files.
            val profileStatsArr = root.optJSONArray("profileStats") ?: JSONArray()
            val profileStatsMap = LinkedHashMap<String, Map<String, PocketShotStats>>()
            for (i in 0 until profileStatsArr.length()) {
                val obj = profileStatsArr.optJSONObject(i) ?: continue
                val profileName = obj.optString("profileName").takeIf { it.isNotBlank() } ?: continue
                val bucketsArr = obj.optJSONArray("buckets") ?: JSONArray()
                val bucketMap = LinkedHashMap<String, PocketShotStats>()
                for (j in 0 until bucketsArr.length()) {
                    val bObj = bucketsArr.optJSONObject(j) ?: continue
                    val key = bObj.optString("key").takeIf { it.isNotBlank() } ?: continue
                    val bPocketIndex = bObj.optInt("pocketIndex", 0).coerceIn(0, 3)
                    val bAngleBucket = runCatching {
                        CarromAngleBucket.valueOf(bObj.optString("angleBucket", CarromAngleBucket.MEDIUM_CUT.name))
                    }.getOrDefault(CarromAngleBucket.MEDIUM_CUT)
                    bucketMap[key] = PocketShotStats(
                        key = key,
                        pocketIndex = bPocketIndex,
                        angleBucket = bAngleBucket,
                        attempts = bObj.optInt("attempts", 0).coerceAtLeast(0),
                        successes = bObj.optInt("successes", 0).coerceAtLeast(0),
                        fouls = bObj.optInt("fouls", 0).coerceAtLeast(0),
                        misses = bObj.optInt("misses", 0).coerceAtLeast(0),
                        learnedPowerScale = bObj.optDouble("learnedPowerScale", 1.0).toFloat().coerceIn(0.72f, 1.38f)
                    )
                }
                profileStatsMap[profileName] = bucketMap
            }

            return CarromMemoryData(
                statsByBucket = map,
                placementStats = placementStats,
                scratchHeatmap = ScratchZoneHeatmap(heatmapMap),
                matchCalibration = calibration,
                foulSignatures = foulSigsMap,
                cleanShotPatterns = cleanPatternsList,
                collisionRisks = risksList,
                boardCalibration = BoardCalibrationProfile.fromJson(root.optJSONObject("boardCalibration")),
                boardProfiles = boardProfilesList,
                activeBoardProfileName = root.optString("activeBoardProfileName", ""),
                profileStats = profileStatsMap,
                selfPlayShotsSimulated = root.optInt("selfPlayShotsSimulated", 0).coerceAtLeast(0),
                selfPlaySureShotsFound = root.optInt("selfPlaySureShotsFound", 0).coerceAtLeast(0),
                straightFoulCount = root.optInt("straightFoulCount", 0).coerceAtLeast(0),
                recentTactics = jsonList("recentTactics"),
                recentFouls = jsonList("recentFouls"),
                lastUpdatedMs = root.optLong("lastUpdatedMs", 0L)
            )
        }

        internal fun updateTrainingPatternsFromShot(
            memory: CarromMemoryData,
            pendingShot: PendingCarromShotRecord,
            outcomeType: CarromShotOutcomeType,
            observedPowerHint: Float?
        ): CarromMemoryData {
            if (pendingShot.isBreakShot) return memory
            val pocketIdx = pendingShot.pocketIndex.coerceIn(0, 3)
            val angleBucket = CarromAngleBucket.fromAngle(pendingShot.cutAngleDeg)
            val isCleanPot = outcomeType == CarromShotOutcomeType.POCKETED_MY_COIN ||
                outcomeType == CarromShotOutcomeType.POCKETED_QUEEN

            val updatedPatterns = memory.cleanShotPatterns.toMutableList()
            val updatedRisks = memory.collisionRisks.associateBy { it.riskKey }.toMutableMap()

            if (isCleanPot) {
                val pullScale = (observedPowerHint ?: pendingShot.observedPullScale).coerceIn(0.75f, 1.35f)
                val kissAssisted = pendingShot.corridorObstacles.any { it.displacedOrKissed && it.lateralOffsetInRadii <= 2.4f }
                // Same pocket + same shot kind + same board: a bank pot must
                // never merge into a direct pattern (or vice versa).
                val existingIdx = updatedPatterns.indexOfFirst { pat ->
                    pat.pocketIndex == pocketIdx &&
                        pat.isBankShot == pendingShot.isBankShot &&
                        pat.isComboShot == pendingShot.isComboShot &&
                        (pat.profileName.isBlank() || pat.profileName == memory.activeBoardProfileName) &&
                        kotlin.math.abs(pat.cutAngleDeg - pendingShot.cutAngleDeg) <= 7.5f &&
                        kotlin.math.hypot(pat.coinNormX - pendingShot.coinNormX, pat.coinNormY - pendingShot.coinNormY) <= 0.14f &&
                        kotlin.math.abs(pat.strikerNormalizedX - pendingShot.strikerNormalizedX) <= 0.14f
                }
                if (existingIdx >= 0) {
                    val prev = updatedPatterns[existingIdx]
                    val n = prev.successCount.coerceAtMost(8)
                    updatedPatterns[existingIdx] = prev.copy(
                        strikerNormalizedX = (prev.strikerNormalizedX * n + pendingShot.strikerNormalizedX) / (n + 1),
                        coinNormX = (prev.coinNormX * n + pendingShot.coinNormX) / (n + 1),
                        coinNormY = (prev.coinNormY * n + pendingShot.coinNormY) / (n + 1),
                        cutAngleDeg = (prev.cutAngleDeg * n + pendingShot.cutAngleDeg) / (n + 1),
                        distRatio = (prev.distRatio * n + pendingShot.distRatio) / (n + 1),
                        observedPullScale = ((prev.observedPullScale * n + pullScale) / (n + 1)).coerceIn(0.75f, 1.35f),
                        minCorridorClearanceRadii = minOf(prev.minCorridorClearanceRadii, pendingShot.minCorridorClearanceRadii),
                        wasCollisionAssisted = prev.wasCollisionAssisted || kissAssisted,
                        successCount = prev.successCount + 1,
                        lastUsedMs = pendingShot.timestampMs
                    )
                } else {
                    val patId = "P${pocketIdx}_R${pendingShot.coinRowZone.coerceIn(0, 3)}C${pendingShot.coinColZone.coerceIn(0, 3)}_${angleBucket.name}_${pendingShot.timestampMs % 100_000L}"
                    updatedPatterns.add(
                        CleanShotPattern(
                            patternId = patId,
                            pocketIndex = pocketIdx,
                            strikerNormalizedX = pendingShot.strikerNormalizedX,
                            coinNormX = pendingShot.coinNormX,
                            coinNormY = pendingShot.coinNormY,
                            cutAngleDeg = pendingShot.cutAngleDeg,
                            distRatio = pendingShot.distRatio,
                            powerStyle = pendingShot.powerStyle,
                            observedPullScale = pullScale,
                            minCorridorClearanceRadii = pendingShot.minCorridorClearanceRadii,
                            wasCollisionAssisted = kissAssisted,
                            successCount = 1,
                            lastUsedMs = pendingShot.timestampMs,
                            isBankShot = pendingShot.isBankShot,
                            isComboShot = pendingShot.isComboShot,
                            profileName = memory.activeBoardProfileName
                        )
                    )
                }
                // Any obstacle near the corridor that still resulted in a clean pot reduces risk / marks helpful kiss
                for (obs in pendingShot.corridorObstacles) {
                    if (obs.lateralOffsetInRadii <= 2.6f) {
                        val alongB = (obs.alongRatio * 4f).toInt().coerceIn(0, 3)
                        val rKey = "P${pocketIdx}_${angleBucket.name}_${obs.segment}_A$alongB"
                        val prevRisk = updatedRisks[rKey]
                        if (prevRisk != null) {
                            updatedRisks[rKey] = prevRisk.copy(
                                cleanPotDespiteNearCount = prevRisk.cleanPotDespiteNearCount + 1,
                                lastSeenMs = pendingShot.timestampMs
                            )
                        }
                    }
                }
            } else {
                // Missed or fouled shot -> save nearby corridor obstacles that likely collided/blocked
                for (obs in pendingShot.corridorObstacles) {
                    if (obs.lateralOffsetInRadii <= 3.1f && (obs.displacedOrKissed || obs.lateralOffsetInRadii <= 2.2f)) {
                        val alongB = (obs.alongRatio * 4f).toInt().coerceIn(0, 3)
                        val rKey = "P${pocketIdx}_${angleBucket.name}_${obs.segment}_A$alongB"
                        val prevRisk = updatedRisks[rKey]
                        if (prevRisk != null) {
                            val n = prevRisk.foulOrMissCount.coerceAtMost(6)
                            updatedRisks[rKey] = prevRisk.copy(
                                blockerLateralInRadii = maxOf(prevRisk.blockerLateralInRadii, (prevRisk.blockerLateralInRadii * n + obs.lateralOffsetInRadii) / (n + 1)),
                                foulOrMissCount = prevRisk.foulOrMissCount + 1,
                                lastSeenMs = pendingShot.timestampMs
                            )
                        } else {
                            updatedRisks[rKey] = CollisionRiskPattern(
                                riskKey = rKey,
                                pocketIndex = pocketIdx,
                                angleBucket = angleBucket,
                                corridorSegment = obs.segment,
                                blockerAlongBucket = alongB,
                                blockerLateralInRadii = obs.lateralOffsetInRadii,
                                foulOrMissCount = 1,
                                cleanPotDespiteNearCount = 0,
                                lastSeenMs = pendingShot.timestampMs
                            )
                        }
                    }
                }
                // A failed shot lowers the confidence of the pattern it just
                // tried: any matching pattern (same pocket, kind, board,
                // similar geometry) loses one success, and a pattern that
                // falls to zero is forgotten. This is what makes a failed
                // bank/combo in a real match actually demote that pattern
                // instead of leaving a stale "sure shot" in the table.
                for (idx in updatedPatterns.indices) {
                    val pat = updatedPatterns[idx]
                    if (pat.pocketIndex == pocketIdx &&
                        pat.isBankShot == pendingShot.isBankShot &&
                        pat.isComboShot == pendingShot.isComboShot &&
                        (pat.profileName.isBlank() || pat.profileName == memory.activeBoardProfileName) &&
                        kotlin.math.abs(pat.cutAngleDeg - pendingShot.cutAngleDeg) <= 7.5f &&
                        kotlin.math.hypot(pat.coinNormX - pendingShot.coinNormX, pat.coinNormY - pendingShot.coinNormY) <= 0.14f &&
                        kotlin.math.abs(pat.strikerNormalizedX - pendingShot.strikerNormalizedX) <= 0.14f
                    ) {
                        val nextCount = pat.successCount - 1
                        if (nextCount <= 0) {
                            updatedPatterns.removeAt(idx)
                        } else {
                            updatedPatterns[idx] = pat.copy(successCount = nextCount)
                        }
                        break
                    }
                }
            }

            val trimmedPatterns = if (updatedPatterns.size > MAX_CLEAN_SHOT_PATTERNS) {
                updatedPatterns
                    .sortedWith(compareByDescending<CleanShotPattern> { it.successCount }.thenByDescending { it.lastUsedMs })
                    .take(MAX_CLEAN_SHOT_PATTERNS)
            } else {
                updatedPatterns
            }
            val trimmedRisks = if (updatedRisks.size > MAX_COLLISION_RISKS) {
                updatedRisks.values
                    .sortedWith(compareByDescending<CollisionRiskPattern> { it.riskScore() }.thenByDescending { it.lastSeenMs })
                    .take(MAX_COLLISION_RISKS)
            } else {
                updatedRisks.values.toList()
            }

            return memory.copy(
                cleanShotPatterns = trimmedPatterns,
                collisionRisks = trimmedRisks,
                lastUpdatedMs = System.currentTimeMillis()
            )
        }

        fun exportTrainingBundleJson(
            memory: CarromMemoryData,
            carromPlaybook: TaskPlaybook?,
            exportedAtMs: Long = System.currentTimeMillis()
        ): String {
            val totalAttempts = maxOf(
                memory.statsByBucket.values.sumOf { it.attempts },
                memory.matchCalibration.shotsThisMatch
            )
            val totalPots = memory.statsByBucket.values.sumOf { it.successes }
            val blockedFouls = memory.blockedFoulCount()

            val root = JSONObject()
                .put("format", CARROM_BUNDLE_FORMAT)
                .put("exportedAtMs", exportedAtMs)
                .put(
                    "summary",
                    JSONObject()
                        .put("totalAttempts", totalAttempts)
                        .put("totalPots", totalPots)
                        .put("blockedFoulSignatures", blockedFouls)
                        .put("cleanShotPatterns", memory.cleanShotPatterns.size)
                        .put("collisionRisks", memory.collisionRisks.size)
                )
                .put("carromMemory", JSONObject(serializeJson(memory)))

            if (carromPlaybook != null) {
                val pbArr = JSONArray(AgentKnowledgeStore.serializePlaybooksJson(listOf(carromPlaybook)))
                if (pbArr.length() > 0) {
                    root.put("carromPlaybook", pbArr.getJSONObject(0))
                }
            }
            return root.toString(2)
        }

        fun parseTrainingBundleJson(raw: String): Pair<CarromMemoryData, TaskPlaybook?> {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) throw IllegalArgumentException("Empty Carrom JSON file.")
            val root = JSONObject(trimmed)
            return if (root.has("carromMemory")) {
                val memObj = root.getJSONObject("carromMemory")
                val mem = parseJson(memObj.toString())
                val pbObj = root.optJSONObject("carromPlaybook")
                val pb = if (pbObj != null) {
                    AgentKnowledgeStore.parsePlaybooksJson(JSONArray().put(pbObj).toString()).firstOrNull()
                } else {
                    null
                }
                mem to pb
            } else if (root.has("buckets") || root.has("foulSignatures") || root.has("matchCalibration")) {
                parseJson(trimmed) to null
            } else {
                throw IllegalArgumentException("File is not a valid TapPilot Carrom training JSON.")
            }
        }
    }
}
