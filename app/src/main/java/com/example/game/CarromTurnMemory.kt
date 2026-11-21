package com.example.game

import com.example.agent.CarromDifficulty
import kotlin.math.abs
import kotlin.math.hypot

enum class TurnProbeStage(val badge: String) {
    PRE_SCAN("PRE-SCAN"),
    PROBING_SLIDER("PROBING"),
    LOCKED_SURE_SHOT("SURE SHOT"),
    CAROM_SURE_SHOT("CAROM SURE"),
    FALLBACK_FIRE("5s MUST SHOOT"),
    WATCH_TRAINING("WATCH TRAIN"),
    // Diagnostic stages: shown on the HUD even when no turn is active, so a
    // "bot does nothing" report can be diagnosed from the screen.
    WAITING("WAITING"),
    NEEDS_AI("NEEDS AI")
}

data class CarromAimVisualData(
    val boardLeft: Float,
    val boardTop: Float,
    val boardRight: Float,
    val boardBottom: Float,
    val strikerCenter: Point2D?,
    val tracedCoins: List<CarromPiece>,
    val targetCoin: Point2D?,
    val ghostBall: Point2D?,
    val targetPocket: Point2D?,
    val dragEnd: Point2D?,
    val cutAngleDeg: Float = 0f,
    val isSureShot: Boolean = false
)

data class CarromTurnHudState(
    val isActive: Boolean = false,
    val elapsedSec: Float = 0f,
    val maxSec: Float = 5.0f,
    val probeNumber: Int = 0,
    val maxProbes: Int = 2,
    val scanCount: Int = 0,
    val rejectedCount: Int = 0,
    val stage: TurnProbeStage = TurnProbeStage.PRE_SCAN,
    val detail: String = "",
    val whiteCount: Int = 0,
    val blackCount: Int = 0,
    val queenVisible: Boolean = false,
    val strikerLocked: Boolean = false,
    val aimSummary: String = "",
    val aimVisual: CarromAimVisualData? = null
)

/**
 * Per-Turn Working Memory, Ranked 2-Spot Fast Baseline Probe Queue & Strict 5-Second Shot Clock.
 *
 * 1. Scans the board & stabilizes piece coordinates across frames.
 * 2. Holds up to 2 physics + training-ranked baseline X candidate positions.
 * 3. Fuses Training Data (Watch & Play) with Physics Rollout to lock Sure Shots fast.
 * 4. Enforces a strict 3.4s probe cutoff / 4.5s hard fire deadline / 5.0s
 *    absolute budget: from turn detection the shot drag MUST start within
 *    5s. Past 4.5s the bot fires immediately (best sure shot remembered
 *    so far, else a foul-safe safety shot) with no more probing and no
 *    pre-shot pause. Only physics-verified sure shots are ever fired as
 *    pots — never a low-confidence gamble, never a foul-risk shot.
 */
class CarromTurnMemory {

    companion object {
        const val MAX_TURN_BUDGET_MS = 5_000L
        const val FALLBACK_FIRE_ELAPSED_MS = 3_400L
        // Hard fire deadline: past this elapsed time the current cycle MUST
        // fire. The remaining 500ms covers the drag dispatch + gesture start.
        const val SHOT_FIRE_DEADLINE_MS = 4_500L
        const val MAX_BASELINE_PROBES = 2
        private const val LIVE_FIRE_RESERVE_MS = 950L
        private const val A11Y_FIRE_RESERVE_MS = 1_250L
        private const val MAX_STORED_SCANS = 20
        private const val SPOT_MATCH_TOLERANCE_PX = 28f
    }

    data class TurnScanFrame(
        val timestampMs: Long,
        val strikerX: Float?,
        val sliderKnobX: Float?,
        val pieces: List<CarromPiece>,
        val bestConfidence: Float,
        val isSureShot: Boolean
    )

