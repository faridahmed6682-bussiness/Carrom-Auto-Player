package com.example.game

import android.graphics.Bitmap
import android.graphics.Color
import com.example.agent.CarromDifficulty
import kotlin.math.abs

sealed interface CarromVisionOutcome {
    data class WaitStillnessOrTurn(
        val reason: String,
        val boardPieces: List<CarromPiece> = emptyList()
    ) : CarromVisionOutcome
    data class SolvedShot(
        val solution: CarromShotSolution,
        val detectedStriker: CarromPiece?,
        val board: CarromBoardLayout,
        val detectedSliderKnob: Point2D? = null,
        val boardPieces: List<CarromPiece> = emptyList(),
        // True when the "your turn" glow halo was seen on the bottom baseline.
        val turnBanner: Boolean = false
    ) : CarromVisionOutcome
    data class NeedsAiVision(
        val diagnostic: String,
        val boardPieces: List<CarromPiece> = emptyList()
    ) : CarromVisionOutcome
}

data class CarromWatchObservation(
    val board: CarromBoardLayout,
    val pieces: List<CarromPiece>,
    val striker: CarromPiece?,
    val sliderKnob: Point2D?,
    val lumaDiff: Float,
    val isInMotion: Boolean,
    val isLikelyGameBoard: Boolean,
    val topStriker: CarromPiece? = null
)

/**
 * Pure Kotlin on-device Carrom screenshot analyzer + offline board-motion detector.
 * Zero native OpenCV bloat (~15-35ms per frame). Falls back cleanly to AI Vision
 * whenever board confidence is low or screen is a lobby/menu.
 */
object CarromVisionDetector {

    private const val GRID_SIZE = 16
    private const val SAMPLE_STEP = 4
    private const val MOTION_THRESHOLD = 13.5f

    private var previousBoardLuma: IntArray? = null

    // Board-settled checker state: the promoted pieces of the previous
    // frame, and how many consecutive frames they stayed still.
    private var previousPromotedPieces: List<CarromPiece>? = null
    private var settledFrameCount: Int = 0

    fun resetMotionHistory() {
        previousBoardLuma = null
        previousPromotedPieces = null
        settledFrameCount = 0
    }

    /**
     * Vision-error guard (miss-cause learning): true when the promoted
     * pieces stayed still across the last 2 analysed frames. Only then
     * may a position-based outcome (miss cause from pre/post coin
     * positions) be trusted; a single-frame detection can misplace a
     * coin by a radius or more. Read right after analyzeAndSolve.
     */
    val isDetectionStableAcrossTwoFrames: Boolean get() = settledFrameCount >= 2

    /**
     * Board-settled check: same piece count, and every previous piece has a
     * same-colour piece within ~0.35 coin radii in the current frame.
     * Order-independent (detection order is not stable frame to frame).
     * A false "settled" only skips one motion wait — the solver still
     * verifies the shot with physics on the current frame.
     */
    private fun piecesSettled(
        prev: List<CarromPiece>,
        current: List<CarromPiece>,
        coinRadiusPx: Float
    ): Boolean {
        if (prev.size != current.size) return false
        val tolerance = coinRadiusPx * 0.35f
        return prev.all { p ->
            current.any { c ->
                c.color == p.color && (c.center - p.center).length() <= tolerance
            }
        }
    }

