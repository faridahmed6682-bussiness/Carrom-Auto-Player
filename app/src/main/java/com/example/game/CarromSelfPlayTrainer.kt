package com.example.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Self-play trainer: how the bot learns sure shots WITHOUT playing a real
 * match.
 *
 * The trainer builds a virtual 1000x1000 board from a saved board profile
 * (pockets, baseline, radii — all normalised, so the same table transfers
 * to any screen size), sweeps a grid of synthetic shots through the SAME
 * 2D physics rollout the live solver uses, and records every outcome into
 * the learned table (CarromShotMemoryStore):
 *
 * - Direct shots: pocket x cut-angle x distance-band grid.
 * - Bank shots (1-rail rebound): pocket x rail x coin-position grid. The
 *   rollout simulates the rail bounce (angle in ~= angle out, 0.80
 *   restitution, friction loss) and checks whether the post-rebound path
 *   reaches a pocket.
 * - Combo shots (coin-coin collision): the rollout simulates the struck
 *   coin moving along the line of centres and the first coin deflecting,
 *   and checks whether EITHER coin then reaches a pocket.
 *
 * Every simulated outcome is recorded through evaluateShotOutcome, so the
 * same bucket stats (attempts / successes / fouls / misses, learned power
 * scale) and the same CleanShotPattern library fill up as in live play —
 * one simulated attempt per grid cell, so the table is a PRIOR, not a
 * flood: live shots keep accumulating on top of it. Simulated outcomes
 * NEVER touch the live match calibration, foul signatures, scratch
 * heatmap or placement stats (those are restored after each simulated
 * record) — only live play tunes those. Simulated misses/fouls update
 * bucket confidence only; they never decay patterns (sim friction is not
 * live friction — only a real-match failure demotes a pattern).
 *
 * In a live match the solver consults this table FIRST (Tier-1
 * training-pattern match in solveBestShot) and only falls back to full
 * computation when the table has no confident entry — that is the fast
 * path that makes a sure shot possible inside the 5-second budget.
 */
data class SelfPlayConfig(
    val includeBankShots: Boolean = true,
    val includeComboShots: Boolean = true,
    val isFreestyle: Boolean = false,
    /** "" = use the memory's active board profile. */
    val profileName: String = "",
    val maxShots: Int = 240
)

data class SelfPlayReport(
    val shotsRun: Int,
    val sureShotsFound: Int,
    val bankShotsRun: Int,
    val bankSureShotsFound: Int,
    val comboShotsRun: Int,
    val comboSureShotsFound: Int,
    val foulsPredicted: Int,
    val patternsAdded: Int,
    val bucketsTouched: Int,
    val profileName: String,
    val elapsedMs: Long,
    val updatedMemory: CarromMemoryData
)

object CarromSelfPlayTrainer {

    /** Virtual board size (px). Geometry is normalised, so any size works. */
    private const val VIRTUAL_BOARD_PX = 1000f

    private val DIRECT_CUTS_DEG = floatArrayOf(7f, 25f, 50f)
    private val DIRECT_DISTANCES = floatArrayOf(0.30f, 0.55f, 0.85f)
    private val DIRECT_APPROACH_OFFSETS_DEG = floatArrayOf(-25f, 0f, 25f)

    private val BANK_CUTS_DEG = floatArrayOf(10f, 25f, 40f)
    private val BANK_COIN_SPOTS_NORM = listOf(
        Point2D(0.30f, 0.30f),
        Point2D(0.70f, 0.30f),
        Point2D(0.50f, 0.50f),
        Point2D(0.30f, 0.65f),
        Point2D(0.70f, 0.65f)
    )

    private val COMBO_HELPER_CUTS_DEG = floatArrayOf(15f, 30f)
    private val COMBO_DISTANCES = floatArrayOf(0.35f, 0.60f)
    private val COMBO_STRIKER_CUTS_DEG = floatArrayOf(7f, 25f)

