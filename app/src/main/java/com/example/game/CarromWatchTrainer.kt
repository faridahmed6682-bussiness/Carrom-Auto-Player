package com.example.game

import com.example.agent.CarromContextHelper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

object CarromCoachHelper {
    const val CARROM_WATCH_TRAIN_GOAL =
        "Carrom Watch & Learn Training: watch me play live Carrom matches passively with zero touches, learn winning pocket cut angles, striker baseline positions, board friction and power feel from my shots, and save everything into Carrom Memory and Playbook."

    const val CARROM_AUTO_PLAY_GOAL =
        "Carrom Auto-Play: open Carrom game, fuse my Watch & Learn Training data with the 2D Physics Solver & Turn Memory, adjust the slider cleanly, and take zero-foul Sure Shots automatically."

    fun isCarromWatchTrainGoal(goal: String): Boolean {
        val trimmed = goal.trim()
        if (trimmed == CARROM_WATCH_TRAIN_GOAL) return true
        if (trimmed == CARROM_AUTO_PLAY_GOAL) return false
        if (!CarromContextHelper.isCarromGoalOrApp(trimmed, "", null)) return false

        // Explicit post-training or autonomous-play requests must never be trapped in passive Watch & Learn mode
        val saysDoneTrainingAndPlay = trimmed.contains("দিয়েছি") ||
            trimmed.contains("দিয়েছে") ||
            trimmed.contains("দিলাম") ||
            trimmed.contains("দেওয়া") ||
            trimmed.contains("দেওয়ার") ||
            trimmed.contains("দেওয়ার") ||
            trimmed.contains("খেলেছি") ||
            trimmed.contains("নিয়েছি") ||
            trimmed.contains("নিলাম") ||
            trimmed.contains("করেছি") ||
            trimmed.contains("শেষ") ||
            trimmed.contains("এবার খেল") ||
            trimmed.contains("এখন খেল") ||
            trimmed.contains("নিজে খেল") ||
            trimmed.contains("নিজে থেকে") ||
            trimmed.contains("নিজে শট") ||
            trimmed.contains("শট নে") ||
            trimmed.contains("শট মার") ||
            trimmed.contains("আসল ম্যাচ") ||
            trimmed.contains("auto-play", ignoreCase = true) ||
            trimmed.contains("autoplay", ignoreCase = true) ||
            trimmed.contains("auto play", ignoreCase = true) ||
            trimmed.contains("use training", ignoreCase = true) ||
            trimmed.contains("after training", ignoreCase = true) ||
            trimmed.contains("real match", ignoreCase = true) ||
            trimmed.contains("play yourself", ignoreCase = true) ||
            trimmed.contains("take shots", ignoreCase = true) ||
            trimmed.contains("shoot automatically", ignoreCase = true) ||
            trimmed.contains("play autonomously", ignoreCase = true)
        if (saysDoneTrainingAndPlay) return false

        val explicitEnglishWatch = trimmed.contains("watch & learn", ignoreCase = true) ||
            trimmed.contains("watch me play", ignoreCase = true) ||
            trimmed.contains("watch us play", ignoreCase = true) ||
            trimmed.contains("watch both", ignoreCase = true) ||
            trimmed.contains("train from my", ignoreCase = true) ||
            trimmed.contains("learn from my", ignoreCase = true) ||
            trimmed.contains("passive coach", ignoreCase = true) ||
            trimmed.contains("observe match", ignoreCase = true)
        if (explicitEnglishWatch) return true

        val explicitBanglaWatch = trimmed.contains("দেখে শিখ") ||
            trimmed.contains("খেলা দেখে") ||
            trimmed.contains("দেখে ট্রেনিং") ||
            trimmed.contains("দেখে শেখ") ||
            trimmed.contains("দেখে নাও")
        if (explicitBanglaWatch) return true

        val asksToTakeTraining = trimmed.contains("ট্রেনিং") &&
            (trimmed.contains("নাও") || trimmed.contains("নেও") || trimmed.contains("দাও") || trimmed.contains("শুরু কর"))
        return asksToTakeTraining
    }
}

enum class WatchTurnSide {
    PLAYER_BOTTOM,
    OPPONENT_TOP
}

enum class WatchTrainerPhase {
    WAITING_FOR_BOARD,
    ARMED_PRE_SHOT,
    TRACKING_MOTION
}