    fun analyzeAndSolve(
        screenshot: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        myCoinColor: CoinColor,
        consecutiveOfflineWaits: Int,
        shotMemory: CarromMemoryData? = null,
        allowStrikerRepositioning: Boolean = true,
        usePhysicsRollout: Boolean = true,
        useSubPixelRefinement: Boolean = true,
        isFreestyle: Boolean = false,
        queenPendingCover: Boolean = false,
        isLiveStream: Boolean = false,
        calibrationOverride: BoardCalibrationProfile? = null,
        isTurnActive: Boolean = false,
        difficulty: CarromDifficulty = CarromDifficulty.MEDIUM
    ): CarromVisionOutcome {
        if (screenshot == null || screenshot.width < 120 || screenshot.height < 120) {
            return CarromVisionOutcome.NeedsAiVision("No screenshot available for Carrom solver.")
        }

        val activeCal = calibrationOverride?.takeIf { it.enabled }
            ?: shotMemory?.boardCalibration?.takeIf { it.enabled }
        val scaleX = screenWidth.toFloat() / screenshot.width.toFloat()
        val scaleY = screenHeight.toFloat() / screenshot.height.toFloat()

        // Estimate or apply calibrated square Carrom board region
        val (boardBmLeft, boardBmTop, boardBmRight, boardBmBottom) = resolveBoardBitmapBounds(screenshot, activeCal)
        val boardBmWidth = (boardBmRight - boardBmLeft).coerceAtLeast(40)
        val boardBmHeight = (boardBmBottom - boardBmTop).coerceAtLeast(40)

        // Measure the real baseline line from the board print (see
        // detectBaselineLineY): the printed line sits at 0.805f on some
        // boards, not the 0.845f default — anchoring the striker to the
        // default puts it ~26 screen px too low and fails the A07
        // baseline test. An explicit calibration still wins; the measured
        // line only replaces the default.
        val measuredBaselineFraction = detectBaselineLineY(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardTop = boardBmTop,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom
        )?.let { ((it - boardBmTop) / boardBmHeight.toFloat()).coerceIn(0.62f, 0.95f) }

        // 1. Offline Motion / Stillness check on central playfield [0.16..0.76] (ignores pulsing baseline striker halo)
        val innerPadX = (boardBmWidth * 0.12f).toInt()
        val motionTop = (boardBmTop + (boardBmHeight * 0.16f).toInt()).coerceIn(0, screenshot.height - 2)
        val motionBottom = (boardBmTop + (boardBmHeight * 0.76f).toInt()).coerceIn(motionTop + 1, screenshot.height - 1)
        val currentLuma = sampleBoardLumaGrid(
            bitmap = screenshot,
            left = (boardBmLeft + innerPadX).coerceIn(0, screenshot.width - 2),
            top = motionTop,
            right = (boardBmRight - innerPadX).coerceIn(1, screenshot.width - 1),
            bottom = motionBottom
        )
        val prevLuma = previousBoardLuma
        previousBoardLuma = currentLuma

        val effectiveMotionThreshold = if (allowStrikerRepositioning) MOTION_THRESHOLD else MOTION_THRESHOLD * 1.55f
        val maxMotionWaits = if (isLiveStream) 4 else 2
        if (!isTurnActive && prevLuma != null && consecutiveOfflineWaits < maxMotionWaits) {
            val meanDiff = computeMeanAbsDiff(prevLuma, currentLuma)
            // Settled-board fast path: the promoted pieces were stable
            // across the last two frames, so the board is still — the luma
            // change is the pulsing turn glow / lighting, not moving coins.
            // Proceed immediately instead of waiting blindly for the luma
            // grid to quiet down.
            if (meanDiff > effectiveMotionThreshold && settledFrameCount < 1) {
                return CarromVisionOutcome.WaitStillnessOrTurn(
                    "Board motion detected (diff=${meanDiff.toInt()}) — waiting offline for coins to settle (0 API)."
                )
            }
        }

        val baselineFraction = activeCal?.baselineYNorm ?: measuredBaselineFraction ?: 0.845f

        // 1b. Turn-glow centre (cheap, skin-independent): the green halo
        // around the bottom striker. Computed early so the knob decision
        // and the striker anchor can use it as a cross-check.
        val glowCenter = detectBottomTurnGlowCenter(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardTop = boardBmTop,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom,
            baselineFraction = baselineFraction
        )
        val glowX = glowCenter.center?.x

        // 2. Detect bottom slider knob (uses calibrated slider track band if enabled)
        val sliderKnobPx = detectBottomSliderKnob(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            calibration = activeCal,
            glowCenterX = glowX
        )

        val boardLeft = boardBmLeft * scaleX
        val boardTop = boardBmTop * scaleY
        val boardRight = boardBmRight * scaleX
        val boardBottom = boardBmBottom * scaleY
        val boardWidthPx = (boardRight - boardLeft).coerceAtLeast(100f)
        val boardHeightPx = (boardBottom - boardTop).coerceAtLeast(100f)

        val coinRadius = boardWidthPx * (activeCal?.coinRadiusRatio ?: 0.029f)
        val strikerRadius = boardWidthPx * (activeCal?.strikerRadiusRatio ?: 0.036f)
        val pocketRadius = boardWidthPx * 0.052f
        val pocketInset = boardWidthPx * 0.064f

        // 3. Scan board region into clustered pieces (using Hough Circle separation for touching break clusters)
        val rawPieces = detectBoardPieces(
            bitmap = screenshot,
            left = boardBmLeft,
            top = boardBmTop,
            right = boardBmRight,
            bottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            expectedCoinRadiusPx = coinRadius,
            useSubPixelRefinement = useSubPixelRefinement,
            baselineFraction = baselineFraction,
            calibration = activeCal
        )

        val pockets = if (activeCal != null && activeCal.pocketsNorm.size == 4) {
            activeCal.resolvedPocketsScreen(screenWidth.toFloat(), screenHeight.toFloat())
        } else {
            listOf(
                Point2D(boardLeft + pocketInset, boardTop + pocketInset),
                Point2D(boardRight - pocketInset, boardTop + pocketInset),
                Point2D(boardLeft + pocketInset, boardBottom - pocketInset),
                Point2D(boardRight - pocketInset, boardBottom - pocketInset)
            )
        }
        val defaultBaselineY = boardTop + boardHeightPx * baselineFraction

        // 4. Striker anchor vote (skin-independent): the slider knob x, the
        // turn-glow centroid x, and the nearest STRIKER-coloured fragment
        // vote on the striker's x. Two agreeing signals win; the knob is
        // the exact slider mapping, so it wins ties. A yellow board marker
        // ~185px away can never vote (0.06 * board width window).
        val appearanceVoteX: Float? = run {
            val refX = glowX ?: sliderKnobPx?.x ?: return@run null
            rawPieces
                .filter { it.color == CoinColor.STRIKER && it.radiusPx >= strikerRadius * 0.58f }
                .minByOrNull { p -> abs(p.center.x - refX) }
                ?.takeIf { p -> abs(p.center.x - refX) <= boardWidthPx * 0.06f }
                ?.center?.x
        }
        val anchorX = resolveStrikerAnchorX(
            sliderKnobPx = sliderKnobPx,
            glowCenterX = glowX,
            appearanceX = appearanceVoteX,
            boardWidthPx = boardWidthPx
        )

        // 4b. Color-agnostic bottom baseline striker detection (matches ANY striker color/skin/halo)
        val rawStrikerAligned = rawPieces
            .filter { it.color == CoinColor.STRIKER && it.radiusPx >= strikerRadius * 0.58f }
            .minByOrNull { p ->
                if (anchorX != null) abs(p.center.x - anchorX)
                else if (sliderKnobPx != null) abs(p.center.x - sliderKnobPx.x)
                else abs(p.center.y - defaultBaselineY)
            }
            ?.takeIf { p ->
                if (anchorX != null) abs(p.center.x - anchorX) <= boardWidthPx * 0.06f
                else sliderKnobPx == null || abs(p.center.x - sliderKnobPx.x) <= boardWidthPx * 0.18f
            }

        val scanBandTop = (baselineFraction - 0.145f).coerceIn(0.62f, 0.85f)
        val scanBandBottom = (baselineFraction + 0.095f).coerceIn(scanBandTop + 0.08f, 0.96f)
        val contrastStriker = if (rawStrikerAligned == null) {
            detectAnyStrikerOnBaseline(
                bitmap = screenshot,
                boardLeft = boardBmLeft,
                boardTop = boardBmTop,
                boardRight = boardBmRight,
                boardBottom = boardBmBottom,
                scaleX = scaleX,
                scaleY = scaleY,
                strikerRadiusPx = strikerRadius,
                sliderKnobPx = sliderKnobPx,
                scanTopFraction = scanBandTop,
                scanBottomFraction = scanBandBottom
            )
        } else {
            null
        }

        val prelimStrikerRaw = contrastStriker ?: rawStrikerAligned
        val prelimStriker = if (prelimStrikerRaw != null && useSubPixelRefinement) {
            val refinedCenter = CarromCircleRefiner.refinePieceCenterSubPixel(
                bitmap = screenshot,
                coarseCenterScreen = prelimStrikerRaw.center,
                expectedRadiusScreen = strikerRadius,
                scaleX = scaleX,
                scaleY = scaleY
            )
            prelimStrikerRaw.copy(center = refinedCenter)
        } else {
            prelimStrikerRaw
        }
        val cleanedPieces = if (prelimStriker != null) {
            val clearR = strikerRadius * 1.40f
            rawPieces.filter { p ->
                p.color != CoinColor.STRIKER && (p.center - prelimStriker.center).length() > clearR
            } + prelimStriker
        } else {
            // Drop unaligned bogus baseline-band STRIKER noise when sliderKnobPx disagrees
            if (sliderKnobPx != null) rawPieces.filter { it.color != CoinColor.STRIKER } else rawPieces
        }

        val promoted = promoteOrAnchorStriker(
            pieces = cleanedPieces,
            baselineY = defaultBaselineY,
            boardLeft = boardLeft,
            boardRight = boardRight,
            boardHeightPx = boardHeightPx,
            strikerRadiusPx = strikerRadius,
            sliderKnobPx = sliderKnobPx,
            anchorStrikerX = anchorX,
            coinRadiusPx = coinRadius,
            // Face-fragment cleaning only when the anchor is trusted: the
            // knob is present and the glow does not actively disagree.
            cleanFaceFragments = sliderKnobPx != null &&
                (glowX == null || abs(sliderKnobPx.x - glowX) <= boardWidthPx * 0.10f)
        )

        if (promoted.isEmpty()) {
            return CarromVisionOutcome.NeedsAiVision(
                diagnostic = "Detected 0 Carrom pieces (slider=${sliderKnobPx != null}); delegating to AI Vision.",
                boardPieces = promoted
            )
        }

        // Board-settled checker: compare this frame's promoted pieces with
        // the previous frame's. Same count, same colours, every centre
        // within ~0.35 coin radii -> the board is still, even if the luma
        // grid disagrees (turn glow / lighting changes luma, not piece
        // positions). Two consecutive stable frames mark the board settled,
        // and the NEXT call skips the blind luma motion wait above.
        val prevPromoted = previousPromotedPieces
        previousPromotedPieces = promoted
        settledFrameCount = if (prevPromoted != null && piecesSettled(prevPromoted, promoted, strikerRadius)) {
            (settledFrameCount + 1).coerceAtMost(3)
        } else {
            0
        }

        val strikerPiece = promoted.firstOrNull { it.color == CoinColor.STRIKER }
        // Fallback striker: when our striker/slider cannot be seen on the bottom
        // baseline but no opponent striker is visible on the top baseline either,
        // the "whose turn is it" signal itself is unreliable. The old code waited
        // out the budget and then returned NeedsAiVision here, which the
        // coordinator turns into an endless WAIT whenever no AI-Vision key is
        // configured — so the striker sat completely still forever and the auto
        // turn never started. After the wait budget, assume it IS our turn and
        // play from the baseline centre instead of sitting still.
        var fallbackStriker: CarromPiece? = null
        var turnBannerDetected = false
        if (strikerPiece == null && sliderKnobPx == null) {
            val detectedTopStriker = detectTopStrikerOnBaseline(
                bitmap = screenshot,
                boardLeft = boardBmLeft,
                boardTop = boardBmTop,
                boardRight = boardBmRight,
                boardBottom = boardBmBottom,
                scaleX = scaleX,
                scaleY = scaleY,
                strikerRadiusPx = strikerRadius,
                useSubPixelRefinement = false
            )
            // Second turn signal: Carrom Pool draws a pulsing green/yellow
            // "your turn" glow halo around the bottom striker while it is OUR
            // turn. If the halo is visible and no opponent striker is on the
            // top baseline, it is our turn right now — do not burn the wait
            // budget, play immediately. This only accelerates the fallback
            // assumption below; it never fires a shot by itself.
            // Glow already scanned up front; reuse it (no second scan).
            turnBannerDetected = detectedTopStriker == null && glowCenter.detected
            val maxTurnWaitCycles = when {
                turnBannerDetected -> 0
                detectedTopStriker != null -> if (isLiveStream) 36 else 12
                else -> if (isLiveStream) 9 else 3
            }
            if (consecutiveOfflineWaits < maxTurnWaitCycles) {
                val reason = if (detectedTopStriker != null) {
                    "Opponent striker confirmed on top baseline — waiting offline for our turn (0 API)."
                } else {
                    "Striker/slider not on bottom baseline (likely opponent turn) — waiting offline (0 API)."
                }
                return CarromVisionOutcome.WaitStillnessOrTurn(
                    reason = reason,
                    boardPieces = promoted
                )
            }
            val hasTargetCoin = promoted.any {
                it.color != CoinColor.STRIKER &&
                    (isFreestyle || it.color == myCoinColor || it.color == CoinColor.QUEEN)
            }
            if (detectedTopStriker == null && hasTargetCoin) {
                // No opponent striker seen and a pottable coin is visible: synthesize
                // our striker at the baseline centre so the solver can take a shot.
                fallbackStriker = CarromPiece(
                    CoinColor.STRIKER,
                    Point2D((boardLeft + boardRight) / 2f, defaultBaselineY),
                    strikerRadius
                )
            } else {
                return CarromVisionOutcome.NeedsAiVision(
                    diagnostic = "Striker not matched locally (${promoted.size} coins visible, no slider knob); delegating shot to AI Vision.",
                    boardPieces = promoted
                )
            }
        }

        val effectiveStriker = strikerPiece ?: sliderKnobPx?.let { knob ->
            val margin = strikerRadius * 2.1f
            val mappedX = knob.x.coerceIn(boardLeft + margin, boardRight - margin)
            CarromPiece(CoinColor.STRIKER, Point2D(mappedX, defaultBaselineY), strikerRadius)
        } ?: fallbackStriker
        val calibratedBaselineY = effectiveStriker?.center?.y ?: defaultBaselineY
        val boardLayout = CarromBoardLayout(
            left = boardLeft,
            top = boardTop,
            right = boardRight,
            bottom = boardBottom,
            pockets = pockets,
            baselineY = calibratedBaselineY,
            coinRadiusPx = coinRadius,
            strikerRadiusPx = strikerRadius,
            pocketRadiusPx = pocketRadius
        )

        val solution = CarromGeometrySolver.solveBestShot(
            board = boardLayout,
            pieces = promoted,
            myCoinColor = myCoinColor,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            existingStriker = effectiveStriker,
            canRepositionStriker = allowStrikerRepositioning,
            shotMemory = shotMemory,
            usePhysicsRollout = usePhysicsRollout,
            isFreestyle = isFreestyle,
            queenPendingCover = queenPendingCover,
            difficulty = difficulty
        )

        // Difficulty shifts the acceptance bar for the first solution, but a
        // physics-verified sure shot is always accepted regardless of level.
        val diffOffset = when (difficulty) {
            CarromDifficulty.EASY -> -0.12f
            CarromDifficulty.MEDIUM -> 0f
            CarromDifficulty.HARD -> 0.08f
        }
        val acceptConf = (0.45f + diffOffset).coerceIn(0.30f, 0.55f)
        val finalSolution = if (solution != null && (solution.confidenceScore >= acceptConf || solution.isSureShot)) {
            solution
        } else if (effectiveStriker != null && promoted.count { it.color != CoinColor.STRIKER } >= 1) {
            // Own striker or slider is confirmed on bottom baseline: ALWAYS solve safest/emergency shot within 5s rather than stalling
            CarromGeometrySolver.solveSafestBreakOrPotShot(
                board = boardLayout,
                pieces = promoted,
                myCoinColor = myCoinColor,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                existingStriker = effectiveStriker,
                shotMemory = shotMemory,
                usePhysicsRollout = usePhysicsRollout,
                isFreestyle = isFreestyle,
                queenPendingCover = queenPendingCover,
                difficulty = difficulty
            ) ?: solution
        } else {
            solution
        }

        val minAcceptConf = if (effectiveStriker != null) 0.15f else (0.48f + diffOffset).coerceIn(0.30f, 0.56f)
        return if (finalSolution != null && (finalSolution.confidenceScore >= minAcceptConf || finalSolution.isSureShot)) {
            CarromVisionOutcome.SolvedShot(
                solution = finalSolution,
                detectedStriker = effectiveStriker,
                board = boardLayout,
                detectedSliderKnob = sliderKnobPx,
                boardPieces = promoted,
                turnBanner = turnBannerDetected
            )
        } else {
            val myTargetsCount = if (isFreestyle) {
                promoted.count { it.color != CoinColor.STRIKER }
            } else {
                promoted.count { it.color == myCoinColor || it.color == CoinColor.QUEEN }
            }
            CarromVisionOutcome.NeedsAiVision(
                diagnostic = "No offline shot (targets=$myTargetsCount, total=${promoted.size}, striker=${effectiveStriker != null}); using AI Vision.",
                boardPieces = promoted
            )
        }
    }