    fun runSweep(memory: CarromMemoryData, config: SelfPlayConfig): SelfPlayReport {
        val startMs = System.currentTimeMillis()
        val profileName = config.profileName.ifBlank { memory.activeBoardProfileName }
        val profile = memory.boardProfiles.firstOrNull { it.profileName == profileName }
            ?: memory.boardCalibration
        val board = buildTrainingBoard(profile)
        val myColor = CoinColor.WHITE

        // The sweep writes into the training profile's own table, at neutral
        // live calibration (simulated friction is 1.0 — the profile table
        // is the prior; the live match re-tunes calibration on top of it).
        var sweepMemory = memory.copy(
            activeBoardProfileName = profileName,
            matchCalibration = MatchPhysicsCalibration()
        )
        val bucketsTouched = LinkedHashSet<String>()
        var shotsRun = 0
        var sureShotsFound = 0
        var bankShotsRun = 0
        var bankSure = 0
        var comboShotsRun = 0
        var comboSure = 0
        var foulsPredicted = 0
        val patternsBefore = sweepMemory.cleanShotPatterns.size

        fun recordCell(
            candidate: RolloutCandidate,
            pieces: List<CarromPiece>,
            targetCoin: CarromPiece,
            strikerPos: Point2D,
            totalDistance: Float,
            isBank: Boolean,
            isCombo: Boolean
        ): Boolean {
            if (shotsRun >= config.maxShots) return false
            val verified = CarromPhysicsSimulator.verifyAndRefineCandidates(
                board = board,
                pieces = pieces,
                myCoinColor = myColor,
                candidates = listOf(candidate),
                shotMemory = null,
                matchCalibration = null,
                isFreestyle = config.isFreestyle
            ) ?: return false
            val (winner, sim) = verified
            val preCounts = BoardPieceCounts(
                myCount = pieces.count { it.color == myColor },
                opponentCount = pieces.count { it.color == CoinColor.BLACK },
                queenCount = pieces.count { it.color == CoinColor.QUEEN }
            )
            val postCounts = when {
                sim.predictedScratchFoul -> preCounts.copy(myCount = preCounts.myCount + 1)
                sim.predictedOpponentOnlyFoul -> preCounts.copy(opponentCount = preCounts.opponentCount - 1)
                sim.isSureShot && (sim.predictedMyCoinsPotted > 0 || sim.predictedQueenPotted) ->
                    preCounts.copy(myCount = preCounts.myCount - 1)
                else -> preCounts
            }
            val distRatio = (totalDistance / board.width).coerceIn(0.15f, 1.8f)
            val pending = PendingCarromShotRecord(
                myCoinColor = myColor,
                targetCoinColor = targetCoin.color,
                pocketIndex = winner.pocketIndex,
                cutAngleDeg = winner.cutAngleDeg,
                strikerNormalizedX = ((strikerPos.x - board.left) / board.width).coerceIn(0f, 1f),
                coinRowZone = ((targetCoin.center.y - board.top) / board.height * 4f).toInt().coerceIn(0, 3),
                coinColZone = ((targetCoin.center.x - board.left) / board.width * 4f).toInt().coerceIn(0, 3),
                powerStyle = CarromPowerStyle.fromPullMultiplier(sim.verifiedPullMultiplier),
                movedStrikerWithSlider = false,
                isBankShot = isBank,
                isComboShot = isCombo,
                isBreakShot = false,
                preCounts = preCounts,
                isFreestyle = config.isFreestyle,
                coinNormX = ((targetCoin.center.x - board.left) / board.width).coerceIn(0f, 1f),
                coinNormY = ((targetCoin.center.y - board.top) / board.height).coerceIn(0f, 1f),
                distRatio = distRatio,
                observedPullScale = sim.verifiedPullMultiplier,
                minCorridorClearanceRadii = 3.5f,
                corridorObstacles = emptyList()
            )
            val before = sweepMemory
            val evaluated = CarromShotMemoryStore.evaluateShotOutcome(
                current = sweepMemory,
                pendingShot = pending,
                postCounts = postCounts,
                lessonPrefix = "[Self-Play] ",
                // Simulated fouls must NOT create hard foul blocks and
                // simulated shots must NOT touch placement stats.
                recordFoulSignatures = false,
                recordPlacementStats = false
            )
            // Restore everything simulation must not tune: live match
            // calibration, scratch heatmap, placement stats, foul
            // signatures and straight-foul count stay exactly as they were.
            var updated = evaluated.updatedMemory.copy(
                matchCalibration = before.matchCalibration,
                scratchHeatmap = before.scratchHeatmap,
                placementStats = before.placementStats,
                foulSignatures = before.foulSignatures,
                straightFoulCount = before.straightFoulCount
            )
            bucketsTouched.add(
                CarromMemoryData.bucketKey(
                    winner.pocketIndex,
                    CarromAngleBucket.fromAngle(winner.cutAngleDeg),
                    distRatio
                )
            )
            // Clean simulated pots join the pattern library (with their
            // kind + board attached). Simulated misses/fouls never decay
            // patterns — only a real-match failure does that.
            val isCleanPot = evaluated.outcomeType == CarromShotOutcomeType.POCKETED_MY_COIN ||
                evaluated.outcomeType == CarromShotOutcomeType.POCKETED_QUEEN
            if (isCleanPot) {
                updated = CarromShotMemoryStore.updateTrainingPatternsFromShot(
                    memory = updated,
                    pendingShot = pending,
                    outcomeType = evaluated.outcomeType,
                    observedPowerHint = sim.verifiedPullMultiplier
                )
            }
            sweepMemory = updated
            shotsRun++
            if (sim.isSureShot) sureShotsFound++
            if (sim.predictedScratchFoul || sim.predictedOpponentOnlyFoul) foulsPredicted++
            if (isBank) {
                bankShotsRun++
                if (sim.isSureShot) bankSure++
            }
            if (isCombo) {
                comboShotsRun++
                if (sim.isSureShot) comboSure++
            }
            return true
        }

        // ---- Direct grid: pocket x cut x distance x approach direction ----
        outer@ for (pocketIdx in 0 until 4) {
            val pocket = board.pockets[pocketIdx]
            val toCentre = (Point2D(board.left + board.width / 2f, board.top + board.height / 2f) - pocket).normalized()
            for (approachOffset in DIRECT_APPROACH_OFFSETS_DEG) {
                val away = rotateVector(toCentre, approachOffset)
                for (dist in DIRECT_DISTANCES) {
                    val coinCenter = pocket + away * (dist * board.width)
                    if (!isInside(coinCenter, board, board.coinRadiusPx)) continue
                    val dirCp = (pocket - coinCenter).normalized()
                    val ghost = coinCenter - dirCp * (board.coinRadiusPx + board.strikerRadiusPx)
                    if (!isInside(ghost, board, board.strikerRadiusPx * 0.55f)) continue
                    val coin = CarromPiece(CoinColor.WHITE, coinCenter, board.coinRadiusPx)
                    for (cutDeg in DIRECT_CUTS_DEG) {
                        if (shotsRun >= config.maxShots) break@outer
                        val strikerPos = strikerOnBaselineForCut(
                            board = board, ghost = ghost, pocketDir = dirCp, cutDeg = cutDeg
                        ) ?: continue
                        val totalDistance = (ghost - strikerPos).length() + (pocket - coinCenter).length()
                        val distRatio = (totalDistance / board.width).coerceIn(0.15f, 1.8f)
                        val learnedScale = sweepMemory.learnedPowerScaleFor(pocketIdx, cutDeg, distRatio)
                        val baseMult = (if ((pocket - coinCenter).length() <= board.coinRadiusPx * 5.2f) 0.85f else 1.0f) * learnedScale
                        val candidate = RolloutCandidate(
                            strikerPos = strikerPos,
                            baseDir = (ghost - strikerPos).normalized(),
                            ghostBall = ghost,
                            targetCoin = coin,
                            targetPocket = pocket,
                            pocketIndex = pocketIdx,
                            cutAngleDeg = cutDeg,
                            isBankShot = false,
                            isComboShot = false,
                            isBreakShot = false,
                            basePullMultiplier = baseMult,
                            staticScore = 0.7f,
                            totalDistance = totalDistance,
                            trainingMatchScore = 0f
                        )
                        recordCell(candidate, listOf(coin), coin, strikerPos, totalDistance, false, false)
                    }
                }
            }
        }

        // ---- Bank grid: pocket x rail x coin spot x cut ----
        // Mirror the pocket across the rail (angle in ~= angle out); the
        // rollout itself simulates the bounce with 0.80 restitution and
        // checks whether the post-rebound path reaches the pocket.
        if (config.includeBankShots) {
            val r = board.coinRadiusPx
            val rails = listOf(
                "top" to { p: Point2D -> Point2D(p.x, 2f * (board.top + r) - p.y) },
                "left" to { p: Point2D -> Point2D(2f * (board.left + r) - p.x, p.y) },
                "right" to { p: Point2D -> Point2D(2f * (board.right - r) - p.x, p.y) },
                "bottom" to { p: Point2D -> Point2D(p.x, 2f * (board.bottom - r) - p.y) }
            )
            bankOuter@ for (pocketIdx in 0 until 4) {
                val pocket = board.pockets[pocketIdx]
                for ((railName, mirrorFn) in rails) {
                    val mirroredPocket = mirrorFn(pocket)
                    for (spot in BANK_COIN_SPOTS_NORM) {
                        val coinCenter = Point2D(
                            board.left + spot.x * board.width,
                            board.top + spot.y * board.height
                        )
                        if (!isInside(coinCenter, board, board.coinRadiusPx)) continue
                        val toMirror = mirroredPocket - coinCenter
                        if (toMirror.length() < r * 2f) continue
                        val dirBank = toMirror.normalized()
                        val ghost = coinCenter - dirBank * (board.coinRadiusPx + board.strikerRadiusPx)
                        if (!isInside(ghost, board, board.strikerRadiusPx * 0.55f)) continue
                        if (!bankBounceOnRail(board, coinCenter, mirroredPocket, railName)) continue
                        val coin = CarromPiece(CoinColor.WHITE, coinCenter, board.coinRadiusPx)
                        for (cutDeg in BANK_CUTS_DEG) {
                            if (shotsRun >= config.maxShots) break@bankOuter
                            val strikerPos = strikerOnBaselineForCut(
                                board = board, ghost = ghost, pocketDir = dirBank, cutDeg = cutDeg
                            ) ?: continue
                            val totalDistance = (ghost - strikerPos).length() + toMirror.length()
                            val distRatio = (totalDistance / board.width).coerceIn(0.15f, 1.8f)
                            val learnedScale = sweepMemory.learnedPowerScaleFor(pocketIdx, cutDeg, distRatio)
                            val candidate = RolloutCandidate(
                                strikerPos = strikerPos,
                                baseDir = (ghost - strikerPos).normalized(),
                                ghostBall = ghost,
                                targetCoin = coin,
                                targetPocket = pocket,
                                pocketIndex = pocketIdx,
                                cutAngleDeg = cutDeg,
                                isBankShot = true,
                                isComboShot = false,
                                isBreakShot = false,
                                // Bank power compensates rail-restitution damping (same 1.24x as the live solver).
                                basePullMultiplier = 1.24f * learnedScale,
                                staticScore = 0.6f,
                                totalDistance = totalDistance,
                                trainingMatchScore = 0f
                            )
                            recordCell(candidate, listOf(coin), coin, strikerPos, totalDistance, true, false)
                        }
                    }
                }
            }
        }

        // ---- Combo grid: pocket x final-coin distance x helper cut ----
        // Striker -> helper coin -> final coin -> pocket. The rollout
        // simulates the coin-coin collision (helper along the line of
        // centres, final coin deflected) and checks whether EITHER coin
        // reaches a pocket.
        if (config.includeComboShots) {
            comboOuter@ for (pocketIdx in 0 until 4) {
                val pocket = board.pockets[pocketIdx]
                val toCentre = (Point2D(board.left + board.width / 2f, board.top + board.height / 2f) - pocket).normalized()
                for (dist in COMBO_DISTANCES) {
                    val finalCenter = pocket + toCentre * (dist * board.width)
                    if (!isInside(finalCenter, board, board.coinRadiusPx)) continue
                    val dirFp = (pocket - finalCenter).normalized()
                    val helperGhost = finalCenter - dirFp * (board.coinRadiusPx * 2.0f)
                    if (!isInside(helperGhost, board, board.coinRadiusPx)) continue
                    val finalCoin = CarromPiece(CoinColor.WHITE, finalCenter, board.coinRadiusPx)
                    for (helperCut in COMBO_HELPER_CUTS_DEG) {
                        for (sign in floatArrayOf(1f, -1f)) {
                            val dirHg = rotateVector(dirFp, helperCut * sign)
                            val helperDist = 0.35f * board.width
                            val helperCenter = helperGhost - dirHg * helperDist
                            if (!isInside(helperCenter, board, board.coinRadiusPx)) continue
                            val helperCoin = CarromPiece(CoinColor.WHITE, helperCenter, board.coinRadiusPx)
                            val strikerGhost = helperCenter - dirHg * (board.coinRadiusPx + board.strikerRadiusPx)
                            if (!isInside(strikerGhost, board, board.strikerRadiusPx * 0.55f)) continue
                            for (strikerCut in COMBO_STRIKER_CUTS_DEG) {
                                if (shotsRun >= config.maxShots) break@comboOuter
                                val strikerPos = strikerOnBaselineForCut(
                                    board = board, ghost = strikerGhost, pocketDir = dirHg, cutDeg = strikerCut
                                ) ?: continue
                                val totalDistance = (strikerGhost - strikerPos).length() +
                                    (helperGhost - helperCenter).length() +
                                    (pocket - finalCenter).length()
                                val distRatio = (totalDistance / board.width).coerceIn(0.15f, 1.8f)
                                val learnedScale = sweepMemory.learnedPowerScaleFor(pocketIdx, strikerCut, distRatio)
                                val candidate = RolloutCandidate(
                                    strikerPos = strikerPos,
                                    baseDir = (strikerGhost - strikerPos).normalized(),
                                    ghostBall = strikerGhost,
                                    targetCoin = helperCoin,
                                    targetPocket = pocket,
                                    pocketIndex = pocketIdx,
                                    cutAngleDeg = strikerCut,
                                    isBankShot = false,
                                    isComboShot = true,
                                    isBreakShot = false,
                                    // Combo power compensates the energy lost in the coin-coin kiss (same 1.26x as the live solver).
                                    basePullMultiplier = 1.26f * learnedScale,
                                    staticScore = 0.55f,
                                    totalDistance = totalDistance,
                                    trainingMatchScore = 0f
                                )
                                recordCell(
                                    candidate, listOf(finalCoin, helperCoin), helperCoin,
                                    strikerPos, totalDistance, false, true
                                )
                            }
                        }
                    }
                }
            }
        }

        val finalMemory = sweepMemory.copy(
            selfPlayShotsSimulated = memory.selfPlayShotsSimulated + shotsRun,
            selfPlaySureShotsFound = memory.selfPlaySureShotsFound + sureShotsFound,
            lastUpdatedMs = System.currentTimeMillis()
        )
        return SelfPlayReport(
            shotsRun = shotsRun,
            sureShotsFound = sureShotsFound,
            bankShotsRun = bankShotsRun,
            bankSureShotsFound = bankSure,
            comboShotsRun = comboShotsRun,
            comboSureShotsFound = comboSure,
            foulsPredicted = foulsPredicted,
            patternsAdded = finalMemory.cleanShotPatterns.size - patternsBefore,
            bucketsTouched = bucketsTouched.size,
            profileName = profileName,
            elapsedMs = System.currentTimeMillis() - startMs,
            updatedMemory = finalMemory
        )
    }