data class InferredHumanShot(
    val pendingShot: PendingCarromShotRecord,
    val postCounts: BoardPieceCounts,
    val observedPowerHint: Float?,
    val humanInsightSummary: String,
    val shooterSide: WatchTurnSide = WatchTurnSide.PLAYER_BOTTOM
)

data class WatchFrameUpdate(
    val phase: WatchTrainerPhase,
    val isBoardVisible: Boolean,
    val hudStatusText: String,
    val completedShot: InferredHumanShot? = null
)

/**
 * Passive Two-Sided Watch & Learn Carrom Trainer.
 *
 * Observes live Carrom frames silently (ZERO injected touches/swipes) from BOTH sides:
 * 1. Arms when either your striker is on the bottom baseline OR opponent's striker is on the top baseline.
 * 2. Detects baseline striker repositioning and shot release motion on either side.
 * 3. Waits until all discs settle again, matches pre- vs post-board discs to infer target coin,
 *    cut angle, pocket, power style, and pot/foul outcome (mirroring opponent top-side geometry
 *    into bottom-player equivalent pocket & row zones).
 * 4. Feeds calibrated lessons directly into CarromShotMemoryStore + TaskPlaybook without
 *    polluting your own foul blacklist or slider placement stats with opponent mistakes.
 */
class CarromWatchTrainer {

    private var turnInitialStrikerX: Float? = null
    private var preShotObs: CarromWatchObservation? = null
    private var armedTurnSide: WatchTurnSide? = null
    private var inMotion: Boolean = false
    private var motionStartMs: Long = 0L
    private var maxMotionDiff: Float = 0f
    private var earlyMovedStriker: Point2D? = null
    private var settledStillFrames: Int = 0
    private var missedBoardWhileInMotionFrames: Int = 0

    var shotsObserved: Int = 0
        private set
    var potsObserved: Int = 0
        private set
    var foulsObserved: Int = 0
        private set

    fun reset() {
        turnInitialStrikerX = null
        preShotObs = null
        armedTurnSide = null
        inMotion = false
        motionStartMs = 0L
        maxMotionDiff = 0f
        earlyMovedStriker = null
        settledStillFrames = 0
        missedBoardWhileInMotionFrames = 0
        shotsObserved = 0
        potsObserved = 0
        foulsObserved = 0
    }

    fun processObservation(
        obs: CarromWatchObservation?,
        myCoinColor: CoinColor,
        isFreestyle: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): WatchFrameUpdate {
        if (obs == null || !obs.isLikelyGameBoard) {
            if (inMotion) {
                missedBoardWhileInMotionFrames++
                if (missedBoardWhileInMotionFrames > 25) {
                    inMotion = false
                    settledStillFrames = 0
                    missedBoardWhileInMotionFrames = 0
                    preShotObs = null
                    armedTurnSide = null
                } else {
                    val sideTag = if (armedTurnSide == WatchTurnSide.OPPONENT_TOP) "Opponent" else "Your"
                    return WatchFrameUpdate(
                        phase = WatchTrainerPhase.TRACKING_MOTION,
                        isBoardVisible = false,
                        hudStatusText = "Tracking $sideTag shot ($shotsObserved learned)..."
                    )
                }
            }
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.WAITING_FOR_BOARD,
                isBoardVisible = false,
                hudStatusText = if (shotsObserved > 0) {
                    "Learned $shotsObserved shots ($potsObserved pots) — waiting for Carrom board..."
                } else {
                    "Waiting for active Carrom match board (both sides learned passively)..."
                }
            )
        }

        missedBoardWhileInMotionFrames = 0

