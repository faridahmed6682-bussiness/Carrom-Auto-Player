package com.example.game

import com.example.agent.CarromDifficulty
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class CoinColor {
    WHITE,
    BLACK,
    QUEEN,
    STRIKER
}

data class Point2D(
    val x: Float,
    val y: Float
) {
    operator fun plus(other: Point2D): Point2D = Point2D(x + other.x, y + other.y)
    operator fun minus(other: Point2D): Point2D = Point2D(x - other.x, y - other.y)
    operator fun times(scalar: Float): Point2D = Point2D(x * scalar, y * scalar)

    fun length(): Float = hypot(x, y)

    fun normalized(): Point2D {
        val len = length()
        return if (len > 1e-4f) Point2D(x / len, y / len) else Point2D(0f, 0f)
    }

    fun dot(other: Point2D): Float = x * other.x + y * other.y
}

data class CarromPiece(
    val color: CoinColor,
    val center: Point2D,
    val radiusPx: Float
)

data class CarromBoardLayout(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pockets: List<Point2D>,
    val baselineY: Float,
    val coinRadiusPx: Float,
    val strikerRadiusPx: Float,
    val pocketRadiusPx: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

data class CarromShotSolution(
    val strikerPos: Point2D,
    val dragEnd: Point2D,
    val ghostBall: Point2D,
    val targetCoin: CarromPiece,
    val targetPocket: Point2D,
    val pocketIndex: Int = 0,
    val cutAngleDeg: Float,
    val isBankShot: Boolean,
    val isComboShot: Boolean = false,
    val isBreakShot: Boolean = false,
    val isSureShot: Boolean = false,
    // Foul-safe safety shot: a soft touch that parks the striker behind
    // coins instead of gambling on a pot. Never a scoring attempt, and
    // never recorded as a pot attempt in shot memory.
    val isSafetyShot: Boolean = false,
    val isCollisionAssistedSureShot: Boolean = false,
    val isTrainingMatched: Boolean = false,
    val rejectedDueToCollisionOrFoul: Boolean = false,
    val powerStyle: CarromPowerStyle = CarromPowerStyle.MEDIUM,
    val confidenceScore: Float,
    val reason: String,
    // Total shot path length (striker -> coin -> pocket) in px and the pull
    // multiplier actually used, so the caller can record the real geometry +
    // power into shot memory for distance-banded learning.
    val totalDistance: Float = 0f,
    val pullMultiplier: Float = 1.0f
)

data class PocketZonePlan(
    val zoneName: String,
    val primaryPocketIndex: Int,
    val secondaryPocketIndex: Int,
    val preferBankForBottomPockets: Boolean
)

/**
 * Pure Kotlin Ghost-Ball & Vector Physics Carrom Shot Solver.
 * Computes exact striker placement, cut angles, obstacle collision checks,
 * striker scratch-foul guards, 1-cushion bank/rebound, 2-coin combo, cluster break,
 * 4-pocket center-out quadrant routing, and adapts power & pocket choices from CarromMemoryData.
 */
object CarromGeometrySolver {

    /**
     * Center-Out & 4-Quadrant Pocket Routing Plan for 4-pocket Carrom boards (500/1000 Freestyle & Disc Pool):
     * - 0 = Top-Left, 1 = Top-Right, 2 = Bottom-Left, 3 = Bottom-Right
     */
    fun planPocketForCoinPosition(
        board: CarromBoardLayout,
        coinCenter: Point2D
    ): PocketZonePlan {
        val nx = ((coinCenter.x - board.left) / board.width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val ny = ((coinCenter.y - board.top) / board.height.coerceAtLeast(1f)).coerceIn(0f, 1f)
        val onLeftHalf = nx <= 0.50f
        return when {
            ny >= 0.78f -> PocketZonePlan(
                zoneName = if (onLeftHalf) "Bottom-Baseline-Left" else "Bottom-Baseline-Right",
                primaryPocketIndex = if (onLeftHalf) 2 else 3,
                secondaryPocketIndex = if (onLeftHalf) 0 else 1,
                preferBankForBottomPockets = true
            )
            ny >= 0.65f -> PocketZonePlan(
                zoneName = if (onLeftHalf) "Lower-Left" else "Lower-Right",
                primaryPocketIndex = if (onLeftHalf) 2 else 3,
                secondaryPocketIndex = if (onLeftHalf) 0 else 1,
                preferBankForBottomPockets = false
            )
            nx in 0.30f..0.70f && ny in 0.30f..0.65f -> PocketZonePlan(
                zoneName = if (onLeftHalf) "Center-Left" else "Center-Right",
                primaryPocketIndex = if (onLeftHalf) 0 else 1,
                secondaryPocketIndex = if (onLeftHalf) 1 else 0,
                preferBankForBottomPockets = false
            )
            ny < 0.30f -> PocketZonePlan(
                zoneName = if (onLeftHalf) "Top-Left-Rail" else "Top-Right-Rail",
                primaryPocketIndex = if (onLeftHalf) 0 else 1,
                secondaryPocketIndex = if (onLeftHalf) 1 else 0,
                preferBankForBottomPockets = false
            )
            onLeftHalf -> PocketZonePlan(
                zoneName = "Mid-Left",
                primaryPocketIndex = 0,
                secondaryPocketIndex = 2,
                preferBankForBottomPockets = false
            )
            else -> PocketZonePlan(
                zoneName = "Mid-Right",
                primaryPocketIndex = 1,
                secondaryPocketIndex = 3,
                preferBankForBottomPockets = false
            )
        }
    }

    internal fun pocketPlanBonus(
        board: CarromBoardLayout,
        coin: CarromPiece,
        pocketIndex: Int,
        isFreestyle: Boolean
    ): Float {
        val plan = planPocketForCoinPosition(board, coin.center)
        val zoneBonus = when (pocketIndex) {
            plan.primaryPocketIndex -> 0.06f
            plan.secondaryPocketIndex -> 0.025f
            else -> 0f
        }
        val freestyleValueBonus = if (isFreestyle) {
            when (coin.color) {
                CoinColor.QUEEN -> 0.18f // 50 points
                CoinColor.WHITE -> 0.09f // 20 points
                CoinColor.BLACK -> 0.00f // 10 points
                CoinColor.STRIKER -> 0f
            }
        } else {
            if (coin.color == CoinColor.QUEEN) 0.09f else 0f
        }
        return zoneBonus + freestyleValueBonus
    }

    fun solveBestShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        existingStriker: CarromPiece? = null,
        canRepositionStriker: Boolean = true,
        shotMemory: CarromMemoryData? = null,
        usePhysicsRollout: Boolean = true,
        isFreestyle: Boolean = false,
        queenPendingCover: Boolean = false,
        difficulty: CarromDifficulty = CarromDifficulty.MEDIUM
    ): CarromShotSolution? {
        if (board.pockets.size < 4) return null

        val targetPieces = if (isFreestyle) {
            pieces.filter { it.color != CoinColor.STRIKER }
        } else if (queenPendingCover) {
            pieces.filter { it.color == myCoinColor }
                .ifEmpty { pieces.filter { it.color != CoinColor.STRIKER } }
        } else {
            pieces.filter {
                it.color == myCoinColor || it.color == CoinColor.QUEEN
            }.ifEmpty {
                pieces.filter { it.color != CoinColor.STRIKER }
            }
        }
        if (targetPieces.isEmpty()) return null

        // Difficulty shifts the confidence floors for accepting a shot:
        // EASY takes riskier pots, MEDIUM keeps the tuned defaults, HARD only
        // takes clearly better shots. All callers default to MEDIUM (offset 0).
        val diffOffset = when (difficulty) {
            CarromDifficulty.EASY -> -0.12f
            CarromDifficulty.MEDIUM -> 0f
            CarromDifficulty.HARD -> 0.08f
        }

        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val strikerCandidates = buildStrikerCandidates(
            board = board,
            obstacles = obstacles,
            existingStriker = existingStriker,
            canRepositionStriker = canRepositionStriker
        )
        if (strikerCandidates.isEmpty()) return null

        // 5s sure-shot / no-foul rule: the scratch guard ALWAYS runs with
        // its wide safety margins (1.48x follow-through / 1.34x deflection),
        // not only after a foul has already happened — a first foul must
        // never be the teacher. The physics rollout re-checks the winner.
        val extraScratchGuard = true
        val inPlaceAdj = shotMemory?.placementStats?.inPlaceBonusAdjustment() ?: 0f
        val directPool = mutableListOf<RolloutCandidate>()
        var bestDirect: CarromShotSolution? = null

        for (coin in targetPieces) {
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                val coinToPocket = (pocket - coin.center)
                val distCoinPocket = coinToPocket.length()
                if (distCoinPocket < board.coinRadiusPx * 1.15f) return@forEachIndexed

                val dirCp = coinToPocket.normalized()
                // Ghost-ball position behind the target coin along pocket line
                val impactDist = board.coinRadiusPx + board.strikerRadiusPx
                val ghostBall = coin.center - dirCp * impactDist

                if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.55f)) {
                    return@forEachIndexed
                }

                // Verify coin -> pocket path is clear of blocking coins
                if (!isSegmentClear(
                        start = coin.center,
                        end = pocket,
                        obstacles = obstacles,
                        excluded = coin,
                        clearanceRadius = board.coinRadiusPx * 1.85f
                    )
                ) {
                    return@forEachIndexed
                }

                for (strikerPos in strikerCandidates) {
                    val strikerToGhost = ghostBall - strikerPos
                    val distStrikerGhost = strikerToGhost.length()
                    if (distStrikerGhost < board.strikerRadiusPx * 1.1f) continue

                    val dirSg = strikerToGhost.normalized()
                    val cosAngle = dirSg.dot(dirCp).coerceIn(-1f, 1f)
                    val cutAngleDeg = (acos(cosAngle) * (180.0 / PI)).toFloat()

                    val isLowerHalfBottomCut = (pocketIdx == 2 || pocketIdx == 3) &&
                        coin.center.y >= board.top + board.height * 0.58f
                    val maxAllowedCut = if (isLowerHalfBottomCut) 72f else 64f
                    if (cutAngleDeg > maxAllowedCut) continue

                    // Verify striker -> ghostBall path does not clip another coin first
                    if (!isSegmentClear(
                            start = strikerPos,
                            end = ghostBall,
                            obstacles = obstacles,
                            excluded = coin,
                            clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.88f
                        )
                    ) {
                        continue
                    }

                    // Ghost-ball neighbourhood check (fixes the W3/B4 miss): the
                    // segment check above ignores obstacles whose projection
                    // falls right at the ghost ball, so a coin sitting ON the
                    // impact point itself would be missed. No other coin may
                    // sit within striking distance of the ghost ball, or the
                    // striker clips it just before impact.
                    val ghostClearRadius = board.coinRadiusPx * 1.2f + board.strikerRadiusPx
                    if (obstacles.any { other ->
                            other !== coin && (other.center - ghostBall).length() < ghostClearRadius
                        }) {
                        continue
                    }

                    // Foul guard: avoid shots where striker follows straight into target or adjacent pocket
                    if (willStrikerScratchIntoPocket(
                            strikerPos = strikerPos,
                            ghostBall = ghostBall,
                            dirSg = dirSg,
                            dirCp = dirCp,
                            cutAngleDeg = cutAngleDeg,
                            board = board,
                            extraSafetyMargin = extraScratchGuard
                        )
                    ) {
                        continue
                    }

                    // Distance-banded learned power: same pocket + cut needs
                    // different power at short vs long range.
                    val distRatio = ((distStrikerGhost + distCoinPocket) / board.width.coerceAtLeast(1f)).coerceIn(0.15f, 1.8f)
                    val learnedPowerScale = shotMemory?.learnedPowerScaleFor(pocketIdx, cutAngleDeg, distRatio) ?: 1.0f
                    val matchPowerGain = shotMemory?.matchCalibration?.shotPowerGain ?: 1.0f
                    val isHanger = distCoinPocket <= board.coinRadiusPx * 5.2f
                    val baseMult = if (isHanger) 0.85f else 1.0f

                    val trainingMatch = shotMemory?.matchCleanShotPattern(
                        board = board,
                        strikerPos = strikerPos,
                        coinCenter = coin.center,
                        pocketIndex = pocketIdx,
                        cutAngleDeg = cutAngleDeg,
                        distRatio = distRatio,
                        obstacles = obstacles,
                        targetCoin = coin,
                        ghostBall = ghostBall,
                        pocketPos = pocket
                    )
                    val collisionPenalty = trainingMatch?.collisionRiskPenalty
                        ?: shotMemory?.corridorCollisionRiskPenalty(
                            board = board,
                            strikerPos = strikerPos,
                            ghostBall = ghostBall,
                            coinCenter = coin.center,
                            pocketPos = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutAngleDeg,
                            obstacles = obstacles,
                            excludedCoin = coin
                        ) ?: 0f

                    val patternPowerScale = if (trainingMatch != null) {
                        (trainingMatch.recommendedPullScale * 0.65f + learnedPowerScale * 0.35f).coerceIn(0.74f, 1.36f)
                    } else {
                        learnedPowerScale
                    }
                    val effectiveMult = baseMult * patternPowerScale * matchPowerGain
                    val candPowerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult)
                    if (shotMemory?.isFoulBlacklisted(
                            board = board,
                            coinCenter = coin.center,
                            strikerPos = strikerPos,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutAngleDeg,
                            powerStyle = candPowerStyle
                        ) == true
                    ) {
                        continue
                    }

                    val hangerBonus = if (isHanger) 0.16f else 0f
                    val planAndValueBonus = pocketPlanBonus(board, coin, pocketIdx, isFreestyle)
                    // Queen cover is urgent: an uncovered queen returns to the
                    // board, so a cover pot is worth much more than a normal pot.
                    val coverBonus = if (queenPendingCover && coin.color == myCoinColor) 0.22f else 0f
                    val angleScore = 1f - (cutAngleDeg / 74f) * 0.55f
                    val distScore = 1f - ((distCoinPocket + distStrikerGhost) / (board.width * 2.2f)).coerceIn(0f, 0.35f)
                    val isCurrentStrikerPos = existingStriker != null && abs(strikerPos.x - existingStriker.center.x) < 18f
                    val existingBonus = if (isCurrentStrikerPos) (0.14f + inPlaceAdj).coerceIn(0.02f, 0.28f) else 0f
                    val memoryBias = shotMemory?.confidenceBiasFor(pocketIdx, cutAngleDeg, distRatio) ?: 0f
                    val trainingBoost = (trainingMatch?.matchScore ?: 0f) * 0.18f
                    val normStrikerX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
                    val heatmapPenalty = (shotMemory?.scratchHeatmap?.riskFor(pocketIdx, cutAngleDeg, normStrikerX) ?: 0f) * 0.35f
                    val score = (
                        angleScore * 0.65f +
                            distScore * 0.35f +
                            planAndValueBonus +
                            coverBonus +
                            hangerBonus +
                            existingBonus +
                            memoryBias +
                            trainingBoost -
                            collisionPenalty * 0.34f -
                            heatmapPenalty
                        ).coerceIn(0.05f, 1f)

                    directPool.add(
                        RolloutCandidate(
                            strikerPos = strikerPos,
                            baseDir = dirSg,
                            ghostBall = ghostBall,
                            targetCoin = coin,
                            targetPocket = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutAngleDeg,
                            isBankShot = false,
                            isComboShot = false,
                            isBreakShot = false,
                            basePullMultiplier = baseMult * patternPowerScale,
                            staticScore = score,
                            totalDistance = distStrikerGhost + distCoinPocket,
                            trainingMatchScore = trainingMatch?.matchScore ?: 0f,
                            collisionRiskPenalty = collisionPenalty
                        )
                    )

                    if (bestDirect == null || score > bestDirect.confidenceScore) {
                        val powerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult)
                        val cal = shotMemory?.boardCalibration?.takeIf { it.enabled }
                        val calPullScale = cal?.maxPullScale ?: 1.0f
                        val compDir = applyCutThrowCompensation(
                            shotDir = dirSg,
                            coinDir = dirCp,
                            cutAngleDeg = cutAngleDeg,
                            throwBias = cal?.cutThrowBias ?: 1.0f
                        )
                        val dragEnd = computeSlingshotDragEnd(
                            strikerPos = strikerPos,
                            shotDir = compDir,
                            totalDistance = distStrikerGhost + distCoinPocket,
                            boardWidth = board.width,
                            screenWidth = screenWidth,
                            screenHeight = screenHeight,
                            cutAngleDeg = cutAngleDeg,
                            powerMultiplier = effectiveMult * calPullScale,
                            deadzonePx = cal?.slingshotDeadzonePx ?: 22f,
                            curveGamma = cal?.slingshotCurveGamma ?: 1.0f
                        )
                        val shotLabel = if (trainingMatch != null) "Trained" else if (isHanger) "Hanger" else "Direct"
                        val pocketLabel = CarromMemoryData.pocketName(pocketIdx)
                        bestDirect = CarromShotSolution(
                            strikerPos = strikerPos,
                            dragEnd = dragEnd,
                            ghostBall = ghostBall,
                            targetCoin = coin,
                            targetPocket = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutAngleDeg,
                            isBankShot = false,
                            isSureShot = !usePhysicsRollout && score >= 0.68f,
                            isTrainingMatched = trainingMatch != null,
                            powerStyle = powerStyle,
                            confidenceScore = score,
                            reason = "$shotLabel ${coin.color.name} (${powerStyle.label}) cut=${cutAngleDeg.toInt()}° -> $pocketLabel",
                            totalDistance = distStrikerGhost + distCoinPocket,
                            pullMultiplier = effectiveMult
                        )
                    }
                }
            }
        }

        // Tier 0: Fast sure-shot finder (5s budget) — before the full solve,
        // check only the 2-3 coins nearest a pocket. If ONE physics-verified
        // sure shot is found there, fire it immediately WITHOUT running the
        // Tier-1 / Tier-2 / bank / combo rollouts. This is the main speed
        // win for the 5-second deadline: a single-candidate rollout
        // (~5-15ms) instead of up to 5 full-tier rollouts (~14 candidates
        // each). If no sure shot is found here, fall through to Tier 1 —
        // nothing is lost, the same candidates are re-checked below.
        if (usePhysicsRollout && directPool.isNotEmpty()) {
            val nearestCoins = targetPieces
                .map { coin -> coin to board.pockets.minOf { pocket -> (pocket - coin.center).length() } }
                .sortedBy { it.second }
                .take(3)
                .map { it.first }
                .toSet()
            val fastCandidate = directPool
                .filter { it.targetCoin in nearestCoins && it.cutAngleDeg <= 30f && it.staticScore >= 0.68f }
                .maxByOrNull { it.staticScore }
            if (fastCandidate != null) {
                val fastVerified = refineWithPhysicsRollout(
                    board = board,
                    pieces = pieces,
                    myCoinColor = myCoinColor,
                    candidates = listOf(fastCandidate),
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    shotMemory = shotMemory,
                    isFreestyle = isFreestyle
                )
                if (fastVerified != null && fastVerified.isSureShot) {
                    return fastVerified
                }
            }
        }

        // Tier 1: Training Memory-First Sure Shot (verify matching CleanShotPatterns with 2D physics for zero-foul guarantee)
        val trainingCandidates = directPool.filter { it.trainingMatchScore >= 0.44f }
        if (usePhysicsRollout && trainingCandidates.isNotEmpty()) {
            val verifiedTraining = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = trainingCandidates,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            if (verifiedTraining != null && verifiedTraining.isSureShot) {
                return verifiedTraining
            }
        }

        // Tier 2: Hybrid Engine + Training Calibration (friction, powerGain, jawSafety, foul/collision risks)
        var directRejectedByPhysics = false
        var bestNonSureDirectVerified: CarromShotSolution? = null
        if (usePhysicsRollout && directPool.isNotEmpty() && (bestDirect == null || bestDirect.confidenceScore >= 0.45f)) {
            val verified = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = directPool,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            if (verified != null && verified.isSureShot) {
                return verified
            }
            if (verified != null && !verified.rejectedDueToCollisionOrFoul) {
                bestNonSureDirectVerified = verified
            }
            if (verified == null) {
                directRejectedByPhysics = true
            }
        }

        if (!usePhysicsRollout && bestDirect != null && bestDirect.confidenceScore >= (0.58f + diffOffset)) {
            return bestDirect
        }

        // Queen-cover urgency: when the queen must be covered, take any direct
        // pot of our own coin (even a modest-confidence one) instead of
        // falling through to bank/combo/break shots that cannot cover her.
        if (queenPendingCover && bestDirect != null && bestDirect.confidenceScore >= 0.42f) {
            return bestDirect
        }

        // Fallback 1: check 1-cushion bank/rebound shot off top/left/right/bottom rails
        val bankPool = mutableListOf<RolloutCandidate>()
        val bestBank = solveOneRailBankShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            outCandidates = bankPool,
            isFreestyle = isFreestyle
        )

        if (usePhysicsRollout && (directPool.isNotEmpty() || bankPool.isNotEmpty())) {
            val combined = directPool + bankPool
            val verifiedBankOrDirect = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = combined,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            if (verifiedBankOrDirect != null && verifiedBankOrDirect.isSureShot) {
                return verifiedBankOrDirect
            }
            if (verifiedBankOrDirect != null && !verifiedBankOrDirect.rejectedDueToCollisionOrFoul &&
                (bestNonSureDirectVerified == null || verifiedBankOrDirect.confidenceScore > bestNonSureDirectVerified.confidenceScore)
            ) {
                bestNonSureDirectVerified = verifiedBankOrDirect
            }
        }

        val bestDirectOrBank = when {
            directRejectedByPhysics && bestBank == null -> null
            directRejectedByPhysics -> bestBank
            bestDirect != null && bestBank != null ->
                if (bestDirect.confidenceScore >= bestBank.confidenceScore) bestDirect else bestBank
            else -> bestDirect ?: bestBank
        }
        if (!usePhysicsRollout && bestDirectOrBank != null && bestDirectOrBank.confidenceScore >= (0.52f + diffOffset)) {
            return bestDirectOrBank
        }

        // Fallback 2: 2-coin Combination (kiss/carom) shot: Striker -> Helper Target Coin -> Final Coin -> Pocket
        val comboPool = mutableListOf<RolloutCandidate>()
        val bestCombo = solveTwoCoinComboShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            outCandidates = comboPool
        )
        if (usePhysicsRollout && (directPool.isNotEmpty() || bankPool.isNotEmpty() || comboPool.isNotEmpty())) {
            val allEnginePool = directPool + comboPool + bankPool
            val verifiedCombo = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = allEnginePool,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            if (verifiedCombo != null && (verifiedCombo.isSureShot || (!verifiedCombo.rejectedDueToCollisionOrFoul && verifiedCombo.confidenceScore >= (0.52f + diffOffset)))) {
                return verifiedCombo
            }
            // Training-data calibration fallback: if training power/bias prevented a Sure Shot, re-test with neutral engine power (still obeying foul blacklists!)
            if (shotMemory != null && allEnginePool.isNotEmpty()) {
                val neutralPool = allEnginePool.map { cand ->
                    val defaultMult = when {
                        cand.isBankShot -> 1.24f
                        cand.isComboShot -> 1.26f
                        cand.totalDistance <= board.coinRadiusPx * 10f -> 0.88f
                        else -> 1.0f
                    }
                    cand.copy(basePullMultiplier = defaultMult)
                }
                val engineFallbackShot = refineWithPhysicsRollout(
                    board = board,
                    pieces = pieces,
                    myCoinColor = myCoinColor,
                    candidates = neutralPool,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    shotMemory = shotMemory,
                    isFreestyle = isFreestyle
                )
                if (engineFallbackShot != null && engineFallbackShot.isSureShot) {
                    return engineFallbackShot
                }
                if (bestNonSureDirectVerified != null && bestNonSureDirectVerified.confidenceScore >= (0.50f + diffOffset)) {
                    return bestNonSureDirectVerified
                }
                if (engineFallbackShot != null && !engineFallbackShot.rejectedDueToCollisionOrFoul && engineFallbackShot.confidenceScore >= (0.50f + diffOffset)) {
                    return engineFallbackShot
                }
            }
        }
        if (usePhysicsRollout && bestNonSureDirectVerified != null && bestNonSureDirectVerified.confidenceScore >= (0.50f + diffOffset)) {
            return bestNonSureDirectVerified
        }
        if (!usePhysicsRollout) {
            if (bestCombo != null && (bestDirectOrBank == null || bestCombo.confidenceScore > bestDirectOrBank.confidenceScore)) {
                return bestCombo
            }
            if (bestDirectOrBank != null) {
                return bestDirectOrBank
            }
        }

        // Fallback 3: Physics-verified zero-foul Break / Cluster-Scatter shot
        return solveClusterBreakShot(
            board = board,
            pieces = pieces,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            isFreestyle = isFreestyle
        )
    }

    /**
     * Full-board pre-scan step: evaluates up to 25 baseline striker X coordinates
     * using 2D physics rollouts across direct, combo, and 1-rail bank paths,
     * returning the top [maxProbes] (default 6) distinct baseline X positions.
     */
    fun buildRankedBaselineProbeQueue(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        existingStriker: CarromPiece?,
        shotMemory: CarromMemoryData?,
        maxProbes: Int = CarromTurnMemory.MAX_BASELINE_PROBES,
        isFreestyle: Boolean = false,
        queenPendingCover: Boolean = false
    ): List<RankedBaselineSpot> {
        if (board.pockets.size < 4) return emptyList()
        val targetPieces = if (isFreestyle) {
            pieces.filter { it.color != CoinColor.STRIKER }
        } else if (queenPendingCover) {
            pieces.filter { it.color == myCoinColor }
                .ifEmpty { pieces.filter { it.color != CoinColor.STRIKER } }
        } else {
            pieces.filter {
                it.color == myCoinColor || it.color == CoinColor.QUEEN
            }.ifEmpty {
                pieces.filter { it.color != CoinColor.STRIKER }
            }
        }
        if (targetPieces.isEmpty()) return emptyList()

        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val strikerCandidates = buildStrikerCandidates(
            board = board,
            obstacles = obstacles,
            existingStriker = existingStriker,
            canRepositionStriker = true
        )
        if (strikerCandidates.isEmpty()) return emptyList()

        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true
        val pool = mutableListOf<RolloutCandidate>()

        for (coin in targetPieces) {
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                val coinToPocket = pocket - coin.center
                val distCoinPocket = coinToPocket.length()
                if (distCoinPocket < board.coinRadiusPx * 1.15f) return@forEachIndexed

                val dirCp = coinToPocket.normalized()
                val ghostBall = coin.center - dirCp * (board.coinRadiusPx + board.strikerRadiusPx)
                if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.55f)) {
                    return@forEachIndexed
                }

                val clearCoinToPocket = isSegmentClear(
                    start = coin.center,
                    end = pocket,
                    obstacles = obstacles,
                    excluded = coin,
                    clearanceRadius = board.coinRadiusPx * 1.75f
                )

                for (strikerPos in strikerCandidates) {
                    val sg = ghostBall - strikerPos
                    val distSg = sg.length()
                    if (distSg < board.strikerRadiusPx * 1.1f) continue
                    val dirSg = sg.normalized()
                    val cutDeg = (acos(dirSg.dot(dirCp).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                    val isLowerHalfBottomCut = (pocketIdx == 2 || pocketIdx == 3) &&
                        coin.center.y >= board.top + board.height * 0.58f
                    val maxAllowedCut = if (isLowerHalfBottomCut) 72f else 64f
                    if (cutDeg > maxAllowedCut) continue

                    if (willStrikerScratchIntoPocket(strikerPos, ghostBall, dirSg, dirCp, cutDeg, board, extraScratchGuard)) {
                        continue
                    }

                    // Allow slightly obstructed lines into physics pool so valid carom/collision-pots can be discovered
                    val clearStrikerToGhost = isSegmentClear(
                        start = strikerPos,
                        end = ghostBall,
                        obstacles = obstacles,
                        excluded = coin,
                        clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.75f
                    )
                    if (!clearCoinToPocket && !clearStrikerToGhost) continue

                    val distRatio = ((distSg + distCoinPocket) / board.width.coerceAtLeast(1f)).coerceIn(0.15f, 1.8f)
                    val isHanger = distCoinPocket <= board.coinRadiusPx * 5.2f
                    val learnedScale = shotMemory?.learnedPowerScaleFor(pocketIdx, cutDeg, distRatio) ?: 1.0f
                    val matchPowerGain = shotMemory?.matchCalibration?.shotPowerGain ?: 1.0f

                    val trainingMatch = shotMemory?.matchCleanShotPattern(
                        board = board,
                        strikerPos = strikerPos,
                        coinCenter = coin.center,
                        pocketIndex = pocketIdx,
                        cutAngleDeg = cutDeg,
                        distRatio = distRatio,
                        obstacles = obstacles,
                        targetCoin = coin,
                        ghostBall = ghostBall,
                        pocketPos = pocket
                    )
                    val collisionPenalty = trainingMatch?.collisionRiskPenalty
                        ?: shotMemory?.corridorCollisionRiskPenalty(
                            board = board,
                            strikerPos = strikerPos,
                            ghostBall = ghostBall,
                            coinCenter = coin.center,
                            pocketPos = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutDeg,
                            obstacles = obstacles,
                            excludedCoin = coin
                        ) ?: 0f
                    val patternScale = if (trainingMatch != null) {
                        (trainingMatch.recommendedPullScale * 0.65f + learnedScale * 0.35f).coerceIn(0.74f, 1.36f)
                    } else {
                        learnedScale
                    }
                    val baseMult = (if (isHanger) 0.85f else 1.0f) * patternScale
                    val candPowerStyle = CarromPowerStyle.fromPullMultiplier(baseMult * matchPowerGain)
                    if (shotMemory?.isFoulBlacklisted(
                            board = board,
                            coinCenter = coin.center,
                            strikerPos = strikerPos,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutDeg,
                            powerStyle = candPowerStyle
                        ) == true
                    ) {
                        continue
                    }

                    val obstructionBias = if (clearCoinToPocket && clearStrikerToGhost) 0f else -0.18f
                    val planAndValueBonus = pocketPlanBonus(board, coin, pocketIdx, isFreestyle)
                    val hangerBonus = if (isHanger) 0.14f else 0f
                    val trainingBoost = (trainingMatch?.matchScore ?: 0f) * 0.24f
                    val angleScore = 1f - (cutDeg / 74f) * 0.55f
                    val distScore = 1f - ((distCoinPocket + distSg) / (board.width * 2.2f)).coerceIn(0f, 0.35f)
                    val score = (angleScore * 0.65f + distScore * 0.35f + planAndValueBonus + hangerBonus + obstructionBias + trainingBoost - collisionPenalty * 0.30f)
                        .coerceIn(0.05f, 1f)

                    pool.add(
                        RolloutCandidate(
                            strikerPos = strikerPos,
                            baseDir = dirSg,
                            ghostBall = ghostBall,
                            targetCoin = coin,
                            targetPocket = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutDeg,
                            isBankShot = false,
                            isComboShot = !clearStrikerToGhost || !clearCoinToPocket,
                            isBreakShot = false,
                            basePullMultiplier = baseMult,
                            staticScore = score,
                            totalDistance = distSg + distCoinPocket,
                            trainingMatchScore = trainingMatch?.matchScore ?: 0f,
                            collisionRiskPenalty = collisionPenalty
                        )
                    )
                }
            }
        }

        // Also include 2-coin combo & 1-rail bank options in probe queue
        solveTwoCoinComboShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = 1080,
            screenHeight = 2400,
            shotMemory = shotMemory,
            outCandidates = pool
        )
        solveOneRailBankShot(
            board = board,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = strikerCandidates,
            screenWidth = 1080,
            screenHeight = 2400,
            shotMemory = shotMemory,
            outCandidates = pool,
            isFreestyle = isFreestyle
        )

        val minProbeSep = max(board.width * 0.14f, board.strikerRadiusPx * 2.2f)
        val ranked = CarromPhysicsSimulator.rankTopBaselinePositions(
            board = board,
            pieces = pieces,
            myCoinColor = myCoinColor,
            candidates = pool,
            shotMemory = shotMemory,
            matchCalibration = shotMemory?.matchCalibration,
            maxSpots = maxProbes,
            minSeparationPx = minProbeSep,
            isFreestyle = isFreestyle
        ).toMutableList()

        // Ensure up to 3-4 side-to-side baseline spots exist if no predicted Sure Shot was locked yet
        if (ranked.none { it.predictedSureShot } && ranked.size < maxProbes) {
            val margin = board.strikerRadiusPx * 2.1f
            val minX = board.left + margin
            val maxX = board.right - margin
            val currentX = existingStriker?.center?.x ?: (board.left + board.width * 0.5f)
            val anchorNorms = if (currentX < board.left + board.width * 0.5f) {
                listOf(0.82f, 0.20f, 0.64f, 0.38f)
            } else {
                listOf(0.18f, 0.80f, 0.36f, 0.62f)
            }
            val fallbackCoin = targetPieces.firstOrNull() ?: obstacles.firstOrNull()
            if (fallbackCoin != null && maxX > minX) {
                for (norm in anchorNorms) {
                    if (ranked.size >= maxProbes) break
                    val candX = (board.left + board.width * norm).coerceIn(minX, maxX)
                    val pos = Point2D(candX, board.baselineY)
                    val free = isPositionFree(pos, obstacles, board.coinRadiusPx + board.strikerRadiusPx * 0.95f)
                    val distinctFromRanked = ranked.none { abs(it.strikerX - candX) < minProbeSep * 0.75f }
                    val distinctFromCurrent = abs(candX - currentX) >= minProbeSep * 0.55f
                    if (free && distinctFromRanked && distinctFromCurrent) {
                        ranked.add(
                            RankedBaselineSpot(
                                strikerX = candX,
                                predictedScore = 0.30f,
                                predictedSureShot = false,
                                collisionAssisted = false,
                                targetCoinColor = fallbackCoin.color,
                                pocketIndex = if (norm < 0.5f) 0 else 1
                            )
                        )
                    }
                }
            }
        }
        return ranked
    }

    // Soft power for safety touches: the striker only needs to reach the
    // nearest own coin and stop behind it. computeSlingshotDragEnd clamps
    // the effective power to a 0.45 minimum, so this is the softest legal
    // pull the slingshot model can express.
    private const val SAFETY_SHOT_PULL_MULTIPLIER = 0.60f

    /**
     * Safety shot — the ONLY fallback when no physics-verified sure pot
     * exists (5s sure-shot / no-foul rule). A soft touch on the nearest
     * own coin that parks the striker behind the coins instead of gambling
     * on a pot:
     * - aims straight at the nearest own coin (freestyle: any coin), so the
     *   striker is guaranteed to contact a coin (missing every coin is
     *   itself a foul);
     * - tries small aim offsets if the straight touch fails a foul check;
     * - passes the wide-margin scratch guard, a clear striker path, and a
     *   check that the touched coin is not driven toward any pocket over
     *   the short distance soft power can move it;
     * - never repeats a learned foul pattern (foul blacklist).
     * Returns null only when no coin can be touched foul-free (in practice
     * unreachable while any coin is on the board).
     */
    fun solveSafetyShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        existingStriker: CarromPiece?,
        shotMemory: CarromMemoryData? = null,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        if (board.pockets.size < 4) return null
        val targets = if (isFreestyle) {
            pieces.filter { it.color != CoinColor.STRIKER }
        } else {
            pieces.filter { it.color == myCoinColor }
                .ifEmpty { pieces.filter { it.color == CoinColor.QUEEN } }
                .ifEmpty { pieces.filter { it.color != CoinColor.STRIKER } }
        }
        if (targets.isEmpty()) return null
        val margin = board.strikerRadiusPx * 2.1f
        val strikerPos = existingStriker?.center?.let {
            Point2D(it.x.coerceIn(board.left + margin, board.right - margin), it.y)
        } ?: Point2D(board.left + board.width * 0.5f, board.baselineY)
        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val aimOffsetsDeg = floatArrayOf(0f, -12f, 12f, -24f, 24f, -36f, 36f)
        val sortedTargets = targets.sortedBy { (it.center - strikerPos).length() }
        for (coin in sortedTargets) {
            val toCoin = coin.center - strikerPos
            if (toCoin.length() < board.strikerRadiusPx * 1.5f) continue
            val baseDir = toCoin.normalized()
            for (offsetDeg in aimOffsetsDeg) {
                val rad = (offsetDeg * PI / 180.0).toFloat()
                val c = cos(rad)
                val s = sin(rad)
                val dir = Point2D(baseDir.x * c - baseDir.y * s, baseDir.x * s + baseDir.y * c).normalized()
                val ghostBall = coin.center - dir * (board.coinRadiusPx + board.strikerRadiusPx)
                if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.55f)) continue
                // The striker must reach the coin without clipping another coin.
                if (!isSegmentClear(
                        start = strikerPos,
                        end = ghostBall,
                        obstacles = obstacles,
                        excluded = coin,
                        clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.88f
                    )
                ) {
                    continue
                }
                // The striker must not scratch: at this soft power it stops
                // at the coin, so the wide-margin follow-through check is
                // deliberately strict here.
                if (willStrikerScratchIntoPocket(
                        strikerPos = strikerPos,
                        ghostBall = ghostBall,
                        dirSg = dir,
                        dirCp = dir,
                        cutAngleDeg = 0f,
                        board = board,
                        extraSafetyMargin = true
                    )
                ) {
                    continue
                }
                // The touched coin must not be driven toward a pocket: at
                // soft power it travels at most ~30% of the board width, so
                // its ray must stay clear of every pocket over that distance.
                val coinTravel = board.width * 0.30f
                val coinEnd = coin.center + dir * coinTravel
                val coinToPocketClear = board.pockets.none { pocket ->
                    distancePointToSegment(pocket, coin.center, coinEnd) < board.pocketRadiusPx * 1.6f
                }
                if (!coinToPocketClear) continue
                // Never repeat a learned foul pattern, even for a soft touch.
                val nearestPocketIdx = board.pockets.indices.minByOrNull { idx ->
                    (board.pockets[idx] - coin.center).length()
                } ?: 0
                val powerStyle = CarromPowerStyle.fromPullMultiplier(SAFETY_SHOT_PULL_MULTIPLIER)
                if (shotMemory?.isFoulBlacklisted(
                        board = board,
                        coinCenter = coin.center,
                        strikerPos = strikerPos,
                        pocketIndex = nearestPocketIdx,
                        cutAngleDeg = 0f,
                        powerStyle = powerStyle
                    ) == true
                ) {
                    continue
                }
                val dragEnd = computeSlingshotDragEnd(
                    strikerPos = strikerPos,
                    shotDir = dir,
                    totalDistance = toCoin.length(),
                    boardWidth = board.width,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    cutAngleDeg = 0f,
                    powerMultiplier = SAFETY_SHOT_PULL_MULTIPLIER
                )
                return CarromShotSolution(
                    strikerPos = strikerPos,
                    dragEnd = dragEnd,
                    ghostBall = ghostBall,
                    targetCoin = coin,
                    targetPocket = board.pockets[nearestPocketIdx],
                    pocketIndex = nearestPocketIdx,
                    cutAngleDeg = 0f,
                    isBankShot = false,
                    isSafetyShot = true,
                    powerStyle = powerStyle,
                    confidenceScore = 0.30f,
                    reason = "Safety shot (no sure pot) — soft touch on ${coin.color.name}, striker parks behind coins",
                    totalDistance = toCoin.length(),
                    pullMultiplier = SAFETY_SHOT_PULL_MULTIPLIER
                )
            }
        }
        return null
    }

    /**
     * Deadline fallback (4.5s hard fire deadline): returns ONLY a
     * physics-verified sure pot, a physics-verified zero-scratch cluster
     * break (opening cluster only), or a foul-safe safety shot — never a
     * low-confidence gamble pot. The old guaranteed emergency pot (0.40
     * confidence, unverified) was removed by the 5s sure-shot / no-foul rule.
     */
    fun solveSafestBreakOrPotShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        existingStriker: CarromPiece?,
        shotMemory: CarromMemoryData?,
        usePhysicsRollout: Boolean = true,
        isFreestyle: Boolean = false,
        queenPendingCover: Boolean = false,
        difficulty: CarromDifficulty = CarromDifficulty.MEDIUM
    ): CarromShotSolution? {
        val inPlaceAttempt = solveBestShot(
            board = board,
            pieces = pieces,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = existingStriker,
            canRepositionStriker = false,
            shotMemory = shotMemory,
            usePhysicsRollout = usePhysicsRollout,
            isFreestyle = isFreestyle,
            queenPendingCover = queenPendingCover,
            difficulty = difficulty
        )
        // Sure shot only: a merely "good-looking" pot (0.42 queen-cover,
        // 0.50+ combo/direct) is a gamble and must never be fired as the
        // deadline fallback. Only a physics-verified sure shot qualifies.
        if (inPlaceAttempt != null && inPlaceAttempt.isSureShot && !inPlaceAttempt.rejectedDueToCollisionOrFoul) {
            return inPlaceAttempt
        }
        val targetPieces = if (isFreestyle) {
            pieces.filter { it.color != CoinColor.STRIKER }
        } else {
            pieces.filter { it.color == myCoinColor || it.color == CoinColor.QUEEN }
                .ifEmpty { pieces.filter { it.color != CoinColor.STRIKER } }
        }
        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val candidates = buildStrikerCandidates(board, obstacles, existingStriker, canRepositionStriker = false)
        val clusterBreak = solveClusterBreakShot(
            board = board,
            pieces = pieces,
            targetPieces = targetPieces,
            obstacles = obstacles,
            strikerCandidates = candidates,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            shotMemory = shotMemory,
            isFreestyle = isFreestyle
        )
        // A verified zero-scratch break is the right call only while the
        // cluster is still unbroken (opening): with >= 5 neighbours around
        // the densest coin the board needs scattering. On a scattered board
        // a hard break just gifts the opponent an easy pot, so prefer the
        // safety shot there — the break stays only as a last resort.
        val maxClusterDensity = targetPieces.maxOfOrNull { candidate ->
            obstacles.count { other -> (other.center - candidate.center).length() <= board.coinRadiusPx * 4.5f }
        } ?: 0
        val safetyShot = solveSafetyShot(
            board = board,
            pieces = pieces,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = existingStriker,
            shotMemory = shotMemory,
            isFreestyle = isFreestyle
        )
        return when {
            clusterBreak != null && maxClusterDensity >= 5 -> clusterBreak
            safetyShot != null -> safetyShot
            else -> clusterBreak
        }
    }

    private fun refineWithPhysicsRollout(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        candidates: List<RolloutCandidate>,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        val (winner, sim) = CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = pieces,
            myCoinColor = myCoinColor,
            candidates = candidates,
            shotMemory = shotMemory,
            matchCalibration = shotMemory?.matchCalibration,
            isFreestyle = isFreestyle
        ) ?: return null

        // Do not accept a rollout winner if it explicitly predicts a striker scratch or (in Disc Pool) opponent-only pot
        if (sim.predictedScratchFoul || (!isFreestyle && sim.predictedOpponentOnlyFoul)) {
            return null
        }

        val matchPowerGain = shotMemory?.matchCalibration?.shotPowerGain ?: 1.0f
        val effectiveMult = winner.basePullMultiplier.let {
            ((sim.verifiedPullMultiplier * 0.7f + it * 0.3f) * matchPowerGain).coerceIn(0.72f, 1.48f)
        }
        val powerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult)
        val cal = shotMemory?.boardCalibration?.takeIf { it.enabled }
        val calPullScale = cal?.maxPullScale ?: 1.0f
        val compVerifiedDir = if (!winner.isBreakShot && !winner.isComboShot) {
            val targetDir = (winner.targetPocket - winner.targetCoin.center).normalized()
            applyCutThrowCompensation(
                shotDir = sim.verifiedDir,
                coinDir = targetDir,
                cutAngleDeg = winner.cutAngleDeg,
                throwBias = cal?.cutThrowBias ?: 1.0f
            )
        } else {
            sim.verifiedDir
        }
        val dragEnd = computeSlingshotDragEnd(
            strikerPos = winner.strikerPos,
            shotDir = compVerifiedDir,
            totalDistance = winner.totalDistance,
            boardWidth = board.width,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            cutAngleDeg = winner.cutAngleDeg,
            powerMultiplier = effectiveMult * calPullScale,
            deadzonePx = cal?.slingshotDeadzonePx ?: 22f,
            curveGamma = cal?.slingshotCurveGamma ?: 1.0f
        )
        val kindTag = when {
            winner.isBreakShot -> "T3-Break"
            sim.isTrainingMatched && sim.isSureShot -> "T1-TRAIN-SURE"
            sim.isCollisionAssistedSureShot -> "T2-CAROM-SURE"
            sim.isSureShot -> "T2-HYBRID-SURE"
            winner.isComboShot -> "T3-Combo"
            winner.isBankShot -> "T3-Bank"
            else -> "T2-Sim"
        }
        val kissTag = when {
            sim.isCollisionAssistedSureShot -> ", carom-pot"
            sim.predictedKissBlock -> ", kiss-miss"
            else -> ", clean"
        }
        val pocketLabel = CarromMemoryData.pocketName(winner.pocketIndex)
        val robPct = (sim.robustnessScore * 100).toInt()
        return CarromShotSolution(
            strikerPos = winner.strikerPos,
            dragEnd = dragEnd,
            ghostBall = winner.ghostBall,
            targetCoin = winner.targetCoin,
            targetPocket = winner.targetPocket,
            pocketIndex = winner.pocketIndex,
            cutAngleDeg = winner.cutAngleDeg,
            isBankShot = winner.isBankShot,
            isComboShot = winner.isComboShot,
            isBreakShot = winner.isBreakShot,
            isSureShot = sim.isSureShot,
            isCollisionAssistedSureShot = sim.isCollisionAssistedSureShot,
            isTrainingMatched = sim.isTrainingMatched,
            rejectedDueToCollisionOrFoul = sim.rejectedDueToCollisionOrFoul,
            powerStyle = powerStyle,
            confidenceScore = if (sim.predictedKissBlock) {
                (sim.physicsCompositeScore * 0.72f).coerceAtLeast(0.10f)
            } else {
                sim.physicsCompositeScore
            },
            reason = "$kindTag ${winner.targetCoin.color.name} (${powerStyle.label}) cut=${winner.cutAngleDeg.toInt()}° -> $pocketLabel [Sim ${sim.rolloutsRun}x/${sim.elapsedMs}ms, rob=${robPct}%$kissTag]",
            totalDistance = winner.totalDistance,
            pullMultiplier = effectiveMult
        )
    }

    /**
     * Striker sweep (try-and-see aim test, user-asked): for ONE chosen coin
     * + pocket, try each candidate striker position on the baseline in
     * turn, simulate the shot (ghost ball + physics rollout), and record
     * for EACH position whether the coin pots, misses, or risks a
     * foul/scratch. The live probe loop IS this test: it slides to the
     * first POTS position and fires. The Aim Lab shows the same table so
     * the user can see the bot testing positions ("পকেটে যাবে" /
     * "যাবে না" / "ফাউল ঝুঁকি" per position).
     *
     * Cost: one refineWithPhysicsRollout per tried position (~35 rollouts
     * each). Live play caps at 9 positions (subsampled baseline grid);
     * the Aim Lab runs the full grid. Run ONCE per turn per (coin,
     * pocket) and cache — never re-sweep every cycle. With the rollout
     * setting off, positions with a clean analytic line report POTS at
     * 0.50 confidence (analytic only, never "sure"); the live loop only
     * fires on a physics-verified sure solution.
     */
    enum class StrikerSweepOutcome { POTS, MISSES, FOUL_RISK }

    data class StrikerSweepEntry(
        val strikerPos: Point2D,
        val strikerNormX: Float,
        val outcome: StrikerSweepOutcome,
        val solution: CarromShotSolution?,
        val confidence: Float
    )

    fun sweepStrikerAim(
        board: CarromBoardLayout,
        coin: CarromPiece,
        pocketIndex: Int,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        existingStriker: CarromPiece? = null,
        shotMemory: CarromMemoryData? = null,
        usePhysicsRollout: Boolean = true,
        isFreestyle: Boolean = false,
        maxPositions: Int = 9
    ): List<StrikerSweepEntry> {
        if (board.pockets.size < 4) return emptyList()
        val pocketIdx = pocketIndex.coerceIn(0, 3)
        val pocket = board.pockets[pocketIdx]
        val obstacles = pieces.filter { it.color != CoinColor.STRIKER }
        val allCandidates = buildStrikerCandidates(
            board = board,
            obstacles = obstacles,
            existingStriker = existingStriker,
            canRepositionStriker = true
        )
        if (allCandidates.isEmpty()) return emptyList()
        // Subsample the baseline grid evenly (left -> right), so the live
        // sweep stays inside the 5s budget. "First POTS" = leftmost
        // potting position, matching the probe loop's left-to-right order.
        val tried = if (maxPositions >= allCandidates.size) {
            allCandidates.toList()
        } else {
            val picked = mutableListOf<Point2D>()
            val step = allCandidates.size.toFloat() / maxPositions
            var i = 0f
            while (picked.size < maxPositions && i < allCandidates.size) {
                val c = allCandidates[i.toInt()]
                if (picked.none { kotlin.math.abs(it.x - c.x) < 1f }) picked.add(c)
                i += step
            }
            picked
        }
        fun normX(p: Point2D) = ((p.x - board.left) / board.width).coerceIn(0f, 1f)

        val coinToPocket = pocket - coin.center
        val distCoinPocket = coinToPocket.length()
        if (distCoinPocket < board.coinRadiusPx * 1.15f) return emptyList()
        val dirCp = coinToPocket.normalized()
        val impactDist = board.coinRadiusPx + board.strikerRadiusPx
        val ghostBall = coin.center - dirCp * impactDist
        if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.55f)) return emptyList()
        // Pocket path blocked from every striker position: nothing to try.
        if (!isSegmentClear(
                start = coin.center,
                end = pocket,
                obstacles = obstacles,
                excluded = coin,
                clearanceRadius = board.coinRadiusPx * 1.85f
            )
        ) {
            return tried.map { StrikerSweepEntry(it, normX(it), StrikerSweepOutcome.MISSES, null, 0f) }
        }

        val entries = mutableListOf<StrikerSweepEntry>()
        for (strikerPos in tried) {
            val nx = normX(strikerPos)
            val strikerToGhost = ghostBall - strikerPos
            val distStrikerGhost = strikerToGhost.length()
            if (distStrikerGhost < board.strikerRadiusPx * 1.1f) {
                entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.MISSES, null, 0f))
                continue
            }
            val dirSg = strikerToGhost.normalized()
            val cosAngle = dirSg.dot(dirCp).coerceIn(-1f, 1f)
            val cutAngleDeg = (acos(cosAngle) * (180.0 / PI)).toFloat()
            val isLowerHalfBottomCut = (pocketIdx == 2 || pocketIdx == 3) &&
                coin.center.y >= board.top + board.height * 0.58f
            val maxAllowedCut = if (isLowerHalfBottomCut) 72f else 64f
            if (cutAngleDeg > maxAllowedCut) {
                entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.MISSES, null, 0f))
                continue
            }
            if (!isSegmentClear(
                    start = strikerPos,
                    end = ghostBall,
                    obstacles = obstacles,
                    excluded = coin,
                    clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.88f
                )
            ) {
                entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.MISSES, null, 0f))
                continue
            }
            val ghostClearRadius = board.coinRadiusPx * 1.2f + board.strikerRadiusPx
            if (obstacles.any { other ->
                    other !== coin && (other.center - ghostBall).length() < ghostClearRadius
                }
            ) {
                entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.MISSES, null, 0f))
                continue
            }
            if (willStrikerScratchIntoPocket(
                    strikerPos = strikerPos,
                    ghostBall = ghostBall,
                    dirSg = dirSg,
                    dirCp = dirCp,
                    cutAngleDeg = cutAngleDeg,
                    board = board,
                    extraSafetyMargin = true
                )
            ) {
                entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.FOUL_RISK, null, 0f))
                continue
            }
            val distRatio = ((distStrikerGhost + distCoinPocket) / board.width.coerceAtLeast(1f)).coerceIn(0.15f, 1.8f)
            val learnedPowerScale = shotMemory?.learnedPowerScaleFor(pocketIdx, cutAngleDeg, distRatio) ?: 1.0f
            val matchPowerGain = shotMemory?.matchCalibration?.shotPowerGain ?: 1.0f
            val isHanger = distCoinPocket <= board.coinRadiusPx * 5.2f
            val baseMult = if (isHanger) 0.85f else 1.0f
            val effectiveMult = baseMult * learnedPowerScale * matchPowerGain
            val candPowerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult)
            if (shotMemory?.isFoulBlacklisted(
                    board = board,
                    coinCenter = coin.center,
                    strikerPos = strikerPos,
                    pocketIndex = pocketIdx,
                    cutAngleDeg = cutAngleDeg,
                    powerStyle = candPowerStyle
                ) == true
            ) {
                entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.FOUL_RISK, null, 0f))
                continue
            }
            if (!usePhysicsRollout) {
                // Analytic line only — never "sure" without the rollout.
                entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.POTS, null, 0.50f))
                continue
            }
            val angleScore = 1f - (cutAngleDeg / 74f) * 0.55f
            val distScore = 1f - ((distCoinPocket + distStrikerGhost) / (board.width * 2.2f)).coerceIn(0f, 0.35f)
            val candidate = RolloutCandidate(
                strikerPos = strikerPos,
                baseDir = dirSg,
                ghostBall = ghostBall,
                targetCoin = coin,
                targetPocket = pocket,
                pocketIndex = pocketIdx,
                cutAngleDeg = cutAngleDeg,
                isBankShot = false,
                isComboShot = false,
                isBreakShot = false,
                basePullMultiplier = baseMult * learnedPowerScale,
                staticScore = (angleScore * 0.65f + distScore * 0.35f).coerceIn(0.05f, 1f),
                totalDistance = distStrikerGhost + distCoinPocket,
                trainingMatchScore = 0f,
                collisionRiskPenalty = 0f
            )
            val refined = refineWithPhysicsRollout(
                board = board,
                pieces = pieces,
                myCoinColor = myCoinColor,
                candidates = listOf(candidate),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = shotMemory,
                isFreestyle = isFreestyle
            )
            when {
                refined == null -> entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.FOUL_RISK, null, 0f))
                refined.isSureShot -> entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.POTS, refined, refined.confidenceScore))
                else -> entries.add(StrikerSweepEntry(strikerPos, nx, StrikerSweepOutcome.MISSES, refined, refined.confidenceScore))
            }
        }
        return entries
    }

    private fun solveOneRailBankShot(
        board: CarromBoardLayout,
        targetPieces: List<CarromPiece>,
        obstacles: List<CarromPiece>,
        strikerCandidates: List<Point2D>,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        outCandidates: MutableList<RolloutCandidate>? = null,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        var bestBank: CarromShotSolution? = null
        val r = board.coinRadiusPx
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        // Mirror top, left, right, and bottom rails for 1-cushion bank geometry
        val railPlanes = listOf(
            "top" to { p: Point2D -> Point2D(p.x, 2f * (board.top + r) - p.y) },
            "left" to { p: Point2D -> Point2D(2f * (board.left + r) - p.x, p.y) },
            "right" to { p: Point2D -> Point2D(2f * (board.right - r) - p.x, p.y) },
            "bottom" to { p: Point2D -> Point2D(p.x, 2f * (board.bottom - r) - p.y) }
        )

        for (coin in targetPieces) {
            val zonePlan = planPocketForCoinPosition(board, coin.center)
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                for ((railName, mirrorFn) in railPlanes) {
                    val mirroredPocket = mirrorFn(pocket)
                    val toMirror = (mirroredPocket - coin.center)
                    if (toMirror.length() < r * 2f) continue
                    val dirBank = toMirror.normalized()
                    val ghostBall = coin.center - dirBank * (board.coinRadiusPx + board.strikerRadiusPx)
                    if (!isInsidePlayableBoard(ghostBall, board, margin = board.strikerRadiusPx * 0.55f)) continue

                    for (strikerPos in strikerCandidates) {
                        val sg = ghostBall - strikerPos
                        if (sg.length() < r * 1.5f) continue
                        val dirSg = sg.normalized()
                        val cutDeg = (acos(dirSg.dot(dirBank).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                        if (cutDeg > 46f) continue

                        if (!isSegmentClear(strikerPos, ghostBall, obstacles, coin, r + board.strikerRadiusPx * 0.9f)) {
                            continue
                        }
                        if (willStrikerScratchIntoPocket(strikerPos, ghostBall, dirSg, dirBank, cutDeg, board, extraScratchGuard)) {
                            continue
                        }

                        val bankDistRatio = ((sg.length() + toMirror.length()) / board.width.coerceAtLeast(1f)).coerceIn(0.15f, 1.8f)
                        val learnedScale = shotMemory?.learnedPowerScaleFor(pocketIdx, cutDeg, bankDistRatio) ?: 1.0f
                        // Bank patterns (learned by self-play / live play) feed
                        // the same blend as direct shots: a matched bank
                        // pattern lowers the rollout's robustness bar via
                        // trainingMatchScore and tunes the pull. Banks stay
                        // capped below direct sure shots (score cap 0.88).
                        val bankTrainingMatch = shotMemory?.matchCleanShotPattern(
                            board = board,
                            strikerPos = strikerPos,
                            coinCenter = coin.center,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutDeg,
                            distRatio = bankDistRatio,
                            obstacles = obstacles,
                            targetCoin = coin,
                            ghostBall = ghostBall,
                            pocketPos = pocket,
                            isBankShot = true,
                            isComboShot = false
                        )
                        val bankPatternScale = if (bankTrainingMatch != null) {
                            (bankTrainingMatch.recommendedPullScale * 0.65f + learnedScale * 0.35f).coerceIn(0.74f, 1.36f)
                        } else {
                            learnedScale
                        }
                        val effectiveMult = 1.24f * bankPatternScale
                        val bankPowerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult)
                        if (shotMemory?.isFoulBlacklisted(
                                board = board,
                                coinCenter = coin.center,
                                strikerPos = strikerPos,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = cutDeg,
                                powerStyle = bankPowerStyle
                            ) == true
                        ) {
                            continue
                        }

                        val memoryBias = shotMemory?.confidenceBiasFor(pocketIdx, cutDeg, bankDistRatio) ?: 0f
                        val normStrikerX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
                        val heatmapPenalty = (shotMemory?.scratchHeatmap?.riskFor(pocketIdx, cutDeg, normStrikerX) ?: 0f) * 0.25f
                        val bottomBankBoost = if (zonePlan.preferBankForBottomPockets && pocketIdx == zonePlan.primaryPocketIndex) 0.08f else 0f
                        val freestyleVal = if (isFreestyle) {
                            when (coin.color) {
                                CoinColor.QUEEN -> 0.14f
                                CoinColor.WHITE -> 0.07f
                                else -> 0f
                            }
                        } else 0f
                        val score = (0.64f - (cutDeg / 66f) * 0.25f + bottomBankBoost + freestyleVal + memoryBias * 0.6f - heatmapPenalty + (bankTrainingMatch?.matchScore ?: 0f) * 0.08f).coerceIn(0.05f, 0.88f)

                        outCandidates?.add(
                            RolloutCandidate(
                                strikerPos = strikerPos,
                                baseDir = dirSg,
                                ghostBall = ghostBall,
                                targetCoin = coin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = cutDeg,
                                isBankShot = true,
                                isComboShot = false,
                                isBreakShot = false,
                                basePullMultiplier = effectiveMult,
                                staticScore = score,
                                totalDistance = sg.length() + toMirror.length(),
                                trainingMatchScore = bankTrainingMatch?.matchScore ?: 0f
                            )
                        )

                        if (bestBank == null || score > bestBank.confidenceScore) {
                            val dragEnd = computeSlingshotDragEnd(
                                strikerPos = strikerPos,
                                shotDir = dirSg,
                                totalDistance = sg.length() + toMirror.length(),
                                boardWidth = board.width,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                cutAngleDeg = cutDeg,
                                powerMultiplier = effectiveMult // Compensate rail restitution damping
                            )
                            bestBank = CarromShotSolution(
                                strikerPos = strikerPos,
                                dragEnd = dragEnd,
                                ghostBall = ghostBall,
                                targetCoin = coin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = cutDeg,
                                isBankShot = true,
                                powerStyle = CarromPowerStyle.fromPullMultiplier(effectiveMult),
                                confidenceScore = score,
                                reason = "1-rail ($railName) bank ${coin.color.name} -> ${CarromMemoryData.pocketName(pocketIdx)} cut=${cutDeg.toInt()}°"
                            )
                        }
                    }
                }
            }
        }
        return bestBank
    }

    private fun solveTwoCoinComboShot(
        board: CarromBoardLayout,
        targetPieces: List<CarromPiece>,
        obstacles: List<CarromPiece>,
        strikerCandidates: List<Point2D>,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        outCandidates: MutableList<RolloutCandidate>? = null
    ): CarromShotSolution? {
        var bestCombo: CarromShotSolution? = null
        val coinImpactDist = board.coinRadiusPx * 2.0f
        val strikerImpactDist = board.coinRadiusPx + board.strikerRadiusPx
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        for (finalCoin in targetPieces) {
            board.pockets.forEachIndexed { pocketIdx, pocket ->
                val toPocket = pocket - finalCoin.center
                val distPocket = toPocket.length()
                if (distPocket < board.coinRadiusPx * 1.2f) return@forEachIndexed
                val dirFp = toPocket.normalized()
                if (!isSegmentClear(finalCoin.center, pocket, obstacles, finalCoin, board.coinRadiusPx * 1.85f)) {
                    return@forEachIndexed
                }

                // Required contact point for helper coin to drive finalCoin into pocket
                val helperGhost = finalCoin.center - dirFp * coinImpactDist
                if (!isInsidePlayableBoard(helperGhost, board, margin = board.coinRadiusPx)) return@forEachIndexed

                for (helperCoin in targetPieces) {
                    if (helperCoin === finalCoin) continue
                    val helperToGhost = helperGhost - helperCoin.center
                    val distHelper = helperToGhost.length()
                    if (distHelper < board.coinRadiusPx * 0.5f || distHelper > board.width * 0.55f) continue

                    val dirHg = helperToGhost.normalized()
                    val comboCutDeg = (acos(dirHg.dot(dirFp).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                    if (comboCutDeg > 34f) continue

                    if (!isSegmentClear(
                            start = helperCoin.center,
                            end = helperGhost,
                            obstacles = obstacles.filter { it !== finalCoin },
                            excluded = helperCoin,
                            clearanceRadius = board.coinRadiusPx * 1.85f
                        )
                    ) {
                        continue
                    }

                    val strikerGhost = helperCoin.center - dirHg * strikerImpactDist
                    if (!isInsidePlayableBoard(strikerGhost, board, margin = board.strikerRadiusPx)) continue

                    for (strikerPos in strikerCandidates) {
                        val sg = strikerGhost - strikerPos
                        if (sg.length() < board.strikerRadiusPx * 1.2f) continue
                        val dirSg = sg.normalized()
                        val entryCutDeg = (acos(dirSg.dot(dirHg).coerceIn(-1f, 1f)) * (180.0 / PI)).toFloat()
                        if (entryCutDeg > 36f) continue

                        if (!isSegmentClear(
                                start = strikerPos,
                                end = strikerGhost,
                                obstacles = obstacles,
                                excluded = helperCoin,
                                clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.88f
                            )
                        ) {
                            continue
                        }
                        if (willStrikerScratchIntoPocket(strikerPos, strikerGhost, dirSg, dirHg, entryCutDeg, board, extraScratchGuard)) {
                            continue
                        }
                        if (shotMemory?.isFoulBlacklisted(
                                board = board,
                                coinCenter = finalCoin.center,
                                strikerPos = strikerPos,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = entryCutDeg,
                                powerStyle = CarromPowerStyle.HARD
                            ) == true
                        ) {
                            continue
                        }

                        // Combo patterns (self-play / live): a matched pattern
                        // vouches for the geometry — its score feeds the
                        // rollout (trainingMatchScore lowers the sure-shot
                        // robustness bar) and adds a small bonus. Combo power
                        // stays at the tuned 1.26f: the pattern never re-tunes
                        // the two-stage energy transfer. Combos stay capped
                        // below direct sure shots (score cap 0.65).
                        val comboTrainingMatch = shotMemory?.matchCleanShotPattern(
                            board = board,
                            strikerPos = strikerPos,
                            coinCenter = helperCoin.center,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = entryCutDeg,
                            distRatio = ((sg.length() + distHelper + distPocket) / board.width.coerceAtLeast(1f)).coerceIn(0.15f, 1.8f),
                            obstacles = obstacles,
                            targetCoin = helperCoin,
                            ghostBall = strikerGhost,
                            pocketPos = pocket,
                            isBankShot = false,
                            isComboShot = true
                        )
                        val score = (0.61f - (comboCutDeg + entryCutDeg) / 140f + (comboTrainingMatch?.matchScore ?: 0f) * 0.06f).coerceIn(0.45f, 0.65f)
                        outCandidates?.add(
                            RolloutCandidate(
                                strikerPos = strikerPos,
                                baseDir = dirSg,
                                ghostBall = strikerGhost,
                                targetCoin = finalCoin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = entryCutDeg,
                                isBankShot = false,
                                isComboShot = true,
                                isBreakShot = false,
                                basePullMultiplier = 1.26f,
                                staticScore = score,
                                totalDistance = sg.length() + distHelper + distPocket,
                                trainingMatchScore = comboTrainingMatch?.matchScore ?: 0f
                            )
                        )
                        if (bestCombo == null || score > bestCombo.confidenceScore) {
                            val dragEnd = computeSlingshotDragEnd(
                                strikerPos = strikerPos,
                                shotDir = dirSg,
                                totalDistance = sg.length() + distHelper + distPocket,
                                boardWidth = board.width,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                cutAngleDeg = entryCutDeg + comboCutDeg * 0.5f,
                                powerMultiplier = 1.28f // Extra impulse for 2-stage collision transfer
                            )
                            bestCombo = CarromShotSolution(
                                strikerPos = strikerPos,
                                dragEnd = dragEnd,
                                ghostBall = strikerGhost,
                                targetCoin = finalCoin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = entryCutDeg,
                                isBankShot = false,
                                isComboShot = true,
                                powerStyle = CarromPowerStyle.HARD,
                                confidenceScore = score,
                                reason = "2-Coin Combo ${helperCoin.color.name}->${finalCoin.color.name} (${entryCutDeg.toInt()}°) -> ${CarromMemoryData.pocketName(pocketIdx)}"
                            )
                        }
                    }
                }
            }
        }
        return bestCombo
    }

    private fun solveClusterBreakShot(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        targetPieces: List<CarromPiece>,
        obstacles: List<CarromPiece>,
        strikerCandidates: List<Point2D>,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData? = null,
        isFreestyle: Boolean = false
    ): CarromShotSolution? {
        if (targetPieces.isEmpty() || strikerCandidates.isEmpty()) return null

        // Pick the target coin with the highest local neighbor density (cluster/break target)
        val anchorCoin = targetPieces.maxByOrNull { candidate ->
            obstacles.count { other -> (other.center - candidate.center).length() <= board.coinRadiusPx * 4.5f }
        } ?: return null

        var nearestPocketIdx = 0
        var nearestDist = Float.MAX_VALUE
        board.pockets.forEachIndexed { idx, p ->
            val d = (p - anchorCoin.center).length()
            if (d < nearestDist) {
                nearestDist = d
                nearestPocketIdx = idx
            }
        }
        val nearestPocket = board.pockets[nearestPocketIdx]
        var bestBreak: CarromShotSolution? = null
        var bestScore = Float.NEGATIVE_INFINITY
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        for (strikerPos in strikerCandidates) {
            val toCoin = anchorCoin.center - strikerPos
            if (toCoin.length() < board.strikerRadiusPx * 1.5f) continue
            val dir = toCoin.normalized()

            val clearPath = isSegmentClear(
                start = strikerPos,
                end = anchorCoin.center,
                obstacles = obstacles,
                excluded = anchorCoin,
                clearanceRadius = board.coinRadiusPx + board.strikerRadiusPx * 0.85f
            )
            if (willStrikerScratchIntoPocket(strikerPos, anchorCoin.center, dir, dir, 0f, board, extraScratchGuard)) {
                continue
            }
            if (!CarromPhysicsSimulator.isBreakTrajectoryScratchFree(
                    board = board,
                    pieces = pieces,
                    strikerPos = strikerPos,
                    shotDir = dir,
                    pullMultiplier = 1.25f,
                    myCoinColor = myCoinColor,
                    shotMemory = shotMemory,
                    isFreestyle = isFreestyle
                )
            ) {
                continue
            }

            // Prefer unobstructed hit into cluster with verified zero-scratch physics
            val score = (if (clearPath) 0.56f else 0.51f) - (toCoin.length() / (board.width * 4f))
            if (score > bestScore) {
                bestScore = score
                val dragEnd = computeSlingshotDragEnd(
                    strikerPos = strikerPos,
                    shotDir = dir,
                    totalDistance = board.width * 1.35f,
                    boardWidth = board.width,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    cutAngleDeg = 0f,
                    powerMultiplier = 1.25f
                )
                bestBreak = CarromShotSolution(
                    strikerPos = strikerPos,
                    dragEnd = dragEnd,
                    ghostBall = anchorCoin.center,
                    targetCoin = anchorCoin,
                    targetPocket = nearestPocket,
                    pocketIndex = nearestPocketIdx,
                    cutAngleDeg = 0f,
                    isBankShot = false,
                    isBreakShot = true,
                    powerStyle = CarromPowerStyle.HARD,
                    confidenceScore = score.coerceIn(0.50f, 0.57f),
                    reason = "Safe Break / Cluster Scatter -> ${anchorCoin.color.name} (${anchorCoin.center.x.toInt()},${anchorCoin.center.y.toInt()})"
                )
            }
        }

        // Safe zero-scratch fallback sweep across angles if direct anchor direction failed scratch guard
        if (bestBreak == null) {
            val strikerPos = strikerCandidates.first()
            val sweepAnglesDeg = floatArrayOf(0f, -10f, 10f, -20f, 20f, -32f, 32f, -45f, 45f, -58f, 58f, -68f, 68f)
            val candidateDirs = sweepAnglesDeg.map { deg ->
                val rad = deg * (PI / 180.0)
                Point2D(kotlin.math.sin(rad).toFloat(), (-kotlin.math.cos(rad)).toFloat()).normalized()
            }
            val safeDir = candidateDirs.firstOrNull { candDir ->
                CarromPhysicsSimulator.isBreakTrajectoryScratchFree(
                    board = board,
                    pieces = pieces,
                    strikerPos = strikerPos,
                    shotDir = candDir,
                    pullMultiplier = 0.88f,
                    myCoinColor = myCoinColor,
                    shotMemory = shotMemory,
                    isFreestyle = isFreestyle
                )
            } ?: Point2D(0f, -1f)

            val dragEnd = computeSlingshotDragEnd(
                strikerPos = strikerPos,
                shotDir = safeDir,
                totalDistance = board.width * 0.95f,
                boardWidth = board.width,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                cutAngleDeg = 0f,
                powerMultiplier = 0.88f
            )
            bestBreak = CarromShotSolution(
                strikerPos = strikerPos,
                dragEnd = dragEnd,
                ghostBall = strikerPos + safeDir * (board.width * 0.4f),
                targetCoin = anchorCoin,
                targetPocket = nearestPocket,
                pocketIndex = nearestPocketIdx,
                cutAngleDeg = 0f,
                isBankShot = false,
                isBreakShot = true,
                powerStyle = CarromPowerStyle.MEDIUM,
                confidenceScore = 0.51f,
                reason = "Zero-Foul Safe Break -> ${anchorCoin.color.name} (${anchorCoin.center.x.toInt()},${anchorCoin.center.y.toInt()})"
            )
        }
        return bestBreak
    }

    private fun buildStrikerCandidates(
        board: CarromBoardLayout,
        obstacles: List<CarromPiece>,
        existingStriker: CarromPiece?,
        canRepositionStriker: Boolean
    ): List<Point2D> {
        val margin = board.strikerRadiusPx * 2.1f
        val minX = board.left + margin
        val maxX = board.right - margin
        if (maxX <= minX) return emptyList()

        val candidates = mutableListOf<Point2D>()
        existingStriker?.let {
            val clampedX = it.center.x.coerceIn(minX, maxX)
            candidates.add(Point2D(clampedX, it.center.y))
        }

        // If we cannot reposition via a separate slider knob and already found the striker,
        // shoot ONLY from its actual position rather than dragging empty wood.
        if (existingStriker != null && !canRepositionStriker) {
            return candidates
        }

        val steps = 24
        val stepSize = (maxX - minX) / steps
        for (i in 0..steps) {
            val pos = Point2D(minX + i * stepSize, board.baselineY)
            if (isPositionFree(pos, obstacles, board.coinRadiusPx + board.strikerRadiusPx * 0.95f)) {
                candidates.add(pos)
            }
        }
        return candidates
    }

    private fun isPositionFree(
        point: Point2D,
        obstacles: List<CarromPiece>,
        minDistance: Float
    ): Boolean {
        return obstacles.none { obs -> (obs.center - point).length() < minDistance }
    }

    internal fun isSegmentClear(
        start: Point2D,
        end: Point2D,
        obstacles: List<CarromPiece>,
        excluded: CarromPiece,
        clearanceRadius: Float
    ): Boolean {
        val seg = end - start
        val segLenSq = seg.x * seg.x + seg.y * seg.y
        if (segLenSq < 1e-3f) return true

        for (obs in obstacles) {
            if (obs === excluded) continue
            val toObs = obs.center - start
            val t = (toObs.dot(seg) / segLenSq).coerceIn(0f, 1f)
            // Ignore exact endpoints near start/end
            if (t <= 0.03f || t >= 0.97f) continue
            val closest = Point2D(start.x + seg.x * t, start.y + seg.y * t)
            val dist = (obs.center - closest).length()
            if (dist < clearanceRadius) {
                return false
            }
        }
        return true
    }

    internal fun willStrikerScratchIntoPocket(
        strikerPos: Point2D,
        ghostBall: Point2D,
        dirSg: Point2D,
        dirCp: Point2D,
        cutAngleDeg: Float,
        board: CarromBoardLayout,
        extraSafetyMargin: Boolean = false
    ): Boolean {
        val followMult = if (extraSafetyMargin) 1.48f else 1.32f
        val deflectMult = if (extraSafetyMargin) 1.34f else 1.22f

        // 1. Nearly straight follow-through (< 16 deg): striker continues roughly along dirSg
        if (cutAngleDeg < 16f) {
            val followEnd = ghostBall + dirSg * (board.width * 0.95f)
            for (pocket in board.pockets) {
                if (distancePointToSegment(pocket, ghostBall, followEnd) < board.pocketRadiusPx * followMult) {
                    return true
                }
            }
        }

        // 2. Deflection tangent ray: post-collision striker moves perpendicular to dirCp
        // Tangent sign points in the same half-plane as dirSg
        val tangent1 = Point2D(-dirCp.y, dirCp.x)
        val tangent = if (tangent1.dot(dirSg) >= 0f) tangent1 else Point2D(dirCp.y, -dirCp.x)
        val deflectEnd = ghostBall + tangent * (board.width * 0.7f)
        for (pocket in board.pockets) {
            if (distancePointToSegment(pocket, ghostBall, deflectEnd) < board.pocketRadiusPx * deflectMult) {
                return true
            }
        }
        return false
    }

    private fun distancePointToSegment(p: Point2D, a: Point2D, b: Point2D): Float {
        val ab = b - a
        val abLenSq = ab.x * ab.x + ab.y * ab.y
        if (abLenSq < 1e-4f) return (p - a).length()
        val t = ((p - a).dot(ab) / abLenSq).coerceIn(0f, 1f)
        val proj = Point2D(a.x + ab.x * t, a.y + ab.y * t)
        return (p - proj).length()
    }

    private fun isInsidePlayableBoard(p: Point2D, board: CarromBoardLayout, margin: Float): Boolean {
        return p.x in (board.left + margin)..(board.right - margin) &&
            p.y in (board.top + margin)..(board.bottom - margin)
    }

    /**
     * Compensates for frictional cut-induced throw on oblique cuts (12°..62°):
     * nudges the striker aim slightly fuller into the target coin (~0.35°–1.35° * throwBias)
     * so contact friction doesn't under-cut the coin into the near pocket jaw.
     */
    internal fun applyCutThrowCompensation(
        shotDir: Point2D,
        coinDir: Point2D,
        cutAngleDeg: Float,
        throwBias: Float = 1.0f
    ): Point2D {
        if (cutAngleDeg < 12f || cutAngleDeg > 62f || throwBias <= 0.02f) return shotDir
        // Cross product z-component determines which side of target coin we are cutting
        val crossZ = shotDir.x * coinDir.y - shotDir.y * coinDir.x
        if (abs(crossZ) < 1e-4f) return shotDir
        val sinFactor = sin((cutAngleDeg.coerceIn(12f, 60f) * PI / 180.0)).toFloat()
        // Max ~1.35 deg full compensation at 1.0x bias on steep cuts
        val compDeg = (1.35f * sinFactor * throwBias.coerceIn(0f, 2f)) * if (crossZ > 0f) 1f else -1f
        val rad = (compDeg * PI / 180.0).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        return Point2D(
            x = shotDir.x * c - shotDir.y * s,
            y = shotDir.x * s + shotDir.y * c
        ).normalized()
    }

    internal fun computeSlingshotDragEnd(
        strikerPos: Point2D,
        shotDir: Point2D,
        totalDistance: Float,
        boardWidth: Float,
        screenWidth: Int,
        screenHeight: Int,
        cutAngleDeg: Float = 0f,
        powerMultiplier: Float = 1.0f,
        deadzonePx: Float = 22f,
        curveGamma: Float = 1.0f
    ): Point2D {
        // Momentum transfer scales with cos(cutAngle), so angled cuts need ~1/cos(theta) stronger pull
        val cutRad = (cutAngleDeg.coerceIn(0f, 68f) * PI / 180.0)
        val cutCompensation = (1.0 / cos(cutRad)).toFloat().coerceIn(1.0f, 1.85f)
        val basePower = (totalDistance / boardWidth).coerceIn(0.45f, 1.35f)
        val effectivePower = (basePower * cutCompensation * powerMultiplier).coerceIn(0.45f, 1.75f)
        val normForce = ((effectivePower - 0.45f) / (1.75f - 0.45f)).coerceIn(0f, 1f)
        val shapedForce = normForce.pow(curveGamma.coerceIn(0.75f, 1.35f))
        val minPull = max(124f + deadzonePx.coerceIn(0f, 60f) * 0.5f, boardWidth * 0.19f)
        val maxPull = min(410f, boardWidth * 0.45f).coerceAtLeast(minPull + 24f)
        val pullLength = (minPull + shapedForce * (maxPull - minPull)).coerceIn(minPull, maxPull)
        val pullVec = Point2D(-shotDir.x * pullLength, -shotDir.y * pullLength)

        val minX = 28f
        val maxX = max(29f, screenWidth - 28f)
        val minY = 24f
        // Keep pull endpoint clear of bottom system navigation/gesture bar and side back-swipe zones on Android 13-15 OEMs
        val bottomNavClearance = max(56f, screenHeight * 0.05f)
        val maxY = max(minY + 1f, screenHeight - bottomNavClearance)

        var scale = 1f
        if (pullVec.x < -1e-3f && strikerPos.x + pullVec.x < minX) {
            scale = min(scale, ((minX - strikerPos.x) / pullVec.x).coerceIn(0.2f, 1f))
        } else if (pullVec.x > 1e-3f && strikerPos.x + pullVec.x > maxX) {
            scale = min(scale, ((maxX - strikerPos.x) / pullVec.x).coerceIn(0.2f, 1f))
        }
        if (pullVec.y < -1e-3f && strikerPos.y + pullVec.y < minY) {
            scale = min(scale, ((minY - strikerPos.y) / pullVec.y).coerceIn(0.2f, 1f))
        } else if (pullVec.y > 1e-3f && strikerPos.y + pullVec.y > maxY) {
            scale = min(scale, ((maxY - strikerPos.y) / pullVec.y).coerceIn(0.2f, 1f))
        }

        val clamped = strikerPos + pullVec * scale
        return Point2D(
            x = clamped.x.coerceIn(minX, maxX),
            y = clamped.y.coerceIn(minY, maxY)
        )
    }
}