    /**
     * Virtual board from a saved profile's normalised geometry. All
     * coordinates are board-relative (0-1), so the learned table transfers
     * across screen sizes and board sizes.
     */
    fun buildTrainingBoard(profile: BoardCalibrationProfile): CarromBoardLayout {
        val w = VIRTUAL_BOARD_PX
        val h = VIRTUAL_BOARD_PX
        val left = profile.boardLeftNorm * w
        val top = profile.boardTopNorm * h
        val right = profile.boardRightNorm * w
        val bottom = profile.boardBottomNorm * h
        val pockets = if (profile.pocketsNorm.size == 4) {
            profile.pocketsNorm.map { Point2D(it.x * w, it.y * h) }
        } else {
            val insetX = (right - left) * 0.064f
            val insetY = (bottom - top) * 0.064f
            listOf(
                Point2D(left + insetX, top + insetY),
                Point2D(right - insetX, top + insetY),
                Point2D(left + insetX, bottom - insetY),
                Point2D(right - insetX, bottom - insetY)
            )
        }
        return CarromBoardLayout(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            pockets = pockets,
            baselineY = profile.baselineYNorm * h,
            coinRadiusPx = w * profile.coinRadiusRatio,
            strikerRadiusPx = w * profile.strikerRadiusRatio,
            pocketRadiusPx = w * 0.052f
        )
    }

