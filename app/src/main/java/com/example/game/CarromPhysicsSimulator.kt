package com.example.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

data class RolloutCandidate(
    val strikerPos: Point2D,
    val baseDir: Point2D,
    val ghostBall: Point2D,
    val targetCoin: CarromPiece,
    val targetPocket: Point2D,
    val pocketIndex: Int,
    val cutAngleDeg: Float,
    val isBankShot: Boolean,
    val isComboShot: Boolean,
    val isBreakShot: Boolean,
    val basePullMultiplier: Float,
    val staticScore: Float,
    val totalDistance: Float,
    val trainingMatchScore: Float = 0f,
    val collisionRiskPenalty: Float = 0f
)

data class RolloutVerificationResult(
    val verifiedDir: Point2D,
    val verifiedPullMultiplier: Float,
    val robustnessScore: Float,
    val predictedMyCoinsPotted: Int,
    val predictedQueenPotted: Boolean,
    val predictedScratchFoul: Boolean,
    val predictedOpponentOnlyFoul: Boolean,
    val predictedKissBlock: Boolean,
    val isCollisionAssistedSureShot: Boolean = false,
    val rejectedDueToCollisionOrFoul: Boolean = false,
    val isSureShot: Boolean,
    val isTrainingMatched: Boolean = false,
    val predictedStrikerPocketIndex: Int?,
    val physicsCompositeScore: Float,
    val rolloutsRun: Int,
    val elapsedMs: Long
)

data class RankedBaselineSpot(
    val strikerX: Float,
    val predictedScore: Float,
    val predictedSureShot: Boolean,
    val collisionAssisted: Boolean,
    val targetCoinColor: CoinColor,
    val pocketIndex: Int,
    val trainingMatchScore: Float = 0f
)

/**
 * Deterministic 2D Rigid-Body Carrom Physics Rollout Simulator (inspired by pooltool / carrom-ai).
 *
 * Models elastic disc-disc impulse collisions (kiss/cluster chain reactions),
 * cushion rail bounces with restitution, sliding table friction, pocket jaw capture wells,
 * and sub-stepped CCD integration so high-power break shots never tunnel.
 *
 * Evaluates angle & power perturbations in ~5–15ms before committing a real swipe.
 */
object CarromPhysicsSimulator {

    private const val STRIKER_MASS = 2.75f
    private const val COIN_MASS = 1.0f
    private const val DISC_RESTITUTION = 0.87f
    private const val RAIL_RESTITUTION = 0.80f
    private const val MAX_SIM_STEPS = 120
    private const val SUBSTEPS_PER_STEP = 6

    private data class SimBody(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val mass: Float,
        val color: CoinColor,
        val isTargetCoin: Boolean,
        var pocketed: Boolean = false,
        var pocketedInIndex: Int = -1
    )

    data class SingleRolloutOutcome(
        val pottedMyCoins: Int,
        val pottedQueen: Boolean,
        val pottedOpponentCoins: Int,
        val strikerScratched: Boolean,
        val strikerPocketIndex: Int?,
        val minTargetToPocketDist: Float,
        val jawClearenceScore: Float,
        val firstStrikerHitIsTarget: Boolean,
        val targetDeflectedByNonTarget: Boolean,
        val kissedObstacleCount: Int,
        val pottedWhiteCoins: Int = 0,
        val pottedBlackCoins: Int = 0
    )