        if (!inMotion) {
            val softShotTriggered = preShotObs != null && didSoftShotStart(preShotObs!!, obs, armedTurnSide)
            val hasPlayerStrikerOnBaseline = obs.striker != null || obs.sliderKnob != null
            val hasOpponentStrikerOnBaseline = !hasPlayerStrikerOnBaseline && obs.topStriker != null
            if (!obs.isInMotion && !softShotTriggered && (hasPlayerStrikerOnBaseline || hasOpponentStrikerOnBaseline)) {
                val side = if (hasPlayerStrikerOnBaseline) WatchTurnSide.PLAYER_BOTTOM else WatchTurnSide.OPPONENT_TOP
                if (armedTurnSide != side) {
                    turnInitialStrikerX = null
                    armedTurnSide = side
                }
                val resolvedX = if (side == WatchTurnSide.PLAYER_BOTTOM) {
                    obs.striker?.center?.x ?: obs.sliderKnob?.x
                } else {
                    obs.topStriker?.center?.x
                }
                if (turnInitialStrikerX == null && resolvedX != null) {
                    turnInitialStrikerX = resolvedX
                }
                preShotObs = obs
                val movedFlag = turnInitialStrikerX != null && resolvedX != null &&
                    abs(resolvedX - turnInitialStrikerX!!) > 22f
                val aimNote = if (movedFlag) "slider adjusted" else "ready on baseline"
                val sideTag = if (side == WatchTurnSide.PLAYER_BOTTOM) "Your Turn" else "Opponent Turn"
                return WatchFrameUpdate(
                    phase = WatchTrainerPhase.ARMED_PRE_SHOT,
                    isBoardVisible = true,
                    hudStatusText = "Armed [$sideTag] ($aimNote) • $shotsObserved shots / $potsObserved pots learned"
                )
            }

            if ((obs.isInMotion || softShotTriggered) && preShotObs != null) {
                val pre = preShotObs!!
                val side = armedTurnSide
                    ?: if (pre.striker != null || pre.sliderKnob != null) WatchTurnSide.PLAYER_BOTTOM else WatchTurnSide.OPPONENT_TOP
                armedTurnSide = side
                inMotion = true
                motionStartMs = nowMs
                maxMotionDiff = obs.lumaDiff
                settledStillFrames = if (!obs.isInMotion) 1 else 0
                val coinR = pre.board.coinRadiusPx
                earlyMovedStriker = if (side == WatchTurnSide.PLAYER_BOTTOM) {
                    val preBaselineY = pre.board.baselineY
                    obs.striker?.center?.takeIf { it.y < preBaselineY - coinR * 1.2f }
                } else {
                    val preTopY = pre.topStriker?.center?.y ?: (pre.board.top + pre.board.height * 0.155f)
                    obs.topStriker?.center?.takeIf { it.y > preTopY + coinR * 1.2f }
                }
                val sideTag = if (side == WatchTurnSide.PLAYER_BOTTOM) "Your Turn" else "Opponent Turn"
                return WatchFrameUpdate(
                    phase = WatchTrainerPhase.TRACKING_MOTION,
                    isBoardVisible = true,
                    hudStatusText = "Shot in motion [$sideTag] — observing trajectory & impact..."
                )
            }

            // Neither striker clearly resting on a baseline yet
            turnInitialStrikerX = null
            armedTurnSide = null
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.WAITING_FOR_BOARD,
                isBoardVisible = true,
                hudStatusText = "Waiting for next turn ($shotsObserved shots learned from both sides)..."
            )
        }

        // Currently tracking shot motion
        maxMotionDiff = maxOf(maxMotionDiff, obs.lumaDiff)
        val pre = preShotObs
        val trackingSide = armedTurnSide ?: WatchTurnSide.PLAYER_BOTTOM
        if (pre != null && earlyMovedStriker == null) {
            if (trackingSide == WatchTurnSide.PLAYER_BOTTOM && obs.striker != null) {
                val s = obs.striker.center
                if (s.y < pre.board.baselineY - pre.board.coinRadiusPx * 1.2f) {
                    earlyMovedStriker = s
                }
            } else if (trackingSide == WatchTurnSide.OPPONENT_TOP && obs.topStriker != null) {
                val s = obs.topStriker.center
                val preTopY = pre.topStriker?.center?.y ?: (pre.board.top + pre.board.height * 0.155f)
                if (s.y > preTopY + pre.board.coinRadiusPx * 1.2f) {
                    earlyMovedStriker = s
                }
            }
        }

        val elapsedMotionMs = nowMs - motionStartMs
        if (!obs.isInMotion) {
            settledStillFrames++
        } else {
            settledStillFrames = 0
        }

        val readyToEvaluate = (settledStillFrames >= 2) ||
            (settledStillFrames >= 1 && elapsedMotionMs > 2_200L) ||
            (elapsedMotionMs > 7_500L && pre != null)

        if (!readyToEvaluate) {
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.TRACKING_MOTION,
                isBoardVisible = true,
                hudStatusText = "Coins moving — waiting for board to settle..."
            )
        }

        inMotion = false
        settledStillFrames = 0
        val preSnapshot = preShotObs
        val evaluatedSide = armedTurnSide
            ?: if (preSnapshot?.striker != null || preSnapshot?.sliderKnob != null) {
                WatchTurnSide.PLAYER_BOTTOM
            } else {
                WatchTurnSide.OPPONENT_TOP
            }

        val nextHasPlayer = obs.striker != null || obs.sliderKnob != null
        val nextHasOpponent = !nextHasPlayer && obs.topStriker != null
        preShotObs = when {
            nextHasPlayer -> obs
            nextHasOpponent -> obs
            else -> null
        }
        armedTurnSide = when {
            nextHasPlayer -> WatchTurnSide.PLAYER_BOTTOM
            nextHasOpponent -> WatchTurnSide.OPPONENT_TOP
            else -> null
        }

        if (preSnapshot == null) {
            return WatchFrameUpdate(
                phase = WatchTrainerPhase.WAITING_FOR_BOARD,
                isBoardVisible = true,
                hudStatusText = "Waiting for next turn ($shotsObserved shots learned)..."
            )
        }

        val inferred = inferHumanShotFromFrames(
            preShot = preSnapshot,
            postShot = obs,
            earlyMovedStriker = earlyMovedStriker,
            turnInitialStrikerX = turnInitialStrikerX,
            maxMotionDiff = maxMotionDiff,
            myCoinColor = myCoinColor,
            isFreestyle = isFreestyle,
            turnSide = evaluatedSide,
            nowMs = nowMs
        )
        earlyMovedStriker = null

        if (inferred == null) {
            // False motion spike (e.g., UI ripple/banner without moving coins or firing striker)
            val nextPhase = if (preShotObs != null) WatchTrainerPhase.ARMED_PRE_SHOT else WatchTrainerPhase.WAITING_FOR_BOARD
            return WatchFrameUpdate(
                phase = nextPhase,
                isBoardVisible = true,
                hudStatusText = if (nextPhase == WatchTrainerPhase.ARMED_PRE_SHOT) {
                    "Ready on baseline ($shotsObserved shots learned) — watching both sides!"
                } else {
                    "Waiting for next turn ($shotsObserved shots learned)..."
                }
            )
        }

        shotsObserved++
        val preCounts = inferred.pendingShot.preCounts
        val postCounts = inferred.postCounts
        val preTotal = preCounts.myCount + preCounts.opponentCount + preCounts.queenCount
        val postTotal = postCounts.myCount + postCounts.opponentCount + postCounts.queenCount
        val isFoul = if (isFreestyle) {
            postTotal > preTotal
        } else {
            (postCounts.myCount > preCounts.myCount) ||
                (postCounts.opponentCount < preCounts.opponentCount && postCounts.myCount >= preCounts.myCount && !(preCounts.queenCount > 0 && postCounts.queenCount == 0))
        }
        val isPot = !isFoul && (
            (preCounts.queenCount > 0 && postCounts.queenCount == 0) ||
                postCounts.myCount < preCounts.myCount ||
                (isFreestyle && postCounts.opponentCount < preCounts.opponentCount)
            )
        if (isFoul) foulsObserved++ else if (isPot) potsObserved++

        // Reset initial striker X if a side is ready on baseline so next slide is measured fresh
        turnInitialStrikerX = when (armedTurnSide) {
            WatchTurnSide.PLAYER_BOTTOM -> obs.striker?.center?.x ?: obs.sliderKnob?.x
            WatchTurnSide.OPPONENT_TOP -> obs.topStriker?.center?.x
            null -> null
        }

        return WatchFrameUpdate(
            phase = if (preShotObs != null) WatchTrainerPhase.ARMED_PRE_SHOT else WatchTrainerPhase.WAITING_FOR_BOARD,
            isBoardVisible = true,
            hudStatusText = "Learned #$shotsObserved ($potsObserved pots, $foulsObserved fouls): ${inferred.humanInsightSummary}",
            completedShot = inferred
        )
    }

    private fun didSoftShotStart(
        pre: CarromWatchObservation,
        cur: CarromWatchObservation,
        armedSide: WatchTurnSide?
    ): Boolean {
        val side = armedSide
            ?: if (pre.striker != null || pre.sliderKnob != null) WatchTurnSide.PLAYER_BOTTOM else WatchTurnSide.OPPONENT_TOP
        val coinR = pre.board.coinRadiusPx.coerceAtLeast(14f)

        // 1. Striker visibly launched away from its baseline
        if (side == WatchTurnSide.PLAYER_BOTTOM && cur.striker != null &&
            cur.striker.center.y < pre.board.baselineY - coinR * 1.4f
        ) {
            return true
        }
        if (side == WatchTurnSide.OPPONENT_TOP && cur.topStriker != null) {
            val preTopY = pre.topStriker?.center?.y ?: (pre.board.top + pre.board.height * 0.155f)
            if (cur.topStriker.center.y > preTopY + coinR * 1.4f) {
                return true
            }
        }

        // 2. Non-striker coin count changed or a coin displaced meaningfully (even on a quiet soft tap)
        val preCoins = pre.pieces.filter { it.color != CoinColor.STRIKER }
        val curCoins = cur.pieces.filter { it.color != CoinColor.STRIKER }
        if (preCoins.size != curCoins.size) {
            return true
        }
        val moveTol = coinR * 0.95f
        for (p in preCoins) {
            val nearest = curCoins
                .filter { it.color == p.color }
                .minOfOrNull { hypot(it.center.x - p.center.x, it.center.y - p.center.y) }
            if (nearest == null || nearest >= moveTol) {
                return true
            }
        }

        // 3. Turn flipped to the opposite baseline between frames
        val hasBottomNow = cur.striker != null || cur.sliderKnob != null
        val hasTopNow = !hasBottomNow && cur.topStriker != null
        if (side == WatchTurnSide.PLAYER_BOTTOM && hasTopNow) return true
        if (side == WatchTurnSide.OPPONENT_TOP && hasBottomNow) return true

        return false
    }

    companion object {

        fun inferHumanShotFromFrames(
            preShot: CarromWatchObservation,
            postShot: CarromWatchObservation,
            earlyMovedStriker: Point2D? = null,
            turnInitialStrikerX: Float? = null,
            maxMotionDiff: Float = 18f,
            myCoinColor: CoinColor,
            isFreestyle: Boolean,
            turnSide: WatchTurnSide = WatchTurnSide.PLAYER_BOTTOM,
            nowMs: Long = System.currentTimeMillis()
        ): InferredHumanShot? {
            val board = preShot.board
            if (board.pockets.size < 4) return null

            val preStrikerPos = if (turnSide == WatchTurnSide.PLAYER_BOTTOM) {
                preShot.striker?.center
                    ?: preShot.sliderKnob?.let { Point2D(it.x, board.baselineY) }
            } else {
                preShot.topStriker?.center
            } ?: return null

            val preCoins = preShot.pieces.filter { it.color != CoinColor.STRIKER }
            val postCoins = postShot.pieces.filter { it.color != CoinColor.STRIKER }
            if (preCoins.isEmpty()) return null

            // In Disc Pool mode, opponent shoots the opposite color coins!
            val shooterCoinColor = if (turnSide == WatchTurnSide.OPPONENT_TOP && !isFreestyle) {
                if (myCoinColor == CoinColor.WHITE) CoinColor.BLACK else CoinColor.WHITE
            } else {
                myCoinColor
            }

            val preCounts = CarromShotMemoryStore.countBoardPieces(preCoins, shooterCoinColor)
            val postCounts = CarromShotMemoryStore.countBoardPieces(postCoins, shooterCoinColor)

            // Match each pre-coin greedily to its closest post-coin of the same color
            val unmatchedPost = postCoins.toMutableList()
            data class CoinTransition(
                val preCoin: CarromPiece,
                val matchedPost: CarromPiece?,
                val displacementPx: Float
            )

            val pairs = mutableListOf<Pair<CarromPiece, CarromPiece>>()
            val remainingPre = preCoins.toMutableList()
            while (remainingPre.isNotEmpty()) {
                var bestPreIdx = -1
                var bestPostIdx = -1
                var bestDist = Float.MAX_VALUE
                for (i in remainingPre.indices) {
                    val p = remainingPre[i]
                    for (j in unmatchedPost.indices) {
                        val q = unmatchedPost[j]
                        if (p.color != q.color) continue
                        val d = (q.center - p.center).length()
                        if (d < bestDist) {
                            bestDist = d
                            bestPreIdx = i
                            bestPostIdx = j
                        }
                    }
                }
                if (bestPreIdx < 0 || bestPostIdx < 0) break
                val preMatch = remainingPre.removeAt(bestPreIdx)
                val postMatch = unmatchedPost.removeAt(bestPostIdx)
                pairs.add(preMatch to postMatch)
            }

            val transitions = mutableListOf<CoinTransition>()
            for ((preC, postC) in pairs) {
                transitions.add(CoinTransition(preC, postC, (postC.center - preC.center).length()))
            }
            for (vanished in remainingPre) {
                transitions.add(CoinTransition(vanished, null, board.width * 0.65f))
            }

            val moveThreshold = board.coinRadiusPx * 0.55f
            val displacedCoins = transitions.filter { it.matchedPost == null || it.displacementPx >= moveThreshold }
            val maxDisp = transitions.maxOfOrNull { it.displacementPx } ?: 0f
            val countChanged = preCounts != postCounts

            // Filter false motion spikes (e.g., toast/banner flash with zero real shot)
            val postSameSideStriker = if (turnSide == WatchTurnSide.PLAYER_BOTTOM) postShot.striker else postShot.topStriker
            val strikerStillAtSameSpot = postSameSideStriker != null &&
                abs(postSameSideStriker.center.x - preStrikerPos.x) < 22f
            if (!countChanged && displacedCoins.isEmpty() && strikerStillAtSameSpot) {
                return null
            }

            // Detect central rack break shot
            val boardCenter = Point2D((board.left + board.right) * 0.5f, (board.top + board.bottom) * 0.5f)
            val centralPreClusterCount = preCoins.count { (it.center - boardCenter).length() <= board.coinRadiusPx * 3.8f }
            val isBreakShot = (centralPreClusterCount >= 5 && displacedCoins.size >= 4) || displacedCoins.size >= 6

            // Infer which target coin the shooter aimed at
            val vanishedCoins = transitions.filter { it.matchedPost == null }.map { it.preCoin }
            val legalColorFilter: (CarromPiece) -> Boolean = { c ->
                isFreestyle || c.color == shooterCoinColor || c.color == CoinColor.QUEEN
            }

            val earlyShotDir = earlyMovedStriker
                ?.takeIf { (it - preStrikerPos).length() > board.strikerRadiusPx }
                ?.let { (it - preStrikerPos).normalized() }

            val targetCoin: CarromPiece = when {
                vanishedCoins.any(legalColorFilter) -> {
                    val candidates = vanishedCoins.filter(legalColorFilter)
                    if (earlyShotDir != null) {
                        candidates.minByOrNull { c -> rayPointDistance(preStrikerPos, earlyShotDir, c.center) }
                            ?: candidates.first()
                    } else {
                        candidates.minByOrNull { c ->
                            board.pockets.minOf { p -> (p - c.center).length() }
                        } ?: candidates.first()
                    }
                }
                vanishedCoins.isNotEmpty() -> vanishedCoins.first()
                earlyShotDir != null -> {
                    preCoins.minByOrNull { c ->
                        val along = (c.center - preStrikerPos).dot(earlyShotDir)
                        if (along <= 0f) Float.MAX_VALUE
                        else rayPointDistance(preStrikerPos, earlyShotDir, c.center)
                    } ?: preCoins.first()
                }
                displacedCoins.any { legalColorFilter(it.preCoin) } -> {
                    displacedCoins
                        .filter { legalColorFilter(it.preCoin) }
                        .maxByOrNull { it.displacementPx }
                        ?.preCoin ?: preCoins.first()
                }
                displacedCoins.isNotEmpty() -> {
                    displacedCoins.maxByOrNull { it.displacementPx }!!.preCoin
                }
                else -> preCoins.filter(legalColorFilter).minByOrNull { (it.center - preStrikerPos).length() }
                    ?: preCoins.first()
            }

            // Infer pocket aimed at from striker -> targetCoin -> pocket geometry
            val strikerToTarget = (targetCoin.center - preStrikerPos).normalized()
            var bestPocketIdx = 0
            var bestCutDeg = 45f
            var bestPocketScore = Float.POSITIVE_INFINITY
            board.pockets.forEachIndexed { idx, pocket ->
                val toPocket = pocket - targetCoin.center
                val distPocket = toPocket.length().coerceAtLeast(1f)
                val dirCp = toPocket.normalized()
                val aimRefDir = earlyShotDir ?: strikerToTarget
                val cosTheta = aimRefDir.dot(dirCp).coerceIn(-1f, 1f)
                val angleDeg = (acos(cosTheta) * (180.0 / PI)).toFloat()
                val distBias = (distPocket / board.width) * 14f
                val composite = angleDeg + distBias
                if (composite < bestPocketScore) {
                    bestPocketScore = composite
                    bestPocketIdx = idx
                    bestCutDeg = angleDeg.coerceIn(0f, 68f)
                }
            }

            val dispRatio = (maxDisp / board.width).coerceIn(0f, 1.5f)
            val powerStyle = when {
                isBreakShot || displacedCoins.size >= 4 || dispRatio >= 0.42f || maxMotionDiff >= 28f ->
                    CarromPowerStyle.HARD
                dispRatio < 0.16f && displacedCoins.size <= 2 && maxMotionDiff < 19f ->
                    CarromPowerStyle.SOFT
                else -> CarromPowerStyle.MEDIUM
            }

            val preTotalCoins = preCounts.myCount + preCounts.opponentCount + preCounts.queenCount
            val postTotalCoins = postCounts.myCount + postCounts.opponentCount + postCounts.queenCount
            val cleanPotOccurred = if (isFreestyle) {
                postTotalCoins < preTotalCoins
            } else {
                (postCounts.myCount < preCounts.myCount || postCounts.queenCount < preCounts.queenCount) &&
                    postCounts.myCount <= preCounts.myCount
            }
            val observedPowerHint = when {
                isBreakShot -> null
                countChanged && cleanPotOccurred -> when (powerStyle) {
                    CarromPowerStyle.SOFT -> 0.89f
                    CarromPowerStyle.MEDIUM -> 1.00f
                    CarromPowerStyle.HARD -> 1.12f
                }
                else -> null
            }

            val strikerNormX = ((preStrikerPos.x - board.left) / board.width).coerceIn(0f, 1f)
            val rawCoinNormX = ((targetCoin.center.x - board.left) / board.width).coerceIn(0f, 1f)
            val rawCoinNormY = ((targetCoin.center.y - board.top) / board.height).coerceIn(0f, 1f)
            val movedSlider = turnInitialStrikerX != null && abs(preStrikerPos.x - turnInitialStrikerX) > 22f
            val (rawRowZone, coinColZone) = CarromMemoryData.coinZoneFor(targetCoin.center, board)

            // Normalize opponent's top-side perspective symmetrically into our bottom-side reference frame:
            // Top-Left (0) <-> Bottom-Left (2), Top-Right (1) <-> Bottom-Right (3), row -> (3 - row), normY -> (1 - normY)
            val normalizedPocketIdx = if (turnSide == WatchTurnSide.OPPONENT_TOP) {
                bestPocketIdx xor 2
            } else {
                bestPocketIdx
            }
            val normalizedRowZone = if (turnSide == WatchTurnSide.OPPONENT_TOP) {
                (3 - rawRowZone).coerceIn(0, 3)
            } else {
                rawRowZone
            }
            val normalizedCoinNormY = if (turnSide == WatchTurnSide.OPPONENT_TOP) {
                (1f - rawCoinNormY).coerceIn(0f, 1f)
            } else {
                rawCoinNormY
            }

            val aimedPocket = board.pockets[bestPocketIdx]
            val dirCp = (aimedPocket - targetCoin.center).normalized()
            val ghostBall = targetCoin.center - dirCp * (board.coinRadiusPx + board.strikerRadiusPx)
            val distRatio = (
                ((ghostBall - preStrikerPos).length() + (aimedPocket - targetCoin.center).length()) /
                    board.width.coerceAtLeast(1f)
                ).coerceIn(0.15f, 1.8f)

            val inferredPullScale = observedPowerHint ?: when (powerStyle) {
                CarromPowerStyle.SOFT -> 0.89f
                CarromPowerStyle.MEDIUM -> 1.00f
                CarromPowerStyle.HARD -> 1.12f
            }

            val coinR = board.coinRadiusPx.coerceAtLeast(10f)
            val corridorObs = mutableListOf<CorridorObstacleObservation>()
            var minClearanceRadii = 5.0f
            for (tr in transitions) {
                val obsCoin = tr.preCoin
                if (abs(obsCoin.center.x - targetCoin.center.x) < 2f &&
                    abs(obsCoin.center.y - targetCoin.center.y) < 2f
                ) {
                    continue
                }
                val wasDisplaced = tr.matchedPost == null || tr.displacementPx >= moveThreshold
                segmentProjection(obsCoin.center, preStrikerPos, ghostBall, coinR)?.let { (along, latR) ->
                    if (latR < minClearanceRadii) minClearanceRadii = latR
                    if (latR <= 3.4f) {
                        corridorObs.add(
                            CorridorObstacleObservation(
                                segment = "STRIKER_TO_COIN",
                                alongRatio = along,
                                lateralOffsetInRadii = latR,
                                displacedOrKissed = wasDisplaced,
                                obstacleColor = obsCoin.color
                            )
                        )
                    }
                }
                segmentProjection(obsCoin.center, targetCoin.center, aimedPocket, coinR)?.let { (along, latR) ->
                    if (latR < minClearanceRadii) minClearanceRadii = latR
                    if (latR <= 3.4f) {
                        corridorObs.add(
                            CorridorObstacleObservation(
                                segment = "COIN_TO_POCKET",
                                alongRatio = along,
                                lateralOffsetInRadii = latR,
                                displacedOrKissed = wasDisplaced,
                                obstacleColor = obsCoin.color
                            )
                        )
                    }
                }
            }

            val pending = PendingCarromShotRecord(
                myCoinColor = shooterCoinColor,
                targetCoinColor = targetCoin.color,
                pocketIndex = normalizedPocketIdx,
                cutAngleDeg = bestCutDeg,
                strikerNormalizedX = strikerNormX,
                coinRowZone = normalizedRowZone,
                coinColZone = coinColZone,
                powerStyle = powerStyle,
                movedStrikerWithSlider = movedSlider,
                isBankShot = false,
                isComboShot = false,
                isBreakShot = isBreakShot,
                preCounts = preCounts,
                isFreestyle = isFreestyle,
                timestampMs = nowMs,
                coinNormX = rawCoinNormX,
                coinNormY = normalizedCoinNormY,
                distRatio = distRatio,
                observedPullScale = inferredPullScale,
                minCorridorClearanceRadii = minClearanceRadii,
                corridorObstacles = corridorObs
            )

            val sidePrefix = if (turnSide == WatchTurnSide.OPPONENT_TOP) "[Opponent]" else "[You]"
            val pocketLabel = CarromMemoryData.pocketName(bestPocketIdx)
            val slideLabel = if (movedSlider) "Slider moved" else "In-place"
            val kissNote = if (corridorObs.any { it.displacedOrKissed && it.lateralOffsetInRadii <= 2.4f }) ", kiss" else ""
            val insight = "$sidePrefix ${targetCoin.color.name} -> $pocketLabel (cut=${bestCutDeg.toInt()}°, ${powerStyle.label}, $slideLabel$kissNote)"

            return InferredHumanShot(
                pendingShot = pending,
                postCounts = postCounts,
                observedPowerHint = observedPowerHint,
                humanInsightSummary = insight,
                shooterSide = turnSide
            )
        }

        private fun segmentProjection(
            point: Point2D,
            start: Point2D,
            end: Point2D,
            coinRadiusPx: Float
        ): Pair<Float, Float>? {
            val dx = end.x - start.x
            val dy = end.y - start.y
            val lenSq = dx * dx + dy * dy
            if (lenSq < 1f) return null
            val t = ((point.x - start.x) * dx + (point.y - start.y) * dy) / lenSq
            if (t < 0.04f || t > 0.96f) return null
            val projX = start.x + dx * t
            val projY = start.y + dy * t
            val latRadii = hypot(point.x - projX, point.y - projY) / coinRadiusPx
            return t to latRadii
        }

        private fun rayPointDistance(origin: Point2D, unitDir: Point2D, point: Point2D): Float {
            val v = point - origin
            val t = v.dot(unitDir).coerceAtLeast(0f)
            val closest = Point2D(origin.x + unitDir.x * t, origin.y + unitDir.y * t)
            return (point - closest).length()
        }
    }
}