    /**
     * Striker position on the baseline that gives the target cut angle
     * against [pocketDir] (the coin -> pocket direction). Tries both
     * rotations (+cut / -cut); the striker must sit below the ghost ball
     * on the baseline and inside the board. Returns null when no baseline
     * position can produce that cut (e.g. the ghost is too close to the
     * rail) — the cell is then skipped, not forced.
     */
    fun strikerOnBaselineForCut(
        board: CarromBoardLayout,
        ghost: Point2D,
        pocketDir: Point2D,
        cutDeg: Float
    ): Point2D? {
        val margin = board.strikerRadiusPx * 2.1f
        val minX = board.left + margin
        val maxX = board.right - margin
        for (sign in floatArrayOf(1f, -1f)) {
            val d = rotateVector(pocketDir, cutDeg * sign)
            // Striker sits below the ghost: the ghost->striker direction
            // must point DOWN the screen (d points ghost->striker inverted:
            // S = G - t*d, so d must point UP, i.e. d.y < 0).
            if (d.y >= -1e-4f) continue
            val t = (ghost.y - board.baselineY) / d.y
            if (t <= 0f) continue
            val striker = Point2D(ghost.x - t * d.x, board.baselineY)
            if (striker.x in minX..maxX) return striker
        }
        return null
    }