    private var turnStartMs: Long = 0L
    private val scanFrames = mutableListOf<TurnScanFrame>()
    private val visitedStrikerXs = mutableListOf<Float>()
    private val localSliderGains = mutableListOf<Float>()
    private val rankedProbeQueue = mutableListOf<RankedBaselineSpot>()
    private val rejectedSpots = mutableListOf<Pair<Float, String>>()
    private var probeQueueInitialized = false
    private var _sliderMovesThisTurn: Int = 0
    private var bestEvaluatedThisTurn: CarromShotSolution? = null
    private var returnedToBestSpot: Boolean = false
    // Last full detect+solve cycle duration (ms). A slow device / slow
    // screenshot pipeline makes each cycle longer than the fixed reserve
    // assumes, so the reserve must grow with the measured cycle time —
    // otherwise a probe dispatched "in budget" overruns the 5s deadline
    // before the next cycle can fire (sim case C4: 1500ms cycles).
    private var lastCycleDurationMs: Long = 0L

    /**
     * Record how long the last detect+solve cycle took (screenshot grab
     * through analyzeAndSolve return). Called by the coordinator once per
     * cycle, before the probe/fire decision, so canAffordAnotherSliderMove
     * can refuse a probe the next cycle cannot finish in budget.
     */
    fun recordCycleDuration(durationMs: Long) {
        lastCycleDurationMs = durationMs.coerceAtLeast(0L)
    }

    /**
     * True when holding the safety shot for one more cycle still leaves
     * room to fire within 5s. A WAIT costs a FIXED 1500ms in
     * GestureExecutor (its waitSeconds is ignored) PLUS one more
     * detect+solve cycle PLUS the drag dispatch — so the wait is only
     * affordable when the remaining budget covers all three plus a
     * 200ms margin. On a slow device the wait is refused and the safety
     * fires immediately instead; waiting there would push the shot past
     * 5s (the hard rule outranks waiting for a sure shot).
     */
    fun canAffordToWaitForSureShot(nowMs: Long = System.currentTimeMillis()): Boolean {
        val waitCostMs = 1_500L
        val nextCycleMs = lastCycleDurationMs.coerceAtLeast(400L)
        val dispatchReserveMs = 300L
        val marginMs = 200L
        return remainingMs(nowMs) > waitCostMs + nextCycleMs + dispatchReserveMs + marginMs
    }

    val sliderMovesThisTurn: Int get() = _sliderMovesThisTurn
    val scanCount: Int get() = scanFrames.size
    val isTurnActive: Boolean get() = turnStartMs > 0L
    val isProbeQueueInitialized: Boolean get() = probeQueueInitialized
    val rejectedProbeCount: Int get() = rejectedSpots.size
    val totalPlannedProbes: Int get() = rankedProbeQueue.size.coerceAtMost(MAX_BASELINE_PROBES)

    fun ensureTurnStarted(nowMs: Long = System.currentTimeMillis()) {
        if (turnStartMs == 0L) {
            turnStartMs = nowMs
        }
    }