    fun autoDetectCalibrationFromBitmap(bitmap: Bitmap): BoardCalibrationProfile {
        val w = bitmap.width.coerceAtLeast(1).toFloat()
        val h = bitmap.height.coerceAtLeast(1).toFloat()
        val bounds = estimateBoardBitmapBounds(bitmap)
        val bw = (bounds.right - bounds.left).coerceAtLeast(40).toFloat()
        val bh = (bounds.bottom - bounds.top).coerceAtLeast(40).toFloat()
        val pocketCentersBm = detectFourCornerPockets(bitmap, bounds)
        val pocketsNorm = pocketCentersBm.map { pt ->
            Point2D((pt.x / w).coerceIn(0f, 1f), (pt.y / h).coerceIn(0f, 1f))
        }
        val sliderKnob = detectBottomSliderKnob(
            bitmap = bitmap,
            boardLeft = bounds.left,
            boardRight = bounds.right,
            boardBottom = bounds.bottom,
            scaleX = 1f,
            scaleY = 1f
        )
        val sliderTrackYNorm = if (sliderKnob != null) {
            (sliderKnob.y / h).coerceIn(0.55f, 0.95f)
        } else {
            ((bounds.bottom / h) + 0.085f).coerceIn(0.62f, 0.92f)
        }
        return BoardCalibrationProfile(
            enabled = true,
            boardLeftNorm = (bounds.left / w).coerceIn(0f, 0.45f),
            boardTopNorm = (bounds.top / h).coerceIn(0f, 0.70f),
            boardRightNorm = (bounds.right / w).coerceIn(0.55f, 1f),
            boardBottomNorm = (bounds.bottom / h).coerceIn(0.30f, 1f),
            pocketsNorm = pocketsNorm,
            baselineYNorm = 0.845f,
            sliderTrackYNorm = sliderTrackYNorm,
            sliderTrackLeftNorm = ((bounds.left + bw * 0.14f) / w).coerceIn(0.05f, 0.45f),
            sliderTrackRightNorm = ((bounds.right - bw * 0.14f) / w).coerceIn(0.55f, 0.95f)
        )
    }

    fun runAimDebugPreview(
        bitmap: Bitmap,
        calibration: BoardCalibrationProfile,
        myCoinColor: CoinColor,
        isFreestyle: Boolean = false
    ): CarromVisionOutcome {
        val savedLuma = previousBoardLuma
        val savedPromoted = previousPromotedPieces
        val savedSettled = settledFrameCount
        previousBoardLuma = null
        previousPromotedPieces = null
        settledFrameCount = 0
        return try {
            analyzeAndSolve(
                screenshot = bitmap,
                screenWidth = bitmap.width,
                screenHeight = bitmap.height,
                myCoinColor = myCoinColor,
                consecutiveOfflineWaits = 0,
                shotMemory = CarromMemoryData(boardCalibration = calibration.copy(enabled = true)),
                allowStrikerRepositioning = true,
                usePhysicsRollout = true,
                useSubPixelRefinement = true,
                isFreestyle = isFreestyle,
                calibrationOverride = calibration.copy(enabled = true)
            )
        } finally {
            previousBoardLuma = savedLuma
            previousPromotedPieces = savedPromoted
            settledFrameCount = savedSettled
        }
    }

    private fun detectFourCornerPockets(bitmap: Bitmap, bounds: BoardBmBounds): List<Point2D> {
        val bw = (bounds.right - bounds.left).coerceAtLeast(40).toFloat()
        val bh = (bounds.bottom - bounds.top).coerceAtLeast(40).toFloat()
        val insetRatio = 0.064f
        val cornerAnchors = listOf(
            Point2D(bounds.left + bw * insetRatio, bounds.top + bh * insetRatio),
            Point2D(bounds.right - bw * insetRatio, bounds.top + bh * insetRatio),
            Point2D(bounds.left + bw * insetRatio, bounds.bottom - bh * insetRatio),
            Point2D(bounds.right - bw * insetRatio, bounds.bottom - bh * insetRatio)
        )
        val searchR = (bw * 0.055f).toInt().coerceIn(8, 36)
        return cornerAnchors.map { anchor ->
            val minX = (anchor.x.toInt() - searchR).coerceIn(0, bitmap.width - 1)
            val maxX = (anchor.x.toInt() + searchR).coerceIn(minX, bitmap.width - 1)
            val minY = (anchor.y.toInt() - searchR).coerceIn(0, bitmap.height - 1)
            val maxY = (anchor.y.toInt() + searchR).coerceIn(minY, bitmap.height - 1)
            var sumX = 0f
            var sumY = 0f
            var weightSum = 0f
            for (y in minY..maxY step 2) {
                for (x in minX..maxX step 2) {
                    val p = bitmap.getPixel(x, y)
                    val luma = Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
                    // Darkest pocket interior gets strongest pull
                    if (luma <= 68f) {
                        val w = (72f - luma).coerceAtLeast(1f)
                        sumX += x * w
                        sumY += y * w
                        weightSum += w
                    }
                }
            }
            if (weightSum >= 40f) {
                Point2D(sumX / weightSum, sumY / weightSum)
            } else {
                anchor
            }
        }
    }

    private fun resolveBoardBitmapBounds(
        bitmap: Bitmap,
        calibration: BoardCalibrationProfile?
    ): BoardBmBounds {
        if (calibration == null || !calibration.enabled) {
            return estimateBoardBitmapBounds(bitmap)
        }
        val w = bitmap.width
        val h = bitmap.height
        val left = (calibration.boardLeftNorm * w).toInt().coerceIn(0, (w - 40).coerceAtLeast(0))
        val top = (calibration.boardTopNorm * h).toInt().coerceIn(0, (h - 40).coerceAtLeast(0))
        val right = (calibration.boardRightNorm * w).toInt().coerceIn((left + 40).coerceAtMost(w - 1), w - 1)
        val bottom = (calibration.boardBottomNorm * h).toInt().coerceIn((top + 40).coerceAtMost(h - 1), h - 1)
        return BoardBmBounds(left = left, top = top, right = right, bottom = bottom)
    }

    fun inspectBoardCounts(
        screenshot: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        myCoinColor: CoinColor,
        calibration: BoardCalibrationProfile? = null
    ): BoardPieceCounts? {
        if (screenshot == null || screenshot.width < 120 || screenshot.height < 120) return null
        val activeCal = calibration?.takeIf { it.enabled }
        val scaleX = screenWidth.toFloat() / screenshot.width.toFloat()
        val scaleY = screenHeight.toFloat() / screenshot.height.toFloat()
        val bounds = resolveBoardBitmapBounds(screenshot, activeCal)
        val expectedCoinR = ((bounds.right - bounds.left) * scaleX * (activeCal?.coinRadiusRatio ?: 0.029f)).coerceAtLeast(16f)
        val raw = detectBoardPieces(
            bitmap = screenshot,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            scaleX = scaleX,
            scaleY = scaleY,
            expectedCoinRadiusPx = expectedCoinR,
            useSubPixelRefinement = false,
            baselineFraction = activeCal?.baselineYNorm ?: 0.845f,
            calibration = activeCal
        )
        if (raw.isEmpty()) return null
        return CarromShotMemoryStore.countBoardPieces(raw, myCoinColor)
    }