    /** Does segment coin -> mirroredPocket cross the named rail inside its span? */
    private fun bankBounceOnRail(
        board: CarromBoardLayout,
        coin: Point2D,
        mirroredPocket: Point2D,
        railName: String
    ): Boolean {
        val r = board.coinRadiusPx
        val dx = mirroredPocket.x - coin.x
        val dy = mirroredPocket.y - coin.y
        return when (railName) {
            "top", "bottom" -> {
                if (abs(dy) < 1e-4f) return false
                val railY = if (railName == "top") board.top + r else board.bottom - r
                val t = (railY - coin.y) / dy
                if (t !in 0f..1f) return false
                val bounceX = coin.x + t * dx
                bounceX in (board.left + r * 2f)..(board.right - r * 2f)
            }
            "left", "right" -> {
                if (abs(dx) < 1e-4f) return false
                val railX = if (railName == "left") board.left + r else board.right - r
                val t = (railX - coin.x) / dx
                if (t !in 0f..1f) return false
                val bounceY = coin.y + t * dy
                bounceY in (board.top + r * 2f)..(board.bottom - r * 2f)
            }
            else -> false
        }
    }

    private fun isInside(p: Point2D, board: CarromBoardLayout, margin: Float): Boolean {
        return p.x in (board.left + margin)..(board.right - margin) &&
            p.y in (board.top + margin)..(board.bottom - margin)
    }

    private fun rotateVector(v: Point2D, angleDeg: Float): Point2D {
        val rad = angleDeg * PI.toFloat() / 180f
        val c = cos(rad)
        val s = sin(rad)
        return Point2D(v.x * c - v.y * s, v.x * s + v.y * c)
    }
}