    fun verifyAndRefineCandidates(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        candidates: List<RolloutCandidate>,
        shotMemory: CarromMemoryData?,
        matchCalibration: MatchPhysicsCalibration?,
        isFreestyle: Boolean = false
    ): Pair<RolloutCandidate, RolloutVerificationResult>? {
        if (candidates.isEmpty() || board.pockets.size < 4) return null

        val startNs = System.nanoTime()
        val shotPowerGain = matchCalibration?.shotPowerGain ?: 1.0f
        val safeCandidates = candidates.filterNot { cand ->
            !cand.isBreakShot && shotMemory?.isFoulBlacklisted(
                board = board,
                coinCenter = cand.targetCoin.center,
                strikerPos = cand.strikerPos,
                pocketIndex = cand.pocketIndex,
                cutAngleDeg = cand.cutAngleDeg,
                powerStyle = CarromPowerStyle.fromPullMultiplier(cand.basePullMultiplier * shotPowerGain)
            ) == true
        }
        if (safeCandidates.isEmpty()) return null

        val topCandidates = safeCandidates
            .sortedByDescending { it.staticScore }
            .take(14)

        val frictionScale = matchCalibration?.frictionScale ?: 1.0f
        val jawSafetyBuffer = matchCalibration?.pocketJawSafetyBuffer ?: 0f
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        var bestPair: Pair<RolloutCandidate, RolloutVerificationResult>? = null
        var totalRollouts = 0

        for (candidate in topCandidates) {
            val angleOffsetsDeg = if (candidate.isBreakShot) {
                floatArrayOf(-2.0f, 0f, 2.0f)
            } else {
                floatArrayOf(-1.4f, -0.7f, 0f, 0.7f, 1.4f)
            }
            val powerFactors = if (candidate.isBankShot || candidate.isComboShot) {
                floatArrayOf(0.88f, 0.98f, 1.08f, 1.20f)
            } else {
                floatArrayOf(0.85f, 0.95f, 1.05f, 1.17f)
            }

            var bestLocalScore = Float.NEGATIVE_INFINITY
            var bestAngleDeg = 0f
            var bestPowerFactor = 1.0f
            var bestOutcome: SingleRolloutOutcome? = null

            // Track outcomes keyed by (pwrFactor, angleDeg) so neighbor robustness uses the exact winning power & angle
            val outcomesByKey = HashMap<Pair<Float, Float>, SingleRolloutOutcome>()

            for (pwrFactor in powerFactors) {
                for (angleDeg in angleOffsetsDeg) {
                    val dir = rotateVector(candidate.baseDir, angleDeg)
                    val speed = computeInitialSpeedPx(
                        board = board,
                        cutAngleDeg = candidate.cutAngleDeg,
                        totalDistance = candidate.totalDistance,
                        pullMultiplier = candidate.basePullMultiplier * pwrFactor * shotPowerGain,
                        frictionScale = frictionScale
                    )
                    val outcome = runSingleRollout(
                        board = board,
                        pieces = pieces,
                        strikerPos = candidate.strikerPos,
                        shotDir = dir,
                        initialSpeed = speed,
                        targetCoin = candidate.targetCoin,
                        myCoinColor = myCoinColor,
                        frictionScale = frictionScale,
                        jawSafetyBuffer = jawSafetyBuffer,
                        extraScratchGuard = extraScratchGuard,
                        isFreestyle = isFreestyle
                    )
                    totalRollouts++
                    outcomesByKey[pwrFactor to angleDeg] = outcome

                    val localScore = scoreOutcome(
                        candidate = candidate,
                        outcome = outcome,
                        angleOffsetDeg = angleDeg,
                        shotMemory = shotMemory,
                        isFreestyle = isFreestyle
                    )
                    if (localScore > bestLocalScore) {
                        bestLocalScore = localScore
                        bestAngleDeg = angleDeg
                        bestPowerFactor = pwrFactor
                        bestOutcome = outcome
                    }
                }
            }

            var chosenOutcome = bestOutcome ?: continue
            // Stage 2: Coarse-to-Fine micro-sweep (+-0.22 / +-0.45 deg) to lock pocket-jaw center
            if (!candidate.isBreakShot) {
                val fineAngles = floatArrayOf(-0.45f, -0.22f, 0f, 0.22f, 0.45f)
                val finePowers = floatArrayOf(0.96f, 1.0f, 1.04f)
                val centerAngle = bestAngleDeg
                val centerPower = bestPowerFactor
                for (fp in finePowers) {
                    val pFactor = (centerPower * fp).coerceIn(0.75f, 1.32f)
                    for (fa in fineAngles) {
                        if (fa == 0f && fp == 1.0f) continue
                        val testAngle = centerAngle + fa
                        val dir = rotateVector(candidate.baseDir, testAngle)
                        val speed = computeInitialSpeedPx(
                            board = board,
                            cutAngleDeg = candidate.cutAngleDeg,
                            totalDistance = candidate.totalDistance,
                            pullMultiplier = candidate.basePullMultiplier * pFactor * shotPowerGain,
                            frictionScale = frictionScale
                        )
                        val fineOutcome = runSingleRollout(
                            board = board,
                            pieces = pieces,
                            strikerPos = candidate.strikerPos,
                            shotDir = dir,
                            initialSpeed = speed,
                            targetCoin = candidate.targetCoin,
                            myCoinColor = myCoinColor,
                            frictionScale = frictionScale,
                            jawSafetyBuffer = jawSafetyBuffer,
                            extraScratchGuard = extraScratchGuard,
                            isFreestyle = isFreestyle
                        )
                        totalRollouts++
                        outcomesByKey[pFactor to testAngle] = fineOutcome
                        val fineScore = scoreOutcome(
                            candidate = candidate,
                            outcome = fineOutcome,
                            angleOffsetDeg = testAngle,
                            shotMemory = shotMemory,
                            isFreestyle = isFreestyle
                        )
                        if (fineScore > bestLocalScore) {
                            bestLocalScore = fineScore
                            bestAngleDeg = testAngle
                            bestPowerFactor = pFactor
                            chosenOutcome = fineOutcome
                        }
                    }
                }
            }
            // Measure robustness around bestAngleDeg at bestPowerFactor (+-0.6 / +-1.2 deg swipe jitter)
            val neighborOffsets = if (candidate.isBreakShot) {
                floatArrayOf(-2.0f, 0f, 2.0f)
            } else {
                floatArrayOf(-1.2f, -0.6f, 0f, 0.6f, 1.2f)
            }
            var safeNeighbors = 0
            var neighborTotal = 0
            var anyNeighborScratched = false
            for (deltaDeg in neighborOffsets) {
                val testAngle = bestAngleDeg + deltaDeg
                val neighborOutcome = outcomesByKey[bestPowerFactor to testAngle] ?: run {
                    val dir = rotateVector(candidate.baseDir, testAngle)
                    val speed = computeInitialSpeedPx(
                        board = board,
                        cutAngleDeg = candidate.cutAngleDeg,
                        totalDistance = candidate.totalDistance,
                        pullMultiplier = candidate.basePullMultiplier * bestPowerFactor * shotPowerGain,
                        frictionScale = frictionScale
                    )
                    totalRollouts++
                    runSingleRollout(
                        board = board,
                        pieces = pieces,
                        strikerPos = candidate.strikerPos,
                        shotDir = dir,
                        initialSpeed = speed,
                        targetCoin = candidate.targetCoin,
                        myCoinColor = myCoinColor,
                        frictionScale = frictionScale,
                        jawSafetyBuffer = jawSafetyBuffer,
                        extraScratchGuard = extraScratchGuard,
                        isFreestyle = isFreestyle
                    )
                }
                neighborTotal++
                if (neighborOutcome.strikerScratched) {
                    anyNeighborScratched = true
                }
                val oppOnly = !isFreestyle &&
                    neighborOutcome.pottedOpponentCoins > 0 &&
                    neighborOutcome.pottedMyCoins == 0 &&
                    !neighborOutcome.pottedQueen
                val cleanPot = (neighborOutcome.pottedMyCoins > 0 || neighborOutcome.pottedQueen) &&
                    !neighborOutcome.strikerScratched &&
                    !oppOnly
                if (cleanPot || (candidate.isBreakShot && !neighborOutcome.strikerScratched)) {
                    safeNeighbors++
                }
            }
            // Power-jitter scratch guard: ensure +/-12% swipe speed error does not drop striker into any pocket
            if (!anyNeighborScratched && !chosenOutcome.strikerScratched) {
                val powerJitterFactors = floatArrayOf(0.88f, 1.12f)
                val jitterDir = rotateVector(candidate.baseDir, bestAngleDeg)
                for (pj in powerJitterFactors) {
                    val jitterSpeed = computeInitialSpeedPx(
                        board = board,
                        cutAngleDeg = candidate.cutAngleDeg,
                        totalDistance = candidate.totalDistance,
                        pullMultiplier = candidate.basePullMultiplier * bestPowerFactor * pj * shotPowerGain,
                        frictionScale = frictionScale
                    )
                    totalRollouts++
                    val pjOutcome = runSingleRollout(
                        board = board,
                        pieces = pieces,
                        strikerPos = candidate.strikerPos,
                        shotDir = jitterDir,
                        initialSpeed = jitterSpeed,
                        targetCoin = candidate.targetCoin,
                        myCoinColor = myCoinColor,
                        frictionScale = frictionScale,
                        jawSafetyBuffer = jawSafetyBuffer,
                        extraScratchGuard = extraScratchGuard,
                        isFreestyle = isFreestyle
                    )
                    if (pjOutcome.strikerScratched) {
                        anyNeighborScratched = true
                        break
                    }
                }
            }
            val robustness = if (neighborTotal > 0) safeNeighbors.toFloat() / neighborTotal else 0.5f

            val heatmapRisk = shotMemory?.scratchHeatmap?.riskFor(
                pocketIndex = candidate.pocketIndex,
                cutAngleDeg = candidate.cutAngleDeg,
                strikerNormalizedX = ((candidate.strikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
            ) ?: 0f

            val opponentOnlyFoul = !isFreestyle &&
                chosenOutcome.pottedOpponentCoins > 0 &&
                chosenOutcome.pottedMyCoins == 0 &&
                !chosenOutcome.pottedQueen

            val anyCleanTargetPot = (chosenOutcome.pottedMyCoins > 0 || chosenOutcome.pottedQueen) &&
                !chosenOutcome.strikerScratched &&
                !opponentOnlyFoul

            val hasSecondaryCollision = !chosenOutcome.firstStrikerHitIsTarget ||
                chosenOutcome.targetDeflectedByNonTarget ||
                chosenOutcome.kissedObstacleCount > 0

            // Strict zero-foul rule: never lock a Sure Shot if the chosen trajectory OR any +-1.2 deg jitter neighbor scratches!
            // When Training Data (Watch or past real-game patterns) matches strongly, lock Sure Shot at >=0.40 robustness.
            val requiredRobustness = if (candidate.trainingMatchScore >= 0.52f) 0.40f else 0.55f
            val isSureShot = anyCleanTargetPot &&
                !chosenOutcome.strikerScratched &&
                !anyNeighborScratched &&
                robustness >= requiredRobustness
            val isCollisionAssisted = isSureShot && hasSecondaryCollision
            val rejectedByCollisionOrFoul = chosenOutcome.strikerScratched ||
                anyNeighborScratched ||
                opponentOnlyFoul ||
                (!candidate.isBreakShot && hasSecondaryCollision && !anyCleanTargetPot)

            val compositeScore = (
                bestLocalScore * 0.44f +
                    candidate.staticScore * 0.18f +
                    robustness * 0.16f +
                    (if (isSureShot) 0.10f else 0f) +
                    candidate.trainingMatchScore * 0.22f -
                    (if (isCollisionAssisted) 0.03f else 0f) -
                    candidate.collisionRiskPenalty * 0.22f -
                    heatmapRisk * 0.20f
                ).coerceIn(0.02f, 1.0f)

            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val verification = RolloutVerificationResult(
                verifiedDir = rotateVector(candidate.baseDir, bestAngleDeg),
                verifiedPullMultiplier = (candidate.basePullMultiplier * bestPowerFactor).coerceIn(0.70f, 1.50f),
                robustnessScore = robustness,
                predictedMyCoinsPotted = chosenOutcome.pottedMyCoins,
                predictedQueenPotted = chosenOutcome.pottedQueen,
                predictedScratchFoul = chosenOutcome.strikerScratched,
                predictedOpponentOnlyFoul = opponentOnlyFoul,
                predictedKissBlock = !candidate.isBreakShot && hasSecondaryCollision && !anyCleanTargetPot,
                isCollisionAssistedSureShot = isCollisionAssisted,
                rejectedDueToCollisionOrFoul = rejectedByCollisionOrFoul,
                isSureShot = isSureShot,
                isTrainingMatched = candidate.trainingMatchScore >= 0.44f,
                predictedStrikerPocketIndex = chosenOutcome.strikerPocketIndex,
                physicsCompositeScore = compositeScore,
                rolloutsRun = totalRollouts,
                elapsedMs = elapsedMs
            )

            val currentBest = bestPair
            val isBetter = currentBest == null ||
                (verification.isSureShot && !currentBest.second.isSureShot) ||
                (verification.isSureShot == currentBest.second.isSureShot && (
                    verification.physicsCompositeScore > currentBest.second.physicsCompositeScore + 0.002f ||
                        (abs(verification.physicsCompositeScore - currentBest.second.physicsCompositeScore) <= 0.002f &&
                            verification.robustnessScore > currentBest.second.robustnessScore)
                    ))
            if (isBetter) {
                bestPair = candidate to verification
            }
        }

        // Stamp final total elapsed & rollout count on the winner
        return bestPair?.let { (cand, res) ->
            val totalElapsed = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            cand to res.copy(rolloutsRun = totalRollouts, elapsedMs = totalElapsed)
        }
    }

    /**
     * Ranks up to [maxSpots] distinct baseline striker X positions (>= [minSeparationPx] apart)
     * across the board using fast 2D physics rollouts. Used to guide the 6–7 slider probes.
     */
    fun rankTopBaselinePositions(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        myCoinColor: CoinColor,
        candidates: List<RolloutCandidate>,
        shotMemory: CarromMemoryData?,
        matchCalibration: MatchPhysicsCalibration?,
        maxSpots: Int = 7,
        minSeparationPx: Float = max(44f, board.strikerRadiusPx * 1.45f),
        isFreestyle: Boolean = false
    ): List<RankedBaselineSpot> {
        if (candidates.isEmpty() || board.pockets.size < 4) return emptyList()

        val frictionScale = matchCalibration?.frictionScale ?: 1.0f
        val shotPowerGain = matchCalibration?.shotPowerGain ?: 1.0f
        val jawSafetyBuffer = matchCalibration?.pocketJawSafetyBuffer ?: 0f
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true

        val safeCandidates = candidates.filterNot { cand ->
            !cand.isBreakShot && shotMemory?.isFoulBlacklisted(
                board = board,
                coinCenter = cand.targetCoin.center,
                strikerPos = cand.strikerPos,
                pocketIndex = cand.pocketIndex,
                cutAngleDeg = cand.cutAngleDeg,
                powerStyle = CarromPowerStyle.fromPullMultiplier(cand.basePullMultiplier * shotPowerGain)
            ) == true
        }
        if (safeCandidates.isEmpty()) return emptyList()

        // Keep top 2 static candidates per distinct quantized X bucket (every 18px) for fast rollout evaluation
        val bucketBest = LinkedHashMap<Int, List<RolloutCandidate>>()
        val sortedByStatic = safeCandidates.sortedByDescending { it.staticScore }
        for (cand in sortedByStatic) {
            val bucketKey = (cand.strikerPos.x / 18f).toInt()
            val list = bucketBest[bucketKey] ?: emptyList()
            if (list.size < 2) {
                bucketBest[bucketKey] = list + cand
            }
        }

        val evaluatedSpots = ArrayList<RankedBaselineSpot>(bucketBest.size)
        val probeAngles = floatArrayOf(-1.5f, 0f, 1.5f)
        val probePowers = floatArrayOf(0.95f, 1.08f)

        for ((_, candsAtX) in bucketBest) {
            var bestScoreForX = Float.NEGATIVE_INFINITY
            var bestSureForX = false
            var bestCollisionAssistedForX = false
            var bestColorForX = myCoinColor
            var bestPocketForX = 0
            var bestTrainingMatchForX = 0f

            for (cand in candsAtX) {
                var bestCandScore = Float.NEGATIVE_INFINITY
                var cleanAngleHits = 0
                var bestOutcome: SingleRolloutOutcome? = null

                for (pwr in probePowers) {
                    var angleHitsAtPwr = 0
                    for (ang in probeAngles) {
                        val dir = rotateVector(cand.baseDir, ang)
                        val speed = computeInitialSpeedPx(
                            board = board,
                            cutAngleDeg = cand.cutAngleDeg,
                            totalDistance = cand.totalDistance,
                            pullMultiplier = cand.basePullMultiplier * pwr * shotPowerGain,
                            frictionScale = frictionScale
                        )
                        val outcome = runSingleRollout(
                            board = board,
                            pieces = pieces,
                            strikerPos = cand.strikerPos,
                            shotDir = dir,
                            initialSpeed = speed,
                            targetCoin = cand.targetCoin,
                            myCoinColor = myCoinColor,
                            frictionScale = frictionScale,
                            jawSafetyBuffer = jawSafetyBuffer,
                            extraScratchGuard = extraScratchGuard,
                            isFreestyle = isFreestyle
                        )
                        val oppOnly = !isFreestyle && outcome.pottedOpponentCoins > 0 && outcome.pottedMyCoins == 0 && !outcome.pottedQueen
                        val cleanPot = (outcome.pottedMyCoins > 0 || outcome.pottedQueen) && !outcome.strikerScratched && !oppOnly
                        if (cleanPot) {
                            angleHitsAtPwr++
                        }
                        val s = scoreOutcome(cand, outcome, ang, shotMemory, isFreestyle)
                        if (s > bestCandScore) {
                            bestCandScore = s
                            bestOutcome = outcome
                        }
                    }
                    if (angleHitsAtPwr > cleanAngleHits) {
                        cleanAngleHits = angleHitsAtPwr
                    }
                }

                val out = bestOutcome ?: continue
                val oppOnly = !isFreestyle && out.pottedOpponentCoins > 0 && out.pottedMyCoins == 0 && !out.pottedQueen
                if (out.strikerScratched || oppOnly) continue

                val hasSecondaryCollision = !out.firstStrikerHitIsTarget || out.targetDeflectedByNonTarget || out.kissedObstacleCount > 0
                val isSure = (out.pottedMyCoins > 0 || out.pottedQueen) && cleanAngleHits >= 2
                val composite = (
                    bestCandScore * 0.72f +
                        cand.staticScore * 0.24f +
                        cand.trainingMatchScore * 0.14f -
                        cand.collisionRiskPenalty * 0.20f +
                        if (isSure) 0.14f else 0f
                    )
                if (composite > bestScoreForX) {
                    bestScoreForX = composite
                    bestSureForX = isSure
                    bestCollisionAssistedForX = isSure && hasSecondaryCollision
                    bestColorForX = cand.targetCoin.color
                    bestPocketForX = cand.pocketIndex
                    bestTrainingMatchForX = cand.trainingMatchScore
                }
            }

            if (bestScoreForX > Float.NEGATIVE_INFINITY) {
                evaluatedSpots.add(
                    RankedBaselineSpot(
                        strikerX = candsAtX.first().strikerPos.x,
                        predictedScore = bestScoreForX,
                        predictedSureShot = bestSureForX,
                        collisionAssisted = bestCollisionAssistedForX,
                        targetCoinColor = bestColorForX,
                        pocketIndex = bestPocketForX,
                        trainingMatchScore = bestTrainingMatchForX
                    )
                )
            }
        }

        // Sort prioritized by Sure Shot flag, Training Pattern match, then score descending
        val sorted = evaluatedSpots.sortedWith(
            compareByDescending<RankedBaselineSpot> { it.predictedSureShot }
                .thenByDescending { it.trainingMatchScore >= 0.44f }
                .thenByDescending { it.predictedScore }
        )
        val picked = ArrayList<RankedBaselineSpot>(maxSpots)
        // First lock any high-confidence Sure Shot spots (keeping minSeparationPx)
        for (spot in sorted) {
            if (!spot.predictedSureShot) break
            if (picked.none { abs(it.strikerX - spot.strikerX) < minSeparationPx }) {
                picked.add(spot)
                if (picked.size >= maxSpots) break
            }
        }
        // For remaining probe slots, deliberately spread across Left / Mid / Right sectors ("এপাশ থেকে ওপাশ")
        if (picked.size < maxSpots) {
            fun sectorOf(x: Float): Int {
                val norm = ((x - board.left) / board.width).coerceIn(0f, 1f)
                return when {
                    norm < 0.38f -> 0
                    norm > 0.62f -> 2
                    else -> 1
                }
            }
            val coveredSectors = picked.mapTo(HashSet()) { sectorOf(it.strikerX) }
            // First pass: pick highest-scoring spot in each uncovered sector (opposite sides first)
            for (spot in sorted) {
                if (picked.size >= maxSpots) break
                val sec = sectorOf(spot.strikerX)
                if (sec !in coveredSectors && picked.none { abs(it.strikerX - spot.strikerX) < minSeparationPx }) {
                    picked.add(spot)
                    coveredSectors.add(sec)
                }
            }
            // Second pass: fill any remaining slots with best remaining well-separated spots
            for (spot in sorted) {
                if (picked.size >= maxSpots) break
                if (picked.none { abs(it.strikerX - spot.strikerX) < minSeparationPx }) {
                    picked.add(spot)
                }
            }
        }
        return picked
    }

    internal fun runSingleRollout(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        strikerPos: Point2D,
        shotDir: Point2D,
        initialSpeed: Float,
        targetCoin: CarromPiece?,
        myCoinColor: CoinColor,
        frictionScale: Float = 1.0f,
        jawSafetyBuffer: Float = 0f,
        extraScratchGuard: Boolean = false,
        isFreestyle: Boolean = false
    ): SingleRolloutOutcome {
        val bodies = ArrayList<SimBody>(pieces.size + 1)
        val strikerBody = SimBody(
            x = strikerPos.x,
            y = strikerPos.y,
            vx = shotDir.x * initialSpeed,
            vy = shotDir.y * initialSpeed,
            radius = board.strikerRadiusPx,
            mass = STRIKER_MASS,
            color = CoinColor.STRIKER,
            isTargetCoin = false
        )
        bodies.add(strikerBody)

        var targetBody: SimBody? = null
        for (p in pieces) {
            if (p.color == CoinColor.STRIKER) continue
            // Skip duplicate overlaps right on top of striker start
            if (hypot(p.center.x - strikerPos.x, p.center.y - strikerPos.y) < board.coinRadiusPx * 0.4f) {
                continue
            }
            val isTarget = targetCoin != null &&
                abs(p.center.x - targetCoin.center.x) < 2f &&
                abs(p.center.y - targetCoin.center.y) < 2f
            val body = SimBody(
                x = p.center.x,
                y = p.center.y,
                vx = 0f,
                vy = 0f,
                radius = board.coinRadiusPx,
                mass = COIN_MASS,
                color = p.color,
                isTargetCoin = isTarget
            )
            if (isTarget) targetBody = body
            bodies.add(body)
        }

        val coinCaptureRadius = (board.pocketRadiusPx - jawSafetyBuffer).coerceAtLeast(board.coinRadiusPx * 1.12f)
        val strikerCaptureRadius = board.pocketRadiusPx * (if (extraScratchGuard) 1.26f else 1.15f)

        // Friction deceleration in px/step calibrated so medium shots traverse ~1.5-1.8 * board.width
        val frictionDecelPerStep = board.width * 0.0042f * frictionScale.coerceIn(0.75f, 1.35f)
        val dtSub = 1.0f / SUBSTEPS_PER_STEP
        var minTargetPocketDist = Float.MAX_VALUE
        var bestJawClearance = 0f
        var strikerHasCollided = false
        var firstStrikerHitIsTarget = true
        var targetDeflectedByNonTarget = false
        var kissedObstacleCount = 0

        for (step in 0 until MAX_SIM_STEPS) {
            var anyMoving = false

            for (sub in 0 until SUBSTEPS_PER_STEP) {
                // 1. Advance positions & check pocket captures
                for (b in bodies) {
                    if (b.pocketed) continue
                    val speed = hypot(b.vx, b.vy)
                    if (speed < 0.08f) {
                        b.vx = 0f
                        b.vy = 0f
                        continue
                    }
                    anyMoving = true
                    b.x += b.vx * dtSub
                    b.y += b.vy * dtSub

                    // Check pockets
                    for (pIdx in board.pockets.indices) {
                        val pocket = board.pockets[pIdx]
                        val distToPocket = hypot(b.x - pocket.x, b.y - pocket.y)
                        if (b === targetBody && distToPocket < minTargetPocketDist) {
                            minTargetPocketDist = distToPocket
                        }
                        val captureR = if (b.color == CoinColor.STRIKER) strikerCaptureRadius else coinCaptureRadius
                        if (distToPocket <= captureR) {
                            b.pocketed = true
                            b.pocketedInIndex = pIdx
                            b.vx = 0f
                            b.vy = 0f
                            if (b === targetBody) {
                                bestJawClearance = ((captureR - distToPocket) / captureR).coerceIn(0f, 1f)
                            }
                            break
                        }
                    }
                    if (b.pocketed) continue

                    // 2. Rail / cushion bounce (only outside corner pocket openings)
                    val minX = board.left + b.radius
                    val maxX = board.right - b.radius
                    val minY = board.top + b.radius
                    val maxY = board.bottom - b.radius

                    if (b.x < minX) {
                        b.x = minX
                        b.vx = abs(b.vx) * RAIL_RESTITUTION
                        b.vy *= 0.96f
                    } else if (b.x > maxX) {
                        b.x = maxX
                        b.vx = -abs(b.vx) * RAIL_RESTITUTION
                        b.vy *= 0.96f
                    }

                    if (b.y < minY) {
                        b.y = minY
                        b.vy = abs(b.vy) * RAIL_RESTITUTION
                        b.vx *= 0.96f
                    } else if (b.y > maxY) {
                        b.y = maxY
                        b.vy = -abs(b.vy) * RAIL_RESTITUTION
                        b.vx *= 0.96f
                    }
                }

                // 3. Pairwise rigid-body elastic disc-disc impulse collisions
                val count = bodies.size
                for (i in 0 until count) {
                    val a = bodies[i]
                    if (a.pocketed) continue
                    for (j in i + 1 until count) {
                        val b = bodies[j]
                        if (b.pocketed) continue

                        val dx = b.x - a.x
                        val dy = b.y - a.y
                        val minDist = a.radius + b.radius
                        val distSq = dx * dx + dy * dy
                        if (distSq >= minDist * minDist || distSq < 1e-6f) continue

                        val dist = kotlin.math.sqrt(distSq)
                        val nx = dx / dist
                        val ny = dy / dist

                        // Positional separation to prevent sinking
                        val overlap = minDist - dist
                        val invMassA = 1f / a.mass
                        val invMassB = 1f / b.mass
                        val invMassSum = invMassA + invMassB
                        a.x -= nx * overlap * (invMassA / invMassSum)
                        a.y -= ny * overlap * (invMassA / invMassSum)
                        b.x += nx * overlap * (invMassB / invMassSum)
                        b.y += ny * overlap * (invMassB / invMassSum)

                        // Relative velocity along contact normal
                        val rvx = b.vx - a.vx
                        val rvy = b.vy - a.vy
                        val velAlongNormal = rvx * nx + rvy * ny
                        if (velAlongNormal > 0f) continue

                        val impulseMag = -(1f + DISC_RESTITUTION) * velAlongNormal / invMassSum
                        val ix = impulseMag * nx
                        val iy = impulseMag * ny
                        a.vx -= ix * invMassA
                        a.vy -= iy * invMassA
                        b.vx += ix * invMassB
                        b.vy += iy * invMassB

                        // Track kiss / unintended obstacle collisions
                        val involvesStriker = (a === strikerBody || b === strikerBody)
                        val otherFromStriker = if (a === strikerBody) b else a
                        if (involvesStriker) {
                            if (!strikerHasCollided) {
                                strikerHasCollided = true
                                if (targetBody != null && otherFromStriker !== targetBody) {
                                    firstStrikerHitIsTarget = false
                                    kissedObstacleCount++
                                }
                            } else if (targetBody != null && otherFromStriker !== targetBody) {
                                kissedObstacleCount++
                            }
                        } else if (targetBody != null && (a === targetBody || b === targetBody)) {
                            targetDeflectedByNonTarget = true
                            kissedObstacleCount++
                        }
                    }
                }
            }

            if (!anyMoving) break

            // 4. Sliding table friction once per full step
            for (b in bodies) {
                if (b.pocketed) continue
                val speed = hypot(b.vx, b.vy)
                if (speed <= frictionDecelPerStep) {
                    b.vx = 0f
                    b.vy = 0f
                } else {
                    val newSpeed = speed - frictionDecelPerStep
                    val ratio = newSpeed / speed
                    b.vx *= ratio
                    b.vy *= ratio
                }
            }
        }

        val pottedMy = if (isFreestyle) {
            bodies.count { it.pocketed && (it.color == CoinColor.WHITE || it.color == CoinColor.BLACK) }
        } else {
            bodies.count { it.pocketed && it.color == myCoinColor }
        }
        val pottedQueen = bodies.any { it.pocketed && it.color == CoinColor.QUEEN }
        val oppColor = if (myCoinColor == CoinColor.WHITE) CoinColor.BLACK else CoinColor.WHITE
        val pottedOpp = if (isFreestyle) {
            0
        } else {
            bodies.count { it.pocketed && it.color == oppColor }
        }

        val pottedWhite = bodies.count { it.pocketed && it.color == CoinColor.WHITE }
        val pottedBlack = bodies.count { it.pocketed && it.color == CoinColor.BLACK }

        return SingleRolloutOutcome(
            pottedMyCoins = pottedMy,
            pottedQueen = pottedQueen,
            pottedOpponentCoins = pottedOpp,
            strikerScratched = strikerBody.pocketed,
            strikerPocketIndex = if (strikerBody.pocketed) strikerBody.pocketedInIndex else null,
            minTargetToPocketDist = minTargetPocketDist,
            jawClearenceScore = bestJawClearance,
            firstStrikerHitIsTarget = firstStrikerHitIsTarget,
            targetDeflectedByNonTarget = targetDeflectedByNonTarget,
            kissedObstacleCount = kissedObstacleCount,
            pottedWhiteCoins = pottedWhite,
            pottedBlackCoins = pottedBlack
        )
    }

    private fun scoreOutcome(
        candidate: RolloutCandidate,
        outcome: SingleRolloutOutcome,
        angleOffsetDeg: Float,
        shotMemory: CarromMemoryData?,
        isFreestyle: Boolean = false
    ): Float {
        if (outcome.strikerScratched) {
            return -0.98f
        }
        val oppOnlyFoul = outcome.pottedOpponentCoins > 0 && outcome.pottedMyCoins == 0 && !outcome.pottedQueen
        if (oppOnlyFoul) {
            return -0.68f
        }

        var score = 0.25f
        if (outcome.pottedQueen) {
            score += if (isFreestyle) 0.62f else 0.50f
        }
        if (outcome.pottedMyCoins > 0) {
            val freestyleColorBonus = if (isFreestyle) {
                outcome.pottedWhiteCoins * 0.10f
            } else {
                0f
            }
            score += 0.42f + (outcome.pottedMyCoins - 1) * 0.14f + freestyleColorBonus
            score += outcome.jawClearenceScore * 0.12f
        } else if (candidate.isBreakShot) {
            // Break shot doesn't guarantee a pot, so reward avoiding fouls and disturbing target
            score += 0.24f
        } else {
            // Missed pot (often due to kiss/deflection): strong penalty so clean pots always win
            val prox = (1f - (outcome.minTargetToPocketDist / 300f).coerceIn(0f, 1f)) * 0.12f
            score += prox - if (outcome.targetDeflectedByNonTarget) 0.38f else 0.16f
        }

        val didPocketTarget = outcome.pottedMyCoins > 0 || outcome.pottedQueen
        if (!candidate.isBreakShot && !outcome.firstStrikerHitIsTarget) {
            if (!didPocketTarget) {
                // Collision caused a miss -> strong rejection penalty
                score -= 0.40f
            } else {
                // Collision still sank our coin/Queen cleanly -> mild preference for clean direct hits
                score -= 0.04f
            }
        } else if (!candidate.isBreakShot && outcome.targetDeflectedByNonTarget && !didPocketTarget) {
            score -= 0.28f
        }

        if (outcome.pottedOpponentCoins > 0 && outcome.pottedMyCoins > 0) {
            score -= 0.08f
        } else if (didPocketTarget &&
            outcome.firstStrikerHitIsTarget &&
            !outcome.targetDeflectedByNonTarget &&
            outcome.pottedOpponentCoins == 0
        ) {
            // Clean zero-kiss zero-foul pot bonus
            score += 0.16f
        }

        // Slight preference for smaller deviation from analytical geometry
        score -= abs(angleOffsetDeg) * 0.012f
        // Incorporate pocket memory bias
        score += (shotMemory?.confidenceBiasFor(candidate.pocketIndex, candidate.cutAngleDeg) ?: 0f) * 0.4f
        return score
    }

    private fun computeInitialSpeedPx(
        board: CarromBoardLayout,
        cutAngleDeg: Float,
        totalDistance: Float,
        pullMultiplier: Float,
        frictionScale: Float
    ): Float {
        // Enough initial impulse to carry striker -> target -> pocket across board friction
        val cutRad = (cutAngleDeg.coerceIn(0f, 68f) * PI / 180.0)
        val cutComp = (1.0 / cos(cutRad)).toFloat().coerceIn(1.0f, 1.75f)
        val distFactor = (totalDistance / board.width).coerceIn(0.45f, 1.65f)
        val baseSpeed = board.width * 0.115f * kotlin.math.sqrt(distFactor)
        return (baseSpeed * cutComp * pullMultiplier * kotlin.math.sqrt(frictionScale)).coerceIn(
            board.width * 0.065f,
            board.width * 0.28f
        )
    }

    internal fun isBreakTrajectoryScratchFree(
        board: CarromBoardLayout,
        pieces: List<CarromPiece>,
        strikerPos: Point2D,
        shotDir: Point2D,
        pullMultiplier: Float,
        myCoinColor: CoinColor,
        shotMemory: CarromMemoryData?,
        isFreestyle: Boolean
    ): Boolean {
        val frictionScale = shotMemory?.matchCalibration?.frictionScale ?: 1.0f
        val shotPowerGain = shotMemory?.matchCalibration?.shotPowerGain ?: 1.0f
        val jawSafetyBuffer = shotMemory?.matchCalibration?.pocketJawSafetyBuffer ?: 0f
        val extraScratchGuard = shotMemory?.needsExtraScratchGuard() == true
        val jitterAngles = floatArrayOf(-1.5f, 0f, 1.5f)
        for (ang in jitterAngles) {
            val dir = rotateVector(shotDir, ang)
            val speed = computeInitialSpeedPx(
                board = board,
                cutAngleDeg = 0f,
                totalDistance = board.width * 1.35f,
                pullMultiplier = pullMultiplier * shotPowerGain,
                frictionScale = frictionScale
            )
            val outcome = runSingleRollout(
                board = board,
                pieces = pieces,
                strikerPos = strikerPos,
                shotDir = dir,
                initialSpeed = speed,
                targetCoin = null,
                myCoinColor = myCoinColor,
                frictionScale = frictionScale,
                jawSafetyBuffer = jawSafetyBuffer,
                extraScratchGuard = extraScratchGuard,
                isFreestyle = isFreestyle
            )
            if (outcome.strikerScratched) return false
        }
        return true
    }

    private fun rotateVector(v: Point2D, angleDeg: Float): Point2D {
        if (abs(angleDeg) < 1e-4f) return v.normalized()
        val rad = angleDeg * (PI / 180.0)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return Point2D(v.x * c - v.y * s, v.x * s + v.y * c).normalized()
    }
}