    fun observeBoardForTraining(
        screenshot: Bitmap?,
        screenWidth: Int,
        screenHeight: Int,
        calibration: BoardCalibrationProfile? = null
    ): CarromWatchObservation? {
        if (screenshot == null || screenshot.width < 120 || screenshot.height < 120) return null

        val activeCal = calibration?.takeIf { it.enabled }
        val scaleX = screenWidth.toFloat() / screenshot.width.toFloat()
        val scaleY = screenHeight.toFloat() / screenshot.height.toFloat()
        val bounds = resolveBoardBitmapBounds(screenshot, activeCal)
        val boardBmLeft = bounds.left
        val boardBmTop = bounds.top
        val boardBmRight = bounds.right
        val boardBmBottom = bounds.bottom
        val boardBmWidth = (boardBmRight - boardBmLeft).coerceAtLeast(40)
        val boardBmHeight = (boardBmBottom - boardBmTop).coerceAtLeast(40)

        // Measure the real baseline line from the board print (same as
        // analyzeAndSolve): an explicit calibration still wins; the
        // measured line only replaces the 0.845f default.
        val measuredBaselineFraction = detectBaselineLineY(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardTop = boardBmTop,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom
        )?.let { ((it - boardBmTop) / boardBmHeight.toFloat()).coerceIn(0.62f, 0.95f) }

        val innerPadX = (boardBmWidth * 0.12f).toInt()
        val motionTop = (boardBmTop + (boardBmHeight * 0.16f).toInt()).coerceIn(0, screenshot.height - 2)
        val motionBottom = (boardBmTop + (boardBmHeight * 0.76f).toInt()).coerceIn(motionTop + 1, screenshot.height - 1)
        val currentLuma = sampleBoardLumaGrid(
            bitmap = screenshot,
            left = (boardBmLeft + innerPadX).coerceIn(0, screenshot.width - 2),
            top = motionTop,
            right = (boardBmRight - innerPadX).coerceIn(1, screenshot.width - 1),
            bottom = motionBottom
        )
        val prevLuma = previousBoardLuma
        previousBoardLuma = currentLuma
        val meanDiff = if (prevLuma != null) computeMeanAbsDiff(prevLuma, currentLuma) else 0f
        val isInMotion = prevLuma != null && meanDiff > (MOTION_THRESHOLD * 0.90f)

        val baselineFraction = activeCal?.baselineYNorm ?: measuredBaselineFraction ?: 0.845f

        // Turn-glow centre, computed early (same as analyzeAndSolve): the
        // knob decision and the striker anchor use it as a cross-check.
        val glowCenter = detectBottomTurnGlowCenter(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardTop = boardBmTop,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom,
            baselineFraction = baselineFraction
        )
        val glowX = glowCenter.center?.x

        val sliderKnobPx = detectBottomSliderKnob(
            bitmap = screenshot,
            boardLeft = boardBmLeft,
            boardRight = boardBmRight,
            boardBottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            calibration = activeCal,
            glowCenterX = glowX
        )

        val boardLeft = boardBmLeft * scaleX
        val boardTop = boardBmTop * scaleY
        val boardRight = boardBmRight * scaleX
        val boardBottom = boardBmBottom * scaleY
        val boardWidthPx = (boardRight - boardLeft).coerceAtLeast(100f)
        val boardHeightPx = (boardBottom - boardTop).coerceAtLeast(100f)
        val coinRadius = boardWidthPx * (activeCal?.coinRadiusRatio ?: 0.029f)
        val strikerRadius = boardWidthPx * (activeCal?.strikerRadiusRatio ?: 0.036f)
        val pocketRadius = boardWidthPx * 0.052f
        val pocketInset = boardWidthPx * 0.064f

        val rawPieces = detectBoardPieces(
            bitmap = screenshot,
            left = boardBmLeft,
            top = boardBmTop,
            right = boardBmRight,
            bottom = boardBmBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            expectedCoinRadiusPx = coinRadius,
            useSubPixelRefinement = !isInMotion,
            baselineFraction = baselineFraction,
            calibration = activeCal
        )

        val defaultBaselineY = boardTop + boardHeightPx * baselineFraction
        // Striker anchor vote (same as analyzeAndSolve): knob x + glow
        // centroid x + nearest STRIKER fragment vote on the striker's x.
        val appearanceVoteX: Float? = run {
            val refX = glowX ?: sliderKnobPx?.x ?: return@run null
            rawPieces
                .filter { it.color == CoinColor.STRIKER && it.radiusPx >= strikerRadius * 0.58f }
                .minByOrNull { p -> abs(p.center.x - refX) }
                ?.takeIf { p -> abs(p.center.x - refX) <= boardWidthPx * 0.06f }
                ?.center?.x
        }
        val anchorX = resolveStrikerAnchorX(
            sliderKnobPx = sliderKnobPx,
            glowCenterX = glowX,
            appearanceX = appearanceVoteX,
            boardWidthPx = boardWidthPx
        )
        val rawStrikerAligned = rawPieces
            .filter { it.color == CoinColor.STRIKER && it.radiusPx >= strikerRadius * 0.58f }
            .minByOrNull { p ->
                if (anchorX != null) abs(p.center.x - anchorX)
                else if (sliderKnobPx != null) abs(p.center.x - sliderKnobPx.x)
                else abs(p.center.y - defaultBaselineY)
            }
            ?.takeIf { p ->
                if (anchorX != null) abs(p.center.x - anchorX) <= boardWidthPx * 0.06f
                else sliderKnobPx == null || abs(p.center.x - sliderKnobPx.x) <= boardWidthPx * 0.18f
            }
        val scanBandTop = (baselineFraction - 0.145f).coerceIn(0.62f, 0.85f)
        val scanBandBottom = (baselineFraction + 0.095f).coerceIn(scanBandTop + 0.08f, 0.96f)
        val contrastStriker = if (rawStrikerAligned == null) {
            detectAnyStrikerOnBaseline(
                bitmap = screenshot,
                boardLeft = boardBmLeft,
                boardTop = boardBmTop,
                boardRight = boardBmRight,
                boardBottom = boardBmBottom,
                scaleX = scaleX,
                scaleY = scaleY,
                strikerRadiusPx = strikerRadius,
                sliderKnobPx = sliderKnobPx,
                scanTopFraction = scanBandTop,
                scanBottomFraction = scanBandBottom
            )
        } else {
            null
        }
        val prelimStriker = contrastStriker ?: rawStrikerAligned
        val cleanedPieces = if (prelimStriker != null) {
            val clearR = strikerRadius * 1.40f
            rawPieces.filter { p ->
                p.color != CoinColor.STRIKER && (p.center - prelimStriker.center).length() > clearR
            } + prelimStriker
        } else {
            if (sliderKnobPx != null) rawPieces.filter { it.color != CoinColor.STRIKER } else rawPieces
        }
        val promoted = promoteOrAnchorStriker(
            pieces = cleanedPieces,
            baselineY = defaultBaselineY,
            boardLeft = boardLeft,
            boardRight = boardRight,
            boardHeightPx = boardHeightPx,
            strikerRadiusPx = strikerRadius,
            sliderKnobPx = sliderKnobPx,
            anchorStrikerX = anchorX,
            coinRadiusPx = coinRadius,
            cleanFaceFragments = sliderKnobPx != null &&
                (glowX == null || abs(sliderKnobPx.x - glowX) <= boardWidthPx * 0.10f)
        )
        val strikerPiece = promoted.firstOrNull { it.color == CoinColor.STRIKER }
        // Only during opponent's turn (bottom striker & slider absent), detect opponent striker on top baseline
        val detectedTopStriker = if (strikerPiece == null && sliderKnobPx == null) {
            detectTopStrikerOnBaseline(
                bitmap = screenshot,
                boardLeft = boardBmLeft,
                boardTop = boardBmTop,
                boardRight = boardBmRight,
                boardBottom = boardBmBottom,
                scaleX = scaleX,
                scaleY = scaleY,
                strikerRadiusPx = strikerRadius,
                useSubPixelRefinement = !isInMotion
            )
        } else {
            null
        }
        val finalWatchPieces = if (detectedTopStriker != null) {
            val clearR = strikerRadius * 1.40f
            promoted.filter { p ->
                (p.center - detectedTopStriker.center).length() > clearR
            }
        } else {
            promoted
        }
        val calibratedBaselineY = strikerPiece?.center?.y ?: defaultBaselineY
        val pockets = if (activeCal != null && activeCal.pocketsNorm.size == 4) {
            activeCal.resolvedPocketsScreen(screenWidth.toFloat(), screenHeight.toFloat())
        } else {
            listOf(
                Point2D(boardLeft + pocketInset, boardTop + pocketInset),
                Point2D(boardRight - pocketInset, boardTop + pocketInset),
                Point2D(boardLeft + pocketInset, boardBottom - pocketInset),
                Point2D(boardRight - pocketInset, boardBottom - pocketInset)
            )
        }
        val boardLayout = CarromBoardLayout(
            left = boardLeft,
            top = boardTop,
            right = boardRight,
            bottom = boardBottom,
            pockets = pockets,
            baselineY = calibratedBaselineY,
            coinRadiusPx = coinRadius,
            strikerRadiusPx = strikerRadius,
            pocketRadiusPx = pocketRadius
        )
        val nonStrikerCount = finalWatchPieces.count { it.color != CoinColor.STRIKER }
        return CarromWatchObservation(
            board = boardLayout,
            pieces = finalWatchPieces,
            striker = strikerPiece,
            sliderKnob = sliderKnobPx,
            lumaDiff = meanDiff,
            isInMotion = isInMotion,
            isLikelyGameBoard = nonStrikerCount >= 2,
            topStriker = detectedTopStriker
        )
    }

    private fun detectTopStrikerOnBaseline(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int,
        scaleX: Float,
        scaleY: Float,
        strikerRadiusPx: Float,
        useSubPixelRefinement: Boolean
    ): CarromPiece? {
        val rawTop = detectVividStrikerOnBand(
            bitmap = bitmap,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            baselineFraction = 0.155f,
            strikerRadiusPx = strikerRadiusPx,
            useSubPixelRefinement = useSubPixelRefinement
        ) ?: detectAnyStrikerOnBaseline(
            bitmap = bitmap,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            scaleX = scaleX,
            scaleY = scaleY,
            strikerRadiusPx = strikerRadiusPx,
            sliderKnobPx = null,
            scanTopFraction = 0.07f,
            scanBottomFraction = 0.24f
        )
        return if (rawTop != null && useSubPixelRefinement) {
            rawTop.copy(
                center = CarromCircleRefiner.refinePieceCenterSubPixel(
                    bitmap = bitmap,
                    coarseCenterScreen = rawTop.center,
                    expectedRadiusScreen = strikerRadiusPx,
                    scaleX = scaleX,
                    scaleY = scaleY
                )
            )
        } else {
            rawTop
        }
    }

    private data class BoardBmBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    private fun estimateBoardBitmapBounds(bitmap: Bitmap): BoardBmBounds {
        val w = bitmap.width
        val h = bitmap.height
        val isLandscape = w > h

        if (isLandscape) {
            val side = (h * 0.84f).toInt().coerceAtLeast(40)
            val left = ((w - side) / 2).coerceIn(0, w - 40)
            val top = ((h - side) / 2).coerceIn(0, h - 40)
            return BoardBmBounds(
                left = left,
                top = top,
                right = (left + side).coerceAtMost(w - 1),
                bottom = (top + side).coerceAtMost(h - 1)
            )
        }

        val left = (w * 0.055f).toInt()
        val right = (w * 0.945f).toInt()
        val boardW = (right - left).coerceAtLeast(40)
        val defaultTop = ((h - boardW) * 0.48f).toInt().coerceIn(0, h - 40)

        // Refine top within +-12% of screen height using symmetric top + bottom board border contrast
        val xStart = left + (boardW * 0.25f).toInt()
        val xEnd = right - (boardW * 0.25f).toInt()
        val searchMinY = (defaultTop - (h * 0.12f).toInt()).coerceAtLeast(0)
        val searchMaxY = (defaultTop + (h * 0.12f).toInt()).coerceAtMost(h - boardW - 1).coerceAtLeast(searchMinY)