    fun elapsedMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (turnStartMs == 0L) return 0L
        return (nowMs - turnStartMs).coerceAtLeast(0L)
    }

    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long {
        if (turnStartMs == 0L) return MAX_TURN_BUDGET_MS
        return (MAX_TURN_BUDGET_MS - elapsedMs(nowMs)).coerceAtLeast(0L)
    }

    /**
     * True when fewer than 2 slider moves have been dispatched AND there is enough room inside
     * the 5.0s turn window to slide, verify the next frame, and still fire before the 3.85s cutoff.
     * The reserve is DYNAMIC: it is the larger of the fixed live/a11y
     * reserve and (last measured cycle duration + 400ms). A probe is only
     * dispatched when the next cycle can finish inside the budget — a
     * 1500ms cycle needs a 1900ms reserve, not the fixed 1250ms.
     */
    fun canAffordAnotherSliderMove(
        nowMs: Long = System.currentTimeMillis(),
        isLiveStreamActive: Boolean
    ): Boolean {
        ensureTurnStarted(nowMs)
        if (_sliderMovesThisTurn >= MAX_BASELINE_PROBES) return false
        val elapsed = elapsedMs(nowMs)
        if (elapsed >= FALLBACK_FIRE_ELAPSED_MS) return false
        val baseReserve = if (isLiveStreamActive) LIVE_FIRE_RESERVE_MS else A11Y_FIRE_RESERVE_MS
        val reserve = maxOf(baseReserve, lastCycleDurationMs + 400L)
        return remainingMs(nowMs) > reserve
    }

    fun shouldTriggerDeadlineFallback(
        nowMs: Long = System.currentTimeMillis(),
        isLiveStreamActive: Boolean
    ): Boolean {
        return !canAffordAnotherSliderMove(nowMs, isLiveStreamActive)
    }

    /**
     * Hard fire deadline (5s rule): true once the turn clock passes 4.5s.
     * When true the coordinator must fire on the current cycle — no more
     * probing, no closed-loop correction, no pre-shot pause.
     */
    fun mustFireNow(nowMs: Long = System.currentTimeMillis()): Boolean {
        return isTurnActive && elapsedMs(nowMs) >= SHOT_FIRE_DEADLINE_MS
    }

    /**
     * Best physics-verified sure shot evaluated so far this turn, if any.
     * A remembered shot is only "sure" from the striker X it was solved
     * for — if the striker has since moved further than maxShiftPx away,
     * the solved aim no longer applies and the shot must NOT be re-fired
     * (firing it from the wrong X would turn a sure shot into a gamble).
     */
    fun bestSureShotThisTurn(
        currentStrikerX: Float? = null,
        maxShiftPx: Float = Float.MAX_VALUE
    ): CarromShotSolution? {
        val best = bestEvaluatedThisTurn ?: return null
        if (!best.isSureShot || best.rejectedDueToCollisionOrFoul) return null
        if (currentStrikerX != null && abs(best.strikerPos.x - currentStrikerX) > maxShiftPx) return null
        return best
    }

    fun initializeProbeQueueIfNeeded(spots: List<RankedBaselineSpot>) {
        if (probeQueueInitialized) return
        probeQueueInitialized = true
        rankedProbeQueue.clear()
        rankedProbeQueue.addAll(spots.take(MAX_BASELINE_PROBES))
    }

    fun markSpotRejected(strikerX: Float, reason: String) {
        if (rejectedSpots.none { abs(it.first - strikerX) <= SPOT_MATCH_TOLERANCE_PX }) {
            rejectedSpots.add(strikerX to reason)
        }
    }

    fun nextUntestedProbeSpot(
        currentStrikerX: Float?,
        tolerancePx: Float = SPOT_MATCH_TOLERANCE_PX
    ): RankedBaselineSpot? {
        if (_sliderMovesThisTurn >= MAX_BASELINE_PROBES) return null
        for (spot in rankedProbeQueue) {
            val nearCurrent = currentStrikerX != null && abs(spot.strikerX - currentStrikerX) <= tolerancePx
            val alreadyRejected = rejectedSpots.any { abs(it.first - spot.strikerX) <= tolerancePx }
            val alreadyVisited = visitedStrikerXs.any { abs(it - spot.strikerX) <= tolerancePx }
            if (!nearCurrent && !alreadyRejected && !alreadyVisited) {
                return spot
            }
        }
        return null
    }

    fun hasMoreProbesRemaining(currentStrikerX: Float?): Boolean {
        return _sliderMovesThisTurn < MAX_BASELINE_PROBES &&
            nextUntestedProbeSpot(currentStrikerX) != null
    }

    fun recordSliderMoveDispatched(preStrikerX: Float?) {
        _sliderMovesThisTurn++
        if (preStrikerX != null) {
            visitedStrikerXs.add(preStrikerX)
        }
    }

    fun recordObservation(
        nowMs: Long = System.currentTimeMillis(),
        pieces: List<CarromPiece>,
        striker: CarromPiece?,
        sliderKnob: Point2D?,
        evaluatedSolution: CarromShotSolution?,
        observedSliderGain: Float? = null
    ) {
        ensureTurnStarted(nowMs)
        if (observedSliderGain != null) {
            localSliderGains.add(observedSliderGain.coerceIn(0.65f, 1.45f))
        }
        val nonStriker = pieces.filter { it.color != CoinColor.STRIKER }
        scanFrames.add(
            TurnScanFrame(
                timestampMs = nowMs,
                strikerX = striker?.center?.x,
                sliderKnobX = sliderKnob?.x,
                pieces = nonStriker,
                bestConfidence = evaluatedSolution?.confidenceScore ?: 0f,
                isSureShot = evaluatedSolution?.isSureShot == true
            )
        )
        if (scanFrames.size > MAX_STORED_SCANS) {
            scanFrames.removeAt(0)
        }
        if (striker != null) {
            visitedStrikerXs.add(striker.center.x)
        }
        if (evaluatedSolution != null && !evaluatedSolution.rejectedDueToCollisionOrFoul) {
            val prev = bestEvaluatedThisTurn
            if (prev == null ||
                (evaluatedSolution.isSureShot && !prev.isSureShot) ||
                (evaluatedSolution.isSureShot == prev.isSureShot && evaluatedSolution.confidenceScore > prev.confidenceScore)
            ) {
                bestEvaluatedThisTurn = evaluatedSolution
            }
        }
    }

    fun bestEvaluatedSpotToReturnTo(
        currentStrikerX: Float?,
        currentInPlaceConfidence: Float,
        minShiftPx: Float = 54f
    ): Float? {
        if (returnedToBestSpot || currentStrikerX == null) return null
        val best = bestEvaluatedThisTurn ?: return null
        if (best.rejectedDueToCollisionOrFoul || best.isBreakShot) return null
        val dist = abs(best.strikerPos.x - currentStrikerX)
        if (dist <= minShiftPx) return null
        return if (best.confidenceScore > currentInPlaceConfidence + 0.14f) best.strikerPos.x else null
    }

    fun markReturnedToBestSpot() {
        returnedToBestSpot = true
    }

    fun isSliderStalledOrOscillating(targetStrikerX: Float, currentStrikerX: Float): Boolean {
        if (visitedStrikerXs.size < 2) return false
        val lastPrev = visitedStrikerXs[visitedStrikerXs.size - 2]
        val movedFromPrev = abs(currentStrikerX - lastPrev)
        val stillFarFromTarget = abs(targetStrikerX - currentStrikerX) > 24f
        if (_sliderMovesThisTurn >= 2 && movedFromPrev < 6f && stillFarFromTarget) {
            return true
        }
        if (visitedStrikerXs.size >= 4) {
            val xA = visitedStrikerXs[visitedStrikerXs.size - 1]
            val xC = visitedStrikerXs[visitedStrikerXs.size - 3]
            if (abs(xA - xC) < 12f && abs(xA - targetStrikerX) > 24f) {
                return true
            }
        }
        return false
    }

    fun effectiveTurnSliderGain(persistedGain: Float): Float {
        if (localSliderGains.isEmpty()) return persistedGain
        val localAvg = localSliderGains.average().toFloat()
        return ((persistedGain + localAvg * localSliderGains.size) / (1 + localSliderGains.size))
            .coerceIn(0.65f, 1.45f)
    }

    fun buildConsensusPieces(
        latestPieces: List<CarromPiece>,
        coinRadiusPx: Float = 28f
    ): List<CarromPiece> {
        val latestNonStriker = latestPieces.filter { it.color != CoinColor.STRIKER }
        if (scanFrames.size <= 1) return latestNonStriker

        val matchThreshold = (coinRadiusPx * 1.2f).coerceAtLeast(20f)
        val fused = ArrayList<CarromPiece>(latestNonStriker.size + 4)

        for (piece in latestNonStriker) {
            var sumX = piece.center.x * 1.4f
            var sumY = piece.center.y * 1.4f
            var weight = 1.4f

            for (frame in scanFrames.dropLast(1)) {
                val match = frame.pieces.minByOrNull { prev ->
                    if (prev.color != piece.color) Float.MAX_VALUE
                    else hypot(prev.center.x - piece.center.x, prev.center.y - piece.center.y)
                }
                if (match != null &&
                    match.color == piece.color &&
                    hypot(match.center.x - piece.center.x, match.center.y - piece.center.y) <= matchThreshold
                ) {
                    sumX += match.center.x
                    sumY += match.center.y
                    weight += 1f
                }
            }
            fused.add(piece.copy(center = Point2D(sumX / weight, sumY / weight)))
        }

        if (scanFrames.size >= 3) {
            val candidatePool = scanFrames.dropLast(1).flatMap { it.pieces }
            val visitedExtra = mutableListOf<Point2D>()

            for (cand in candidatePool) {
                val alreadyInFused = fused.any {
                    hypot(it.center.x - cand.center.x, it.center.y - cand.center.y) <= matchThreshold
                }
                val alreadyRecovered = visitedExtra.any {
                    hypot(it.x - cand.center.x, it.y - cand.center.y) <= matchThreshold
                }
                if (alreadyInFused || alreadyRecovered) continue

                var occurrences = 0
                var sumX = 0f
                var sumY = 0f
                for (frame in scanFrames.dropLast(1)) {
                    val m = frame.pieces.firstOrNull { prev ->
                        prev.color == cand.color &&
                            hypot(prev.center.x - cand.center.x, prev.center.y - cand.center.y) <= matchThreshold
                    }
                    if (m != null) {
                        occurrences++
                        sumX += m.center.x
                        sumY += m.center.y
                    }
                }
                if (occurrences >= 2) {
                    val center = Point2D(sumX / occurrences, sumY / occurrences)
                    visitedExtra.add(center)
                    fused.add(CarromPiece(cand.color, center, cand.radiusPx))
                }
            }
        }

        return fused
    }

    /**
     * Deadline fallback (when <=2 probes finish or the 5s clock hits the
     * 4.5s hard fire deadline): combines all accumulated Turn Memory
     * frames, Training Data, and Physics Engine to MUST-FIRE a shot.
     * Sure-shot only: the chain returns a physics-verified sure pot, a
     * verified zero-scratch break, or a foul-safe safety shot — a
     * low-confidence pot is NEVER returned here, even as a last resort.
     */
    fun solveCombinedFinalShot(
        board: CarromBoardLayout,
        latestPieces: List<CarromPiece>,
        striker: CarromPiece?,
        myCoinColor: CoinColor,
        screenWidth: Int,
        screenHeight: Int,
        shotMemory: CarromMemoryData?,
        usePhysicsRollout: Boolean,
        fallbackSolution: CarromShotSolution?,
        isFreestyle: Boolean = false,
        queenPendingCover: Boolean = false,
        difficulty: CarromDifficulty = CarromDifficulty.MEDIUM
    ): CarromShotSolution? {
        val consensusPieces = buildConsensusPieces(latestPieces, board.coinRadiusPx)
        val anchorStriker = striker ?: run {
            val lastX = scanFrames.lastOrNull { it.strikerX != null }?.strikerX
                ?: fallbackSolution?.strikerPos?.x
                ?: (board.left + board.width * 0.5f)
            CarromPiece(CoinColor.STRIKER, Point2D(lastX, board.baselineY), board.strikerRadiusPx)
        }
        val combinedPieces = consensusPieces + anchorStriker

        val solved = CarromGeometrySolver.solveSafestBreakOrPotShot(
            board = board,
            pieces = combinedPieces,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = anchorStriker,
            shotMemory = shotMemory,
            usePhysicsRollout = usePhysicsRollout,
            isFreestyle = isFreestyle,
            queenPendingCover = queenPendingCover,
            difficulty = difficulty
        )
        if (solved != null) return solved

        // Only a remembered SURE shot may be re-fired — a merely good-looking
        // remembered pot is a gamble and stays on the shelf.
        val bestRemembered = bestEvaluatedThisTurn?.takeIf {
            it.isSureShot && !it.rejectedDueToCollisionOrFoul &&
                abs(it.strikerPos.x - anchorStriker.center.x) <= board.strikerRadiusPx * 1.6f
        }
        if (bestRemembered != null) return bestRemembered
        if (fallbackSolution != null && fallbackSolution.isSureShot &&
            !fallbackSolution.rejectedDueToCollisionOrFoul
        ) {
            return fallbackSolution
        }
        // Terminal fallback: a foul-safe safety touch. The old guaranteed
        // emergency pot (0.40 confidence, unverified) is gone — under the
        // 5s sure-shot / no-foul rule it must never be fired.
        return CarromGeometrySolver.solveSafetyShot(
            board = board,
            pieces = combinedPieces,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = anchorStriker,
            shotMemory = shotMemory,
            isFreestyle = isFreestyle
        )
    }

    fun buildHudState(
        nowMs: Long,
        stage: TurnProbeStage,
        detail: String = "",
        board: CarromBoardLayout? = null,
        tracedPieces: List<CarromPiece> = emptyList(),
        strikerPos: Point2D? = null,
        solution: CarromShotSolution? = null,
        dragEndOverride: Point2D? = null,
        aimSummary: String = "",
        // Diagnostic stages (WAITING / NEEDS_AI) have no active turn, but the
        // HUD must still show so the current step is visible on screen.
        forceActive: Boolean = false
    ): CarromTurnHudState {
        val nonStriker = tracedPieces.filter { it.color != CoinColor.STRIKER }
        val whiteCount = nonStriker.count { it.color == CoinColor.WHITE }
        val blackCount = nonStriker.count { it.color == CoinColor.BLACK }
        val queenVisible = nonStriker.any { it.color == CoinColor.QUEEN }
        val visual = if (board != null) {
            CarromAimVisualData(
                boardLeft = board.left,
                boardTop = board.top,
                boardRight = board.right,
                boardBottom = board.bottom,
                strikerCenter = strikerPos ?: solution?.strikerPos,
                tracedCoins = nonStriker,
                targetCoin = solution?.targetCoin?.center,
                ghostBall = solution?.ghostBall,
                targetPocket = solution?.targetPocket,
                dragEnd = dragEndOverride ?: solution?.dragEnd,
                cutAngleDeg = solution?.cutAngleDeg ?: 0f,
                isSureShot = solution?.isSureShot == true
            )
        } else {
            null
        }
        return CarromTurnHudState(
            isActive = isTurnActive || forceActive,
            elapsedSec = elapsedMs(nowMs) / 1000f,
            maxSec = MAX_TURN_BUDGET_MS / 1000f,
            probeNumber = (_sliderMovesThisTurn + 1).coerceAtMost(MAX_BASELINE_PROBES),
            maxProbes = totalPlannedProbes.coerceAtLeast(1).coerceAtMost(MAX_BASELINE_PROBES),
            scanCount = scanCount,
            rejectedCount = rejectedSpots.size,
            stage = stage,
            detail = detail,
            whiteCount = whiteCount,
            blackCount = blackCount,
            queenVisible = queenVisible,
            strikerLocked = strikerPos != null,
            aimSummary = aimSummary,
            aimVisual = visual
        )
    }

    fun formatTurnTelemetry(nowMs: Long, sol: CarromShotSolution): String {
        val sec = elapsedMs(nowMs) / 1000f
        val status = when {
            sol.isSafetyShot -> "SAFE"
            sol.isTrainingMatched && sol.isSureShot -> "TRAIN-SURE"
            sol.isCollisionAssistedSureShot -> "CAROM-SURE"
            sol.isSureShot -> "SURE"
            sol.isBreakShot -> "BREAK-5s"
            else -> "MUST-5s"
        }
        return String.format(
            java.util.Locale.US,
            "[5s Clock %.1fs/5.0s | Probe %d/%d (%d rej) | %s]",
            sec,
            (_sliderMovesThisTurn + 1).coerceAtMost(MAX_BASELINE_PROBES),
            totalPlannedProbes.coerceAtLeast(1).coerceAtMost(MAX_BASELINE_PROBES),
            rejectedSpots.size,
            status
        )
    }

    fun resetTurn() {
        turnStartMs = 0L
        scanFrames.clear()
        visitedStrikerXs.clear()
        localSliderGains.clear()
        rankedProbeQueue.clear()
        rejectedSpots.clear()
        probeQueueInitialized = false
        _sliderMovesThisTurn = 0
        bestEvaluatedThisTurn = null
        returnedToBestSpot = false
        lastCycleDurationMs = 0L
    }
}