        fun avgRowLuma(py: Int): Float {
            val clampedY = py.coerceIn(0, h - 1)
            var rowLuma = 0
            var count = 0
            var px = xStart
            while (px <= xEnd) {
                val p = bitmap.getPixel(px, clampedY)
                rowLuma += (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                count++
                px += 12
            }
            return if (count > 0) rowLuma.toFloat() / count else 0f
        }

        val edgePadIn = (boardW * 0.08f).toInt().coerceAtLeast(10)
        val edgePadOut = (boardW * 0.045f).toInt().coerceAtLeast(10)

        fun scoreCandidate(candidateTop: Int): Float {
            val topContrast = avgRowLuma(candidateTop + edgePadIn) - avgRowLuma(candidateTop - edgePadOut)
            val bottomContrast = avgRowLuma(candidateTop + boardW - edgePadIn) - avgRowLuma(candidateTop + boardW + edgePadOut)
            return minOf(topContrast, bottomContrast) + (topContrast + bottomContrast) * 0.5f
        }

        val defaultScore = scoreCandidate(defaultTop)
        var bestTop = defaultTop
        var bestScore = defaultScore
        var y = searchMinY
        while (y <= searchMaxY) {
            val score = scoreCandidate(y)
            if (score > bestScore) {
                bestScore = score
                bestTop = y
            }
            y += 6
        }

        val finalTop = if (bestScore > defaultScore + 8f && bestScore > 16f) bestTop else defaultTop
        val finalBottom = (finalTop + boardW).coerceAtMost(h - 1)
        return BoardBmBounds(left = left, top = finalTop, right = right, bottom = finalBottom)
    }

    /**
     * Measure the board's real bottom-baseline Y from the printed dark
     * horizontal baseline line (with its two end circles), instead of
     * assuming the 0.845f default. The line is the longest dark run in
     * the lower board band (0.70..0.92 of board height); a coin row can
     * only make a ~1-disc run, and the small cyan deco spot some boards
     * paint near 0.845f is far too short to win. Returns the line centre
     * in bitmap pixels, or null when no convincing line is found (the
     * caller then keeps the default). A decorated striker sitting on the
     * line does not break it: the disc is dark too, only its light
     * centre interrupts the run, and both halves still clear the bar.
     */
    private fun detectBaselineLineY(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int
    ): Float? {
        val bw = (boardRight - boardLeft).coerceAtLeast(40)
        val bh = (boardBottom - boardTop).coerceAtLeast(40)
        val (woodR, woodG, woodB) = estimateBoardWoodRgb(bitmap, boardLeft, boardTop, boardRight, boardBottom)
        val woodLuma = (woodR * 30 + woodG * 59 + woodB * 11) / 100
        val scanLeft = (boardLeft + (bw * 0.14f).toInt()).coerceIn(0, bitmap.width - 2)
        val scanRight = (boardLeft + (bw * 0.86f).toInt()).coerceIn(scanLeft + 2, bitmap.width - 1)
        val scanTop = (boardTop + (bh * 0.70f).toInt()).coerceIn(0, bitmap.height - 2)
        val scanBottom = (boardTop + (bh * 0.92f).toInt()).coerceIn(scanTop + 2, bitmap.height - 1)
        val minRun = ((scanRight - scanLeft) * 0.30f).toInt().coerceAtLeast(20)

        fun longestDarkRun(py: Int): Int {
            var best = 0
            var run = 0
            var px = scanLeft
            while (px <= scanRight) {
                val p = bitmap.getPixel(px, py)
                val diff = abs(Color.red(p) - woodR) + abs(Color.green(p) - woodG) + abs(Color.blue(p) - woodB)
                val luma = (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                if (diff >= 56 && luma < woodLuma) run++ else {
                    if (run > best) best = run
                    run = 0
                }
                px++
            }
            if (run > best) best = run
            return best
        }

        var bestRun = 0
        var bestY = -1
        var y = scanTop
        while (y <= scanBottom) {
            val run = longestDarkRun(y)
            if (run > bestRun) {
                bestRun = run
                bestY = y
            }
            y++
        }
        if (bestY < 0 || bestRun < minRun) return null
        // The printed line is a few px tall: average every row whose dark
        // run is almost as long as the best row, so the centre is the
        // line's middle rather than its top or bottom edge.
        var sumY = 0f
        var count = 0
        y = scanTop
        while (y <= scanBottom) {
            if (longestDarkRun(y) >= (bestRun * 0.75f).toInt().coerceAtLeast(minRun)) {
                sumY += y.toFloat()
                count++
            }
            y++
        }
        return if (count > 0) sumY / count else bestY.toFloat()
    }

    private fun detectBottomSliderKnob(
        bitmap: Bitmap,
        boardLeft: Int,
        boardRight: Int,
        boardBottom: Int,
        scaleX: Float,
        scaleY: Float,
        calibration: BoardCalibrationProfile? = null,
        // Turn-glow centroid x (halo around the real striker): breaks ties
        // when the vivid and contrast candidates disagree.
        glowCenterX: Float? = null
    ): Point2D? {
        val sliderTop: Int
        val sliderBottom: Int
        val searchLeft: Int
        val searchRight: Int
        if (calibration != null && calibration.enabled) {
            val calCenterY = (calibration.sliderTrackYNorm * bitmap.height).toInt()
            val halfBand = (bitmap.height * 0.045f).toInt().coerceAtLeast(12)
            sliderTop = (calCenterY - halfBand).coerceIn(0, bitmap.height - 2)
            sliderBottom = (calCenterY + halfBand).coerceIn(sliderTop + 2, bitmap.height - 1)
            searchLeft = (calibration.sliderTrackLeftNorm * bitmap.width).toInt().coerceIn(0, bitmap.width - 10)
            searchRight = (calibration.sliderTrackRightNorm * bitmap.width).toInt().coerceIn(searchLeft + 10, bitmap.width - 1)
        } else {
            sliderTop = (boardBottom + (bitmap.height * 0.012f).toInt()).coerceIn(0, bitmap.height - 2)
            sliderBottom = (boardBottom + (bitmap.height * 0.21f).toInt()).coerceIn(sliderTop + 1, bitmap.height - 1)
            searchLeft = boardLeft
            searchRight = boardRight
        }
        val bw = (searchRight - searchLeft).coerceAtLeast(1)
        val bh = (sliderBottom - sliderTop).coerceAtLeast(1)
        val cols = (bw / SAMPLE_STEP).coerceAtLeast(1)
        val rows = (bh / SAMPLE_STEP).coerceAtLeast(1)
        if (cols < 4 || rows < 2) return null

        val active = Array(rows) { BooleanArray(cols) }
        val hsv = FloatArray(3)
        for (r in 0 until rows) {
            val py = (sliderTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (searchLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                rgbToHsv(bitmap.getPixel(px, py), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                // Slider knobs are vivid/bright discs (gold/amber, cyan/blue, emerald, ruby, silver/white) — exclude muted tan wood
                active[r][c] = (s >= 0.58f && v >= 0.65f && h in 20f..66f) ||
                    (s >= 0.42f && v >= 0.50f && h in 158f..235f) ||
                    (s >= 0.45f && v >= 0.48f && h in 88f..157f) ||
                    (s >= 0.55f && v >= 0.55f && (h <= 18f || h >= 336f)) ||
                    (s <= 0.24f && v >= 0.84f)
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        var bestCenter: Point2D? = null
        var bestCount = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!active[r][c] || visited[r][c]) continue
                var sumX = 0f
                var sumY = 0f
                var count = 0
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (searchLeft + cc * SAMPLE_STEP) * scaleX
                    sumY += (sliderTop + cr * SAMPLE_STEP) * scaleY
                    count++
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols && !visited[nr][nc] && active[nr][nc]) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                // Knob should be a compact disc (not a wide full-width track/bar)
                if (count in 4..180 && count > bestCount) {
                    bestCount = count
                    bestCenter = Point2D(sumX / count, sumY / count)
                }
            }
        }
        // NOTE: no early return here. The vivid pass alone cannot be
        // trusted for patterned skins: on the user's monster-skin board the
        // largest vivid blob is the slider track's gold end cap, not the
        // knob. The contrast pass below is the primary signal; vivid is
        // only a cross-check (see the decision at the end).
        val vividBest = bestCenter

        // Fallback: row-wise track-contrast check catches patterned/brown/dark/custom striker skins inside the slider bar
        val trackColStart = if (calibration != null && calibration.enabled) 0 else (cols * 0.18f).toInt().coerceIn(0, cols - 1)
        val trackColEnd = if (calibration != null && calibration.enabled) (cols - 1).coerceAtLeast(trackColStart) else (cols * 0.82f).toInt().coerceIn(trackColStart, cols - 1)
        val contrastActive = Array(rows) { BooleanArray(cols) }
        val rowR = IntArray(trackColEnd - trackColStart + 1)
        val rowG = IntArray(trackColEnd - trackColStart + 1)
        val rowB = IntArray(trackColEnd - trackColStart + 1)

        for (r in 0 until rows) {
            val py = (sliderTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            var idx = 0
            for (c in trackColStart..trackColEnd) {
                val px = (searchLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                rowR[idx] = Color.red(p)
                rowG[idx] = Color.green(p)
                rowB[idx] = Color.blue(p)
                idx++
            }
            val medR = medianOfCopy(rowR, idx)
            val medG = medianOfCopy(rowG, idx)
            val medB = medianOfCopy(rowB, idx)

            for (c in trackColStart..trackColEnd) {
                val px = (searchLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val diff = abs(Color.red(p) - medR) + abs(Color.green(p) - medG) + abs(Color.blue(p) - medB)
                contrastActive[r][c] = diff >= 54
            }
        }

        val cVisited = Array(rows) { BooleanArray(cols) }
        var contrastBestCenter: Point2D? = null
        var contrastBestCount = 0
        // Largest component overall (count >= 5): last-resort knob when the
        // filter below rejects every candidate (e.g. an unusually big skin).
        var contrastLargestCenter: Point2D? = null
        var contrastLargestCount = 0
        for (r in 0 until rows) {
            for (c in trackColStart..trackColEnd) {
                if (!contrastActive[r][c] || cVisited[r][c]) continue
                var sumX = 0f
                var sumY = 0f
                var count = 0
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                queue.add(r to c)
                cVisited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (searchLeft + cc * SAMPLE_STEP) * scaleX
                    sumY += (sliderTop + cr * SAMPLE_STEP) * scaleY
                    count++
                    if (cr < minR) minR = cr
                    if (cr > maxR) maxR = cr
                    if (cc < minC) minC = cc
                    if (cc > maxC) maxC = cc
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in trackColStart..trackColEnd &&
                                !cVisited[nr][nc] && contrastActive[nr][nc]
                            ) {
                                cVisited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                val spanR = (maxR - minR + 1).coerceAtLeast(1)
                val spanC = (maxC - minC + 1).coerceAtLeast(1)
                val aspect = maxOf(spanR, spanC).toFloat() / minOf(spanR, spanC).toFloat()
                if (count >= 5 && count > contrastLargestCount) {
                    contrastLargestCount = count
                    contrastLargestCenter = Point2D(sumX / count, sumY / count)
                }
                // Knob filter: a real knob merged with the slider track's edge
                // is a wide flat blob (measured: count 333, spanC 43, aspect 3.31
                // on a 720x1600 board), while the track end caps are smaller
                // (count ~130). The old caps (180 / 0.16*cols / 2.4) rejected the
                // real knob, so a track end was picked as the "knob" instead.
                if (count in 5..400 && spanR >= 2 && spanC <= (cols * 0.30f).toInt().coerceAtLeast(12) && aspect <= 4.0f && count > contrastBestCount) {
                    contrastBestCount = count
                    contrastBestCenter = Point2D(sumX / count, sumY / count)
                }
            }
        }
        // Decide: contrast is primary (on the measured boards the real knob
        // is the largest contrast blob, 247-333 samples, while the track
        // end caps are ~130-150). Vivid agrees when it found the same disc.
        // On disagreement the glow centroid — the halo around the real
        // striker — breaks the tie.
        val contrastPick = contrastBestCenter ?: contrastLargestCenter
        val trackWidthPx = (boardRight - boardLeft) * scaleX
        return when {
            contrastPick != null && vividBest != null -> {
                if (abs(contrastPick.x - vividBest.x) <= trackWidthPx * 0.10f) {
                    contrastPick
                } else if (glowCenterX != null) {
                    if (abs(contrastPick.x - glowCenterX) <= abs(vividBest.x - glowCenterX)) contrastPick else vividBest
                } else {
                    contrastPick
                }
            }
            contrastPick != null -> contrastPick
            else -> vividBest
        }
    }

    private fun promoteOrAnchorStriker(
        pieces: List<CarromPiece>,
        baselineY: Float,
        boardLeft: Float,
        boardRight: Float,
        boardHeightPx: Float,
        strikerRadiusPx: Float,
        sliderKnobPx: Point2D?,
        // Skin-independent anchor x (knob + glow + appearance vote).
        // When present it IS the striker x — any appearance-matched
        // "striker" far from it is a board marker, not the striker.
        anchorStrikerX: Float? = null,
        coinRadiusPx: Float = 0f,
        // Drop face fragments (mislabelled queen/white bits of a
        // decorated striker skin) only when the anchor is trusted.
        cleanFaceFragments: Boolean = false
    ): List<CarromPiece> {
        if (anchorStrikerX != null) {
            val clampedX = anchorStrikerX.coerceIn(
                boardLeft + strikerRadiusPx * 2.1f,
                boardRight - strikerRadiusPx * 2.1f
            )
            val anchored = CarromPiece(
                color = CoinColor.STRIKER,
                center = Point2D(clampedX, baselineY),
                radiusPx = strikerRadiusPx
            )
            // A decorated striker's face fragments (red/white bits of the
            // skin) can be mislabelled as queen/white coins sitting on the
            // striker. A real coin's centre can never be closer than
            // strikerRadius + coinRadius to the striker's centre (the discs
            // cannot overlap), so anything nearer is face, not a coin.
            val clearR = if (cleanFaceFragments && coinRadiusPx > 0f) {
                (strikerRadiusPx + coinRadiusPx) * 0.95f
            } else {
                0f
            }
            val rest = pieces.filter { piece ->
                piece.color != CoinColor.STRIKER &&
                    (clearR <= 0f || (piece.center - anchored.center).length() >= clearR)
            }
            return rest + anchored
        }

        if (pieces.any { it.color == CoinColor.STRIKER }) return pieces

        val baselineTolerance = boardHeightPx * 0.085f
        val baselineCandidates = pieces.filter { abs(it.center.y - baselineY) <= baselineTolerance }

        // 1. If bottom slider knob is detected, match the piece on the baseline closest to slider X
        if (sliderKnobPx != null) {
            val maxHorizDelta = (boardRight - boardLeft) * 0.16f
            val matchedBySlider = baselineCandidates.minByOrNull { abs(it.center.x - sliderKnobPx.x) }
            if (matchedBySlider != null && abs(matchedBySlider.center.x - sliderKnobPx.x) <= maxHorizDelta) {
                return pieces.map { piece ->
                    if (piece === matchedBySlider) piece.copy(color = CoinColor.STRIKER) else piece
                }
            }
            // Even if the striker has an uncommon dark/patterned skin, its position matches sliderKnobPx.x on baselineY!
            val clampedX = sliderKnobPx.x.coerceIn(
                boardLeft + strikerRadiusPx * 2.1f,
                boardRight - strikerRadiusPx * 2.1f
            )
            return pieces + CarromPiece(
                color = CoinColor.STRIKER,
                center = Point2D(clampedX, baselineY),
                radiusPx = strikerRadiusPx
            )
        }

        // 2. Fallback without slider: promote only a genuine striker-sized disc inside the playable middle baseline
        val avgRadius = pieces.map { it.radiusPx }.average().toFloat().coerceAtLeast(14f)
        val midLeft = boardLeft + (boardRight - boardLeft) * 0.21f
        val midRight = boardLeft + (boardRight - boardLeft) * 0.79f
        val tightBaselineCandidate = baselineCandidates
            .filter {
                abs(it.center.y - baselineY) <= boardHeightPx * 0.055f &&
                    it.center.x in midLeft..midRight
            }
            .maxByOrNull { it.radiusPx }
        if (tightBaselineCandidate != null &&
            tightBaselineCandidate.radiusPx >= maxOf(avgRadius * 1.10f, strikerRadiusPx * 0.90f)
        ) {
            return pieces.map { piece ->
                if (piece === tightBaselineCandidate) piece.copy(color = CoinColor.STRIKER) else piece
            }
        }

        return pieces
    }

    private fun detectBoardPieces(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        scaleX: Float,
        scaleY: Float,
        expectedCoinRadiusPx: Float = 28f,
        useSubPixelRefinement: Boolean = true,
        baselineFraction: Float = 0.845f,
        calibration: BoardCalibrationProfile? = null
    ): List<CarromPiece> {
        val bw = (right - left).coerceAtLeast(1)
        val bh = (bottom - top).coerceAtLeast(1)
        val cols = (bw / SAMPLE_STEP).coerceAtLeast(1)
        val rows = (bh / SAMPLE_STEP).coerceAtLeast(1)

        val labels = Array(rows) { arrayOfNulls<CoinColor>(cols) }
        val hsv = FloatArray(3)
        val pocketMargin = (bw * 0.11f).toInt()
        val calWhite = calibration?.whiteColor
        val calBlack = calibration?.blackColor
        val calQueen = calibration?.queenColor
        val calStriker = calibration?.strikerColor

        for (r in 0 until rows) {
            val py = (top + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (left + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)

                // Skip dark corner pocket holes
                val dx = px - left
                val dy = py - top
                val nearCorner = (dx < pocketMargin || dx > bw - pocketMargin) &&
                    (dy < pocketMargin || dy > bh - pocketMargin)
                if (nearCorner) continue

                val pixel = bitmap.getPixel(px, py)
                rgbToHsv(pixel, hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]

                val isBaselineBand = abs(dy - bh * baselineFraction) < bh * 0.105f

                labels[r][c] = when {
                    // Striker: distinct colored disc/ring on baseline or calibrated striker swatch
                    isBaselineBand && (
                        (calStriker != null && calStriker.matches(hsv, CoinColor.STRIKER)) ||
                            (h in 162f..245f && s >= 0.34f && v >= 0.40f) ||
                            (h in 36f..62f && s >= 0.56f && v >= 0.70f) ||
                            (h in 260f..325f && s >= 0.40f && v >= 0.40f) ||
                            (h in 85f..155f && s >= 0.42f && v >= 0.42f)
                        ) -> CoinColor.STRIKER
                    // Queen: saturated red/crimson or calibrated queen swatch
                    (calQueen != null && calQueen.matches(hsv, CoinColor.QUEEN)) ||
                        ((h >= 338f || h <= 16f) && s >= 0.48f && v >= 0.35f) -> CoinColor.QUEEN
                    // White coin: bright ivory/white or calibrated white swatch
                    (calWhite != null && calWhite.matches(hsv, CoinColor.WHITE)) ||
                        (s <= 0.28f && v >= 0.69f) -> CoinColor.WHITE
                    // Black coin: dark grey/charcoal disc or calibrated black swatch
                    (calBlack != null && calBlack.matches(hsv, CoinColor.BLACK)) ||
                        (v in 0.06f..0.30f && s <= 0.42f) -> CoinColor.BLACK
                    else -> null
                }
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val detected = mutableListOf<CarromPiece>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        val bucketCellPx = (bw * scaleX * 0.056f).coerceAtLeast(24f)

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val color = labels[r][c] ?: continue
                if (visited[r][c]) continue

                var sumX = 0f
                var sumY = 0f
                var count = 0
                val clusterPoints = ArrayList<Point2D>(64)
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    val sx = (left + cc * SAMPLE_STEP) * scaleX
                    val sy = (top + cr * SAMPLE_STEP) * scaleY
                    sumX += sx
                    sumY += sy
                    count++
                    if (clusterPoints.size < 900) {
                        clusterPoints.add(Point2D(sx, sy))
                    }

                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            if (dr == 0 && dc == 0) continue
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols &&
                                !visited[nr][nc] && labels[nr][nc] == color
                            ) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                if (color == CoinColor.STRIKER) {
                    if (count in 9..260) {
                        val coarse = Point2D(sumX / count, sumY / count)
                        val radiusApprox = (kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX)
                            .coerceIn(14f, 68f)
                        val refined = if (useSubPixelRefinement) {
                            CarromCircleRefiner.refinePieceCenterSubPixel(bitmap, coarse, radiusApprox, scaleX, scaleY)
                        } else coarse
                        detected.add(CarromPiece(color, refined, radiusApprox))
                    }
                } else if (count in 4..75) {
                    val coarse = Point2D(sumX / count, sumY / count)
                    val radiusApprox = (kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX)
                        .coerceIn(14f, 68f)
                    val refined = if (useSubPixelRefinement) {
                        CarromCircleRefiner.refinePieceCenterSubPixel(bitmap, coarse, expectedCoinRadiusPx, scaleX, scaleY)
                    } else coarse
                    detected.add(CarromPiece(color, refined, radiusApprox))
                } else if (count in 76..900) {
                    // Use Hough Circle separation to extract exact individual disc centers inside touching break clusters
                    val houghSeparated = if (useSubPixelRefinement) {
                        CarromCircleRefiner.separateClusterByHough(
                            bitmap = bitmap,
                            clusterPointsScreen = clusterPoints,
                            colorHint = color,
                            coinRadiusScreen = expectedCoinRadiusPx,
                            scaleX = scaleX,
                            scaleY = scaleY
                        )
                    } else {
                        emptyList()
                    }
                    if (houghSeparated.isNotEmpty()) {
                        detected.addAll(houghSeparated)
                    } else {
                        val buckets = LinkedHashMap<Pair<Int, Int>, MutableList<Point2D>>()
                        for (pt in clusterPoints) {
                            val bx = (pt.x / bucketCellPx).toInt()
                            val by = (pt.y / bucketCellPx).toInt()
                            buckets.getOrPut(bx to by) { mutableListOf() }.add(pt)
                        }
                        for ((_, pts) in buckets) {
                            if (pts.size >= 3) {
                                val avgX = pts.sumOf { it.x.toDouble() }.toFloat() / pts.size
                                val avgY = pts.sumOf { it.y.toDouble() }.toFloat() / pts.size
                                detected.add(CarromPiece(color, Point2D(avgX, avgY), (bucketCellPx * 0.48f).coerceIn(16f, 44f)))
                            }
                        }
                    }
                }
            }
        }

        return detected
    }

    /**
     * Second "whose turn is it" signal: Carrom Pool draws a pulsing
     * green/yellow glow halo around the bottom striker while it is OUR turn.
     * Scans the baseline band for dense, wide green/yellow glow pixels.
     * Conservative on purpose: a single coin sitting on the baseline is small
     * and narrow, so it cannot reach both the sample-count and the width
     * threshold at once. Only used to skip the wait budget when no opponent
     * striker is visible on the top baseline — never to fire a shot alone.
     */
    /**
     * "Your turn" glow halo around the bottom striker, with its centre.
     * The halo is the most skin-independent striker locator on the board:
     * its green part belongs ONLY to the halo (the yellow baseline
     * end-circles are yellow, not green), so the green-only centroid sits
     * on the striker within a few px. Falls back to the densest glow
     * cluster, then to the plain centroid.
     */
    private data class TurnGlowCenter(
        val center: Point2D?,
        val samples: Int,
        val spanPx: Int,
        val greenSamples: Int
    ) {
        // Same thresholds as before: dense AND wide, so a single coin on
        // the baseline can never count as a turn signal.
        val detected: Boolean get() = samples >= 120 && spanPx >= 56
    }

    private fun detectBottomTurnGlowCenter(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int,
        baselineFraction: Float
    ): TurnGlowCenter {
        val bw = (boardRight - boardLeft).coerceAtLeast(40)
        val bh = (boardBottom - boardTop).coerceAtLeast(40)
        val scanLeft = (boardLeft + (bw * 0.14f).toInt()).coerceIn(0, bitmap.width - 2)
        val scanRight = (boardLeft + (bw * 0.86f).toInt()).coerceIn(scanLeft + 2, bitmap.width - 1)
        val scanTop = (boardTop + (bh * (baselineFraction - 0.085f)).toInt()).coerceIn(0, bitmap.height - 2)
        val scanBottom = (boardTop + (bh * (baselineFraction + 0.085f)).toInt()).coerceIn(scanTop + 2, bitmap.height - 1)
        val cols = ((scanRight - scanLeft) / SAMPLE_STEP).coerceAtLeast(1)
        val rows = ((scanBottom - scanTop) / SAMPLE_STEP).coerceAtLeast(1)
        if (cols < 4 || rows < 3) return TurnGlowCenter(null, 0, 0, 0)

        val hsv = FloatArray(3)
        var glowSamples = 0
        var minGlowX = Int.MAX_VALUE
        var maxGlowX = Int.MIN_VALUE
        var sumX = 0f
        var sumY = 0f
        var greenSumX = 0f
        var greenSumY = 0f
        var greenSamples = 0
        val glowXs = ArrayList<Int>()
        val glowYs = ArrayList<Int>()
        for (r in 0 until rows) {
            val py = (scanTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (scanLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                rgbToHsv(bitmap.getPixel(px, py), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                val isGreen = h in 85f..155f && s >= 0.42f && v >= 0.42f
                val isYellow = h in 36f..62f && s >= 0.56f && v >= 0.70f
                if (isGreen || isYellow) {
                    glowSamples++
                    sumX += px
                    sumY += py
                    glowXs.add(px)
                    glowYs.add(py)
                    if (px < minGlowX) minGlowX = px
                    if (px > maxGlowX) maxGlowX = px
                }
                if (isGreen) {
                    greenSamples++
                    greenSumX += px
                    greenSumY += py
                }
            }
        }
        val spanPx = if (glowSamples > 0) maxGlowX - minGlowX else 0
        val center: Point2D? = when {
            greenSamples >= 8 -> Point2D(greenSumX / greenSamples, greenSumY / greenSamples)
            glowSamples > 0 -> {
                // Densest 40px window, then the mean of the glow pixels
                // within +-60px of its centre (robust to the symmetric
                // yellow end-circles pulling a plain centroid).
                val sortedXs = glowXs.sorted()
                var winSum = 0f
                var winJ = 0
                var bestCount = 0
                var bestMean = 0f
                for (i in sortedXs.indices) {
                    winSum += sortedXs[i]
                    while (sortedXs[winJ] < sortedXs[i] - 40) {
                        winSum -= sortedXs[winJ]
                        winJ++
                    }
                    val count = i - winJ + 1
                    if (count > bestCount) {
                        bestCount = count
                        bestMean = winSum / count
                    }
                }
                var nearSumX = 0f
                var nearSumY = 0f
                var nearCount = 0
                for (k in glowXs.indices) {
                    if (abs(glowXs[k] - bestMean) <= 60f) {
                        nearSumX += glowXs[k]
                        nearSumY += glowYs[k]
                        nearCount++
                    }
                }
                if (nearCount > 0) Point2D(nearSumX / nearCount, nearSumY / nearCount)
                else Point2D(sumX / glowSamples, sumY / glowSamples)
            }
            else -> null
        }
        return TurnGlowCenter(center, glowSamples, spanPx, greenSamples)
    }

    /**
     * Second "whose turn is it" signal: the bottom turn-glow halo is bright
     * green/yellow AND wide (only when YOUR striker sits on your baseline,
     * with the halo around it). The single-coin-on-the-baseline problem is
     * avoided by requiring a wide span AND many samples. A piece placed on
     * the baseline cannot be large enough or bright enough to match, so it
     * won't trigger this.
     */
    private fun detectBottomTurnGlow(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int,
        baselineFraction: Float
    ): Boolean {
        return detectBottomTurnGlowCenter(
            bitmap = bitmap,
            boardLeft = boardLeft,
            boardTop = boardTop,
            boardRight = boardRight,
            boardBottom = boardBottom,
            baselineFraction = baselineFraction
        ).detected
    }

    /**
     * Skin-independent striker anchor: the slider knob x, the turn-glow
     * centroid x, and the nearest STRIKER-coloured fragment vote on the
     * striker's x. Two agreeing signals win; the knob is the exact slider
     * mapping, so it wins ties. The glow's green centroid is the most
     * precise single signal (measured within ~2px of the true striker),
     * so it wins an uncorroborated disagreement.
     */
    private fun resolveStrikerAnchorX(
        sliderKnobPx: Point2D?,
        glowCenterX: Float?,
        appearanceX: Float?,
        boardWidthPx: Float
    ): Float? {
        val knobX = sliderKnobPx?.x
        if (knobX != null && glowCenterX != null) {
            if (abs(knobX - glowCenterX) <= boardWidthPx * 0.10f) return knobX
            if (appearanceX != null) {
                val voteTol = boardWidthPx * 0.06f
                val backsKnob = abs(appearanceX - knobX) <= voteTol && abs(appearanceX - glowCenterX) > voteTol
                val backsGlow = abs(appearanceX - glowCenterX) <= voteTol && abs(appearanceX - knobX) > voteTol
                if (backsKnob) return knobX
                if (backsGlow) return glowCenterX
            }
            return glowCenterX
        }
        return glowCenterX ?: knobX
    }

    private fun detectVividStrikerOnBand(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int,
        scaleX: Float,
        scaleY: Float,
        baselineFraction: Float,
        strikerRadiusPx: Float,
        useSubPixelRefinement: Boolean
    ): CarromPiece? {
        val bw = (boardRight - boardLeft).coerceAtLeast(40)
        val bh = (boardBottom - boardTop).coerceAtLeast(40)
        val scanLeft = (boardLeft + (bw * 0.14f).toInt()).coerceIn(0, bitmap.width - 2)
        val scanRight = (boardLeft + (bw * 0.86f).toInt()).coerceIn(scanLeft + 2, bitmap.width - 1)
        val scanTop = (boardTop + (bh * (baselineFraction - 0.085f)).toInt()).coerceIn(0, bitmap.height - 2)
        val scanBottom = (boardTop + (bh * (baselineFraction + 0.085f)).toInt()).coerceIn(scanTop + 2, bitmap.height - 1)

        val cols = ((scanRight - scanLeft) / SAMPLE_STEP).coerceAtLeast(1)
        val rows = ((scanBottom - scanTop) / SAMPLE_STEP).coerceAtLeast(1)
        if (cols < 4 || rows < 3) return null

        val active = Array(rows) { BooleanArray(cols) }
        val hsv = FloatArray(3)
        for (r in 0 until rows) {
            val py = (scanTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (scanLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                rgbToHsv(bitmap.getPixel(px, py), hsv)
                val h = hsv[0]
                val s = hsv[1]
                val v = hsv[2]
                active[r][c] = (h in 162f..245f && s >= 0.34f && v >= 0.40f) ||
                    (h in 36f..62f && s >= 0.56f && v >= 0.70f) ||
                    (h in 260f..325f && s >= 0.40f && v >= 0.40f) ||
                    (h in 85f..155f && s >= 0.42f && v >= 0.42f)
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        var bestPiece: CarromPiece? = null
        var bestCount = 0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!active[r][c] || visited[r][c]) continue
                var sumX = 0f
                var sumY = 0f
                var count = 0
                queue.add(r to c)
                visited[r][c] = true
                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (scanLeft + cc * SAMPLE_STEP) * scaleX
                    sumY += (scanTop + cr * SAMPLE_STEP) * scaleY
                    count++
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols && !visited[nr][nc] && active[nr][nc]) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }
                if (count in 6..260 && count > bestCount) {
                    val coarse = Point2D(sumX / count, sumY / count)
                    val radiusApprox = (kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX)
                        .coerceIn(strikerRadiusPx * 0.75f, strikerRadiusPx * 1.45f)
                    val refined = if (useSubPixelRefinement) {
                        CarromCircleRefiner.refinePieceCenterSubPixel(bitmap, coarse, strikerRadiusPx, scaleX, scaleY)
                    } else {
                        coarse
                    }
                    bestCount = count
                    bestPiece = CarromPiece(CoinColor.STRIKER, refined, radiusApprox)
                }
            }
        }
        return bestPiece
    }

    private fun detectAnyStrikerOnBaseline(
        bitmap: Bitmap,
        boardLeft: Int,
        boardTop: Int,
        boardRight: Int,
        boardBottom: Int,
        scaleX: Float,
        scaleY: Float,
        strikerRadiusPx: Float,
        sliderKnobPx: Point2D?,
        scanTopFraction: Float = 0.76f,
        scanBottomFraction: Float = 0.93f
    ): CarromPiece? {
        val bw = (boardRight - boardLeft).coerceAtLeast(40)
        val bh = (boardBottom - boardTop).coerceAtLeast(40)
        val (woodR, woodG, woodB) = estimateBoardWoodRgb(bitmap, boardLeft, boardTop, boardRight, boardBottom)

        val scanLeft = (boardLeft + (bw * 0.14f).toInt()).coerceIn(0, bitmap.width - 2)
        val scanRight = (boardLeft + (bw * 0.86f).toInt()).coerceIn(scanLeft + 2, bitmap.width - 1)
        val scanTop = (boardTop + (bh * scanTopFraction).toInt()).coerceIn(0, bitmap.height - 2)
        val scanBottom = (boardTop + (bh * scanBottomFraction).toInt()).coerceIn(scanTop + 2, bitmap.height - 1)

        val cols = ((scanRight - scanLeft) / SAMPLE_STEP).coerceAtLeast(1)
        val rows = ((scanBottom - scanTop) / SAMPLE_STEP).coerceAtLeast(1)
        if (cols < 4 || rows < 3) return null

        val rawNonWood = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) {
            val py = (scanTop + r * SAMPLE_STEP).coerceIn(0, bitmap.height - 1)
            for (c in 0 until cols) {
                val px = (scanLeft + c * SAMPLE_STEP).coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val diff = abs(Color.red(p) - woodR) + abs(Color.green(p) - woodG) + abs(Color.blue(p) - woodB)
                rawNonWood[r][c] = diff >= 56
            }
        }

        // Remove 1-sample-tall horizontal baseline tracks so flood-fill does not glue
        // the striker disc onto the baseline rails or red/orange end-circles.
        val nonWood = Array(rows) { BooleanArray(cols) }
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!rawNonWood[r][c]) continue
                val hasVertNeighbor =
                    (r > 0 && (rawNonWood[r - 1][c] || (c > 0 && rawNonWood[r - 1][c - 1]) || (c + 1 < cols && rawNonWood[r - 1][c + 1]))) ||
                        (r + 1 < rows && (rawNonWood[r + 1][c] || (c > 0 && rawNonWood[r + 1][c - 1]) || (c + 1 < cols && rawNonWood[r + 1][c + 1])))
                nonWood[r][c] = hasVertNeighbor
            }
        }

        val visited = Array(rows) { BooleanArray(cols) }
        val queue = ArrayDeque<Pair<Int, Int>>()
        val innerSafeLeftPx = (boardLeft + bw * 0.21f) * scaleX
        val innerSafeRightPx = (boardLeft + bw * 0.79f) * scaleX
        val boardWidthPx = bw * scaleX

        var bestPiece: CarromPiece? = null
        var bestScore = 0f

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                if (!nonWood[r][c] || visited[r][c]) continue
                var sumX = 0f
                var sumY = 0f
                var count = 0
                var minR = r
                var maxR = r
                var minC = c
                var maxC = c
                queue.add(r to c)
                visited[r][c] = true

                while (queue.isNotEmpty()) {
                    val (cr, cc) = queue.removeFirst()
                    sumX += (scanLeft + cc * SAMPLE_STEP) * scaleX
                    sumY += (scanTop + cr * SAMPLE_STEP) * scaleY
                    count++
                    if (cr < minR) minR = cr
                    if (cr > maxR) maxR = cr
                    if (cc < minC) minC = cc
                    if (cc > maxC) maxC = cc
                    for (dr in -1..1) {
                        for (dc in -1..1) {
                            val nr = cr + dr
                            val nc = cc + dc
                            if (nr in 0 until rows && nc in 0 until cols && !visited[nr][nc] && nonWood[nr][nc]) {
                                visited[nr][nc] = true
                                queue.add(nr to nc)
                            }
                        }
                    }
                }

                val spanR = (maxR - minR + 1).coerceAtLeast(1)
                val spanC = (maxC - minC + 1).coerceAtLeast(1)
                val aspect = maxOf(spanR, spanC).toFloat() / minOf(spanR, spanC).toFloat()
                if (count !in 8..280 || spanR < 3 || spanC < 3 || aspect > 2.2f) continue

                val cx = sumX / count
                val cy = sumY / count
                val inSafeMidZone = cx in innerSafeLeftPx..innerSafeRightPx
                val nearSlider = sliderKnobPx != null && abs(cx - sliderKnobPx.x) <= boardWidthPx * 0.16f
                val approxUnclampedR = kotlin.math.sqrt(count.toFloat() / 3.14f) * SAMPLE_STEP * scaleX
                if (!inSafeMidZone && !nearSlider && approxUnclampedR < strikerRadiusPx * 0.92f) {
                    // Ignore small stationary orange baseline end-circles at any screen DPI when no slider knob is near
                    continue
                }
                if (sliderKnobPx != null && !nearSlider) continue

                val score = count.toFloat() + (if (nearSlider) 120f else 0f) + (if (inSafeMidZone) 40f else 0f)
                if (score > bestScore) {
                    bestScore = score
                    val approxR = approxUnclampedR.coerceIn(strikerRadiusPx * 0.8f, strikerRadiusPx * 1.45f)
                    bestPiece = CarromPiece(CoinColor.STRIKER, Point2D(cx, cy), approxR)
                }
            }
        }
        if (bestPiece != null) return bestPiece

        // Radial gradient Hough fallback: pinpoints exact visual striker (x,y) around sliderKnobPx.x
        // even when dark/wood-toned/patterned striker skins fail color-diff thresholding or baseline lines touch it.
        if (sliderKnobPx != null) {
            val houghMinX = (sliderKnobPx.x - boardWidthPx * 0.18f).coerceAtLeast(boardLeft * scaleX + strikerRadiusPx)
            val houghMaxX = (sliderKnobPx.x + boardWidthPx * 0.18f).coerceAtMost(boardRight * scaleX - strikerRadiusPx)
            return CarromCircleRefiner.findStrikerByHoughInWindow(
                bitmap = bitmap,
                searchMinScreenX = houghMinX,
                searchMaxScreenX = houghMaxX,
                searchMinScreenY = scanTop * scaleY,
                searchMaxScreenY = scanBottom * scaleY,
                expectedRadiusScreen = strikerRadiusPx,
                scaleX = scaleX,
                scaleY = scaleY,
                preferredXScreen = sliderKnobPx.x
            )
        }
        return null
    }

    private fun estimateBoardWoodRgb(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Triple<Int, Int, Int> {
        val bw = (right - left).coerceAtLeast(1)
        val bh = (bottom - top).coerceAtLeast(1)
        val maxSamples = 144
        val rs = IntArray(maxSamples)
        val gs = IntArray(maxSamples)
        val bs = IntArray(maxSamples)
        var count = 0

        for (gy in 0 until 12) {
            val fy = 0.18f + gy * (0.64f / 11f)
            val py = (top + bh * fy).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until 12) {
                val fx = 0.18f + gx * (0.64f / 11f)
                // Skip center break-rack region so clustered coins don't skew median wood color
                if (fx in 0.34f..0.66f && fy in 0.34f..0.66f) continue
                val px = (left + bw * fx).toInt().coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                if (count < maxSamples) {
                    rs[count] = Color.red(p)
                    gs[count] = Color.green(p)
                    bs[count] = Color.blue(p)
                    count++
                }
            }
        }
        if (count == 0) return Triple(205, 165, 110)
        return Triple(medianOfCopy(rs, count), medianOfCopy(gs, count), medianOfCopy(bs, count))
    }

    private fun medianOfCopy(values: IntArray, length: Int): Int {
        if (length <= 0) return 0
        val copy = values.copyOf(length)
        copy.sort()
        return copy[length / 2]
    }

    private fun sampleBoardLumaGrid(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): IntArray {
        val out = IntArray(GRID_SIZE * GRID_SIZE)
        val w = (right - left).coerceAtLeast(1)
        val h = (bottom - top).coerceAtLeast(1)
        var idx = 0
        for (gy in 0 until GRID_SIZE) {
            val py = (top + (gy + 0.5f) * h / GRID_SIZE).toInt().coerceIn(0, bitmap.height - 1)
            for (gx in 0 until GRID_SIZE) {
                val px = (left + (gx + 0.5f) * w / GRID_SIZE).toInt().coerceIn(0, bitmap.width - 1)
                val p = bitmap.getPixel(px, py)
                val luma = (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                out[idx++] = luma
            }
        }
        return out
    }

    private fun computeMeanAbsDiff(a: IntArray, b: IntArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var total = 0L
        for (i in a.indices) {
            total += abs(a[i] - b[i])
        }
        return total.toFloat() / a.size
    }

    private fun rgbToHsv(pixel: Int, out: FloatArray) {
        val r = ((pixel ushr 16) and 0xFF) / 255f
        val g = ((pixel ushr 8) and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        val delta = maxC - minC

        var hue = when {
            delta < 1e-5f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }
        if (hue < 0f) hue += 360f
        val sat = if (maxC < 1e-5f) 0f else delta / maxC

        out[0] = hue
        out[1] = sat
        out[2] = maxC
    }
}
