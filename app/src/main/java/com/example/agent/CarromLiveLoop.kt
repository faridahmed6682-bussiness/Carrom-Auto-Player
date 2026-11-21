package com.example.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import com.example.game.BoardCalibrationProfile
import com.example.game.BoardPieceCounts
import com.example.game.BoardProfileMatcher
import com.example.game.CarromGeometrySolver
import com.example.game.CarromMemoryData
import com.example.game.CarromPiece
import com.example.game.CarromShotMemoryStore
import com.example.game.CarromShotOutcomeType
import com.example.game.CarromShotSolution
import com.example.game.CarromTurnHudState
import com.example.game.CarromTurnMemory
import com.example.game.CarromVisionDetector
import com.example.game.CarromVisionOutcome
import com.example.game.CoinColor
import com.example.game.MissCauseInput
import com.example.game.PendingCarromShotRecord
import com.example.game.Point2D
import com.example.game.SelfPlayConfig
import com.example.game.TurnProbeStage
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Carrom live loop — the carrom-playing chain extracted from TapPilot's
 * AgentCoordinator (Auto-Agent5, commit ea02d43).
 *
 * SOURCE — app/src/main/java/com/example/agent/AgentCoordinator.kt:
 * - decideCarromOrAiAction .......... lines 1423-1447 (carrom half only;
 *   the AI-planner half, planWithAiAction, is NOT here)
 * - solveCarromAction ............... lines 1448-1503
 * - evaluatePreviousCarromShot ...... lines 1504-1708
 * - handleCarromOutcome ............. lines 1709-1777
 * - handleSolvedShot ................ lines 1778-1806
 * - probeCarromShot ................. lines 1807-2098
 * - fireCarromShot .................. lines 2099-2380
 * - resolveCarromRuleMode ........... lines 3162-3180
 * - tryLaunchPackage ................ lines 3181-3192
 * - tryLaunchInstalledCarromApp ..... lines 3193-3206
 * - exportCarromTrainingJson ........ lines 239-248
 * - importCarromTrainingJson ........ lines 250-267
 * - self-play idle trigger .......... lines 925-950 (observeAndIntake)
 * - carrom state fields ............. TaskRunState, lines 283-360
 *   (carrom fields only) + PendingSliderVerify, lines 53-58
 * - data classes .................... lines 399-421 (CarromActionResult,
 *   CarromShotProbe, CarromShotProbeResult, CarromShotResult)
 *
 * The carrom LOGIC is copied verbatim where possible. Only the plumbing
 * was adapted:
 * - No coordinator step loop: the host feeds screenshots, one per cycle,
 *   via [playTurn]; the loop detects the turn, solves, probes inside the
 *   5-second budget (CarromTurnMemory), and fires through [dragPerformer].
 * - No PlannedAction / AgentActionType: a decided drag is a [CarromDrag]
 *   (start/end + duration) executed inline via [dragPerformer]; a decided
 *   wait is [CarromTurnResult.Waiting]; "not a carrom turn" is
 *   [CarromTurnResult.NotCarromTurn] and the host decides (AI fallback).
 * - No UiSnapshot / StepObservation / StepAnalysis: screen size comes from
 *   the screenshot itself; rule mode + coin colour come from [goal] and
 *   [settings]; "is this a match screen" is answered by the vision
 *   outcome itself (no board found -> NeedsAiVision -> NotCarromTurn).
 *   The coordinator's `interactiveNodeCount < 50` gate is therefore gone —
 *   the host should only feed game screenshots to [playTurn].
 * - No LiveMediaProjectionCapturer: [isLiveStream] is a constructor flag.
 * - No StateFlow HUD: the latest HUD is [lastHud] and is also carried on
 *   every [CarromTurnResult]. No _uiState / trace / workingMemory.
 * - No AgentKnowledgeStore: the shot-evaluation lesson that TapPilot wrote
 *   into its playbook is handed to the host via [onShotLesson] instead.
 *   Imported training playbooks are NOT auto-saved anywhere — read
 *   [lastImportedPlaybook] after [importCarromTrainingJson].
 * - No CoroutineScope.launch: store calls that TapPilot fire-and-forgot
 *   (profile activation / reset) run directly here — [playTurn] is a
 *   suspend fun, so they simply await.
 *
 * LEFT OUT on purpose (not carrom, or a different agent's job):
 * - planWithAiAction / GeminiAiPlanner / AI fallback (host's job when the
 *   result is NotCarromTurn).
 * - runWatchTraining / CarromWatchTrainer ("Watch & Learn" coach mode).
 * - Screen capture, launcher search, notifications, messaging, chat guard,
 *   action cache, contacts, playbooks, confirmation gates.
 *
 * HOW THE HOST USES IT:
 * 1. Build it with a [CarromShotMemoryStore] (call loadInitial() first),
 *    a [dragPerformer] that performs one accessibility slingshot drag and
 *    returns true when it was dispatched, and the current [AgentSettings].
 * 2. Every cycle (~1s, or right after the previous drag), call
 *    `playTurn(screenshot, System.currentTimeMillis())` with a fresh
 *    screenshot of the game screen and read the [CarromTurnResult]:
 *    SliderMoved / ShotFired = a drag was performed; Waiting = feed the
 *    next screenshot after ~1s; NotCarromTurn = not a board / solver off /
 *    vision needs AI — host decides (open the game, wait, or AI fallback).
 * 3. When the app sits idle on the launcher/settings screen, call
 *    [maybeRunIdleSelfPlay] to run one background self-play sweep.
 * 4. [tryLaunchInstalledCarromApp] needs a Context; without one it
 *    returns false.
 *
 * No secrets live in this file (no keys, tokens or passwords).
 */
class CarromLiveLoop(
    private val memoryStore: CarromShotMemoryStore,
    private val dragPerformer: suspend (startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) -> Boolean,
    private val context: Context? = null,
    initialSettings: AgentSettings = AgentSettings(),
    private val goal: String = "carrom",
    private val isLiveStream: Boolean = false,
    private val carromPlaybook: TaskPlaybook? = null,
    /** Called with a shot-evaluation lesson (tactic / foul), if any, so the
     *  host can record it in its own playbook. TapPilot wrote these into
     *  AgentKnowledgeStore; this export has no knowledge store. */
    private val onShotLesson: ((tacticLesson: String?, foulLesson: String?) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CarromLiveLoop"
    }

    var settings: AgentSettings = initialSettings
        private set

    // ---- Carrom run state (from TaskRunState's carrom fields) ----
    private val turnMemory = CarromTurnMemory()
    private var pendingSliderVerify: PendingSliderVerify? = null
    private var pendingCarromShot: PendingCarromShotRecord? = null
    private var consecutiveUnregisteredCarromShots = 0
    private var lastShotAttemptStrikerX: Float? = null
    private var lastShotPrePieces: List<CarromPiece> = emptyList()
    private var queenPendingCover = false
    // Fresh-rack latch: set once the board is seen depleted mid-match.
    private var carromSawDepletedBoard = false
    // Striker-sweep cache (try-and-see aim test) for one board layout.
    private var carromSweepKey: String? = null
    private var carromSweepEntries: List<CarromGeometrySolver.StrikerSweepEntry> = emptyList()
    // Miss-cause vision guard: cycles spent waiting for a 2-frame-stable
    // post-shot detection before recording the outcome.
    private var pendingEvalStaleCycles = 0
    private var carromOfflineWaits = 0
    private var movedStrikerThisTurn = false
    // Self-play trainer: at most one idle-triggered sweep per session
    // (the settings toggle can still trigger a fresh sweep on demand).
    private var selfPlayRanThisSession = false

    /** Last outcome hint, newest first (TapPilot's TaskRunState.lastOutcomeHint). */
    var lastOutcomeHint: String = ""
        private set

    /** Summary of the most recently evaluated shot, if any. */
    var lastShotSummary: String = ""
        private set

    /** Latest diagnostic HUD state (also carried on every CarromTurnResult). */
    var lastHud: CarromTurnHudState? = null
        private set

    /** Playbook imported by [importCarromTrainingJson], if the bundle had one.
     *  The host must persist it — there is no knowledge store in this export. */
    var lastImportedPlaybook: TaskPlaybook? = null
        private set

    /** True when the last playTurn() slid the striker (probe). */
    var didSlideCarromThisStep: Boolean = false
        private set

    /** True while a 5-second turn is in progress. */
    val isTurnActive: Boolean get() = turnMemory.isTurnActive

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val before = settings
        settings = transform(before)
        // Self-play toggle just turned on: allow one fresh idle sweep.
        if (!before.carromSelfPlayEnabled && settings.carromSelfPlayEnabled) {
            selfPlayRanThisSession = false
        }
    }

    /**
     * One live-loop cycle. Feed a fresh screenshot of the game screen and
     * the capture time; the loop detects the turn, solves, probes inside
     * the 5s budget and fires through [dragPerformer].
     * (decideCarromOrAiAction + solveCarromAction, AgentCoordinator 1423-1503,
     * with the AI-planner branch replaced by NotCarromTurn.)
     */
    suspend fun playTurn(screenshot: Bitmap, nowMs: Long): CarromTurnResult {
        didSlideCarromThisStep = false
        if (!settings.carromPrecisionSolver) {
            turnMemory.resetTurn()
            lastHud = null
            return CarromTurnResult.NotCarromTurn(
                reason = "Carrom precision solver is off in settings.",
                hint = lastOutcomeHint
            )
        }
        val screenWidth = screenshot.width
        val screenHeight = screenshot.height
        val result = solveCarromAction(screenshot, screenWidth, screenHeight, nowMs)
        return when (val action = result.action) {
            is CarromStepAction.Drag -> {
                // The coordinator's step loop used to execute the
                // PlannedAction; here the loop performs the drag itself.
                val dragOk = dragPerformer(
                    action.drag.startX, action.drag.startY,
                    action.drag.endX, action.drag.endY,
                    action.drag.durationMs
                )
                if (dragOk && result.nextPendingCarromShotCandidate != null) {
                    pendingCarromShot = result.nextPendingCarromShotCandidate
                }
                if (action.isShot) {
                    CarromTurnResult.ShotFired(
                        drag = action.drag,
                        dragOk = dragOk,
                        wasSafetyShot = action.solution?.isSafetyShot == true,
                        wasSureShot = action.solution?.isSureShot == true,
                        pendingShotRegistered = dragOk &&
                            result.nextPendingCarromShotCandidate != null,
                        hud = lastHud,
                        hint = lastOutcomeHint
                    )
                } else {
                    CarromTurnResult.SliderMoved(
                        drag = action.drag,
                        dragOk = dragOk,
                        probeNumber = turnMemory.sliderMovesThisTurn,
                        hud = lastHud,
                        hint = lastOutcomeHint
                    )
                }
            }
            is CarromStepAction.Wait -> CarromTurnResult.Waiting(
                reason = action.reason,
                hud = lastHud,
                hint = lastOutcomeHint
            )
            is CarromStepAction.NotCarrom -> CarromTurnResult.NotCarromTurn(
                reason = "Local vision needs AI help.",
                diagnostic = action.diagnostic,
                aiPreCarromCounts = result.aiPreCarromCounts,
                hud = lastHud,
                hint = lastOutcomeHint
            )
            null -> CarromTurnResult.NotCarromTurn(
                reason = "Solver decided nothing.",
                hud = lastHud,
                hint = lastOutcomeHint
            )
        }
    }

    // Local zero-API Carrom solver for this cycle.
    // (solveCarromAction, AgentCoordinator 1448-1503, minus the
    // isActiveCarromMatchScreen / interactiveNodeCount gates — the host
    // only feeds game screenshots, and the vision outcome answers the
    // "is this a board" question itself.)
    private suspend fun solveCarromAction(
        screenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        nowCarromMs: Long
    ): CarromActionResult {
        val currentMem = memoryStore.memory.value
        val effectiveRule = resolveCarromRuleMode(goal, settings.carromRuleMode)
        val isFreestyle = effectiveRule == CarromRuleMode.FREESTYLE
        val myCoinColor = if (effectiveRule == CarromRuleMode.BLACK_ONLY) CoinColor.BLACK else CoinColor.WHITE
        val allowRepositionByBudget = !turnMemory.isTurnActive ||
            turnMemory.canAffordAnotherSliderMove(nowCarromMs, isLiveStream)
        val cycleStartMs = System.currentTimeMillis()
        val outcome = CarromVisionDetector.analyzeAndSolve(
            screenshot = screenshot,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            myCoinColor = myCoinColor,
            consecutiveOfflineWaits = carromOfflineWaits,
            shotMemory = currentMem,
            allowStrikerRepositioning = allowRepositionByBudget,
            usePhysicsRollout = settings.usePhysicsRollout,
            useSubPixelRefinement = settings.useSubPixelCircleRefinement,
            isFreestyle = isFreestyle,
            queenPendingCover = queenPendingCover,
            isLiveStream = isLiveStream,
            isTurnActive = turnMemory.isTurnActive,
            difficulty = settings.carromDifficulty
        )
        turnMemory.recordCycleDuration(System.currentTimeMillis() - cycleStartMs)
        evaluatePreviousCarromShot(screenshot, screenWidth, screenHeight, outcome, currentMem)
        return handleCarromOutcome(
            outcome, currentMem, nowCarromMs, screenWidth, screenHeight,
            isFreestyle, myCoinColor, allowRepositionByBudget
        )
    }

    // Fresh-rack detection, previous-shot outcome evaluation (power /
    // deflection / scratch learning), and the queen-cover check.
    // (evaluatePreviousCarromShot, AgentCoordinator 1504-1708, verbatim
    // except: obs.snapshot -> the screenshot params; _carromMemory ->
    // memoryStore.memory; carromShotMemoryStore -> memoryStore; the
    // scope.launch around profile activation is gone — this is already a
    // suspend fun, so the store calls await directly; the playbook-learning
    // block (AgentKnowledgeStore.recordStepLearning) is replaced by the
    // onShotLesson callback — see the class header.)
    private suspend fun evaluatePreviousCarromShot(
        screenshot: Bitmap,
        screenWidth: Int,
        screenHeight: Int,
        outcome: CarromVisionOutcome,
        currentCarromMem: CarromMemoryData
    ) {

        // New-match detection (fresh rack): the board is
        // full again (9+9+1 = 19 coins; >= 18 tolerates
        // one detection miss) after having been seen
        // depleted, and shots were played in the previous
        // match. Two things happen, once per match:
        // 1. Per-match calibration resets (friction /
        //    power / jaw buffer / slider gain back to
        //    neutral) so the previous board's tuning
        //    never leaks into the new board.
        // 2. Auto-Snap profile match: the live board
        //    geometry is matched against the saved named
        //    profiles; a matching board is activated
        //    (its own learned table + calibration).
        //    A board that matches NOTHING keeps
        //    auto-detection — never a wrong profile.
        val outcomePieces = when (outcome) {
            is CarromVisionOutcome.SolvedShot -> outcome.boardPieces
            is CarromVisionOutcome.WaitStillnessOrTurn -> outcome.boardPieces
            is CarromVisionOutcome.NeedsAiVision -> outcome.boardPieces
        }
        val totalCoinsOnBoard = outcomePieces.count { it.color != CoinColor.STRIKER }
        if (totalCoinsOnBoard >= 18) {
            if (currentCarromMem.matchCalibration.shotsThisMatch > 0 && carromSawDepletedBoard) {
                carromSawDepletedBoard = false
                queenPendingCover = false
                val liveGeometry = runCatching {
                    CarromVisionDetector.autoDetectCalibrationFromBitmap(screenshot)
                }.getOrNull()
                val matchedProfile = liveGeometry?.let {
                    BoardProfileMatcher.matchProfile(it, currentCarromMem.boardProfiles)
                }
                if (matchedProfile != null &&
                    matchedProfile.profile.profileName != currentCarromMem.activeBoardProfileName
                ) {
                    memoryStore.activateBoardProfile(matchedProfile.profile.profileName)
                } else {
                    memoryStore.resetMatchCalibration()
                }
            }
        } else if (totalCoinsOnBoard in 1..17) {
            carromSawDepletedBoard = true
        }

        // Evaluate the previous Carrom shot once coins have stopped moving
        val isMovingWait = outcome is CarromVisionOutcome.WaitStillnessOrTurn &&
            outcome.reason.contains("motion", ignoreCase = true)
        val prevShot = pendingCarromShot
        if (prevShot != null && !isMovingWait) {
            val settledPieces = when (outcome) {
                is CarromVisionOutcome.SolvedShot -> outcome.boardPieces
                is CarromVisionOutcome.WaitStillnessOrTurn -> outcome.boardPieces
                is CarromVisionOutcome.NeedsAiVision -> outcome.boardPieces
            }
            val postCounts = if (settledPieces.isNotEmpty()) {
                CarromShotMemoryStore.countBoardPieces(settledPieces, prevShot.myCoinColor)
            } else {
                CarromVisionDetector.inspectBoardCounts(
                    screenshot = screenshot,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    myCoinColor = prevShot.myCoinColor,
                    calibration = currentCarromMem.boardCalibration
                )
            }
            val strikerStayedPut = outcome is CarromVisionOutcome.SolvedShot &&
                outcome.detectedStriker != null &&
                lastShotAttemptStrikerX != null &&
                kotlin.math.abs(outcome.detectedStriker.center.x - lastShotAttemptStrikerX!!) <= 16f
            val coinsUnmoved = outcome is CarromVisionOutcome.SolvedShot &&
                postCounts == prevShot.preCounts &&
                lastShotPrePieces.isNotEmpty() &&
                lastShotPrePieces.filter { it.color != CoinColor.STRIKER }.all { pre ->
                    outcome.boardPieces.any { cur ->
                        cur.color == pre.color &&
                            kotlin.math.hypot(cur.center.x - pre.center.x, cur.center.y - pre.center.y) <= outcome.board.coinRadiusPx * 0.55f
                    }
                }
            if (strikerStayedPut && coinsUnmoved && consecutiveUnregisteredCarromShots < 3) {
                pendingCarromShot = null
                consecutiveUnregisteredCarromShots++
                lastOutcomeHint = "[Carrom shot drag left board static (attempt #$consecutiveUnregisteredCarromShots/3) — recalibrating striker grab Y/duration] $lastOutcomeHint"
            } else {
                consecutiveUnregisteredCarromShots = 0
                lastShotAttemptStrikerX = null
                lastShotPrePieces = emptyList()
                if (postCounts != null) {
                    // Vision-error guard (miss-cause
                    // learning): record the outcome only
                    // when the post-shot detection is
                    // stable across 2 frames — an
                    // unstable detection would corrupt
                    // the learned table with wrong
                    // counts. Retried next cycle (off
                    // the turn clock, never delaying
                    // the next shot's 5s window);
                    // dropped unrecorded after 5
                    // unstable cycles.
                    val postStable = CarromVisionDetector.isDetectionStableAcrossTwoFrames
                    if (!postStable) {
                        pendingEvalStaleCycles++
                        if (pendingEvalStaleCycles > 5) {
                            pendingCarromShot = null
                            pendingEvalStaleCycles = 0
                            lastOutcomeHint = "[Carrom outcome not recorded — vision never stabilised across 2 frames] $lastOutcomeHint"
                        }
                    } else {
                        pendingEvalStaleCycles = 0
                        // Miss-cause geometry: only when
                        // the pre-shot detection was ALSO
                        // stable across 2 frames (frozen
                        // in the record at fire time) and
                        // this outcome carries the board.
                        // Otherwise counts-only recording
                        // (coarse rules, UNKNOWN cause).
                        val missInput: MissCauseInput? =
                            if (prevShot.hasSolverGeometry && prevShot.preDetectionStable &&
                                outcome is CarromVisionOutcome.SolvedShot && settledPieces.isNotEmpty()
                            ) {
                                val board = outcome.board
                                val prePx = Point2D(
                                    board.left + prevShot.coinNormX * board.width,
                                    board.top + prevShot.coinNormY * board.height
                                )
                                val postPiece = settledPieces
                                    .filter { it.color == prevShot.targetCoinColor }
                                    .minByOrNull { kotlin.math.hypot(it.center.x - prePx.x, it.center.y - prePx.y) }
                                    ?.takeIf { kotlin.math.hypot(it.center.x - prePx.x, it.center.y - prePx.y) <= board.width * 0.25f }
                                val postNorm = postPiece?.let {
                                    Point2D(
                                        ((it.center.x - board.left) / board.width).coerceIn(0f, 1f),
                                        ((it.center.y - board.top) / board.height).coerceIn(0f, 1f)
                                    )
                                }
                                val pocketPx = board.pockets[prevShot.pocketIndex.coerceIn(0, 3)]
                                MissCauseInput(
                                    preCoinNorm = Point2D(prevShot.coinNormX, prevShot.coinNormY),
                                    postCoinNorm = postNorm,
                                    pocketNorm = Point2D(
                                        ((pocketPx.x - board.left) / board.width).coerceIn(0f, 1f),
                                        ((pocketPx.y - board.top) / board.height).coerceIn(0f, 1f)
                                    ),
                                    coinRadiusNorm = (board.coinRadiusPx / board.width).coerceAtLeast(0.005f),
                                    pocketRadiusNorm = (board.pocketRadiusPx / board.width).coerceAtLeast(0.005f)
                                )
                            } else null
                        val eval = memoryStore.recordShotOutcome(prevShot, postCounts, missCauseInput = missInput)
                        pendingCarromShot = null
                        if (eval.outcomeType == CarromShotOutcomeType.POCKETED_QUEEN &&
                            !prevShot.isFreestyle &&
                            postCounts.myCount > 0
                        ) {
                            queenPendingCover = true
                        } else {
                            queenPendingCover = false
                        }
                        lastOutcomeHint = "${eval.summaryMessage} $lastOutcomeHint"
                        lastShotSummary = eval.summaryMessage
                        if (eval.learnedTacticLesson != null || eval.learnedFoulLesson != null) {
                            onShotLesson?.invoke(eval.learnedTacticLesson, eval.learnedFoulLesson)
                        }
                    }
                }
            }
        } else if (prevShot != null && isMovingWait) {
            consecutiveUnregisteredCarromShots = 0
        }
        if (queenPendingCover) {
            val visiblePieces = when (outcome) {
                is CarromVisionOutcome.SolvedShot -> outcome.boardPieces
                is CarromVisionOutcome.WaitStillnessOrTurn -> outcome.boardPieces
                is CarromVisionOutcome.NeedsAiVision -> outcome.boardPieces
            }
            if (visiblePieces.any { it.color == CoinColor.QUEEN }) {
                queenPendingCover = false
            }
        }
    }

    // Dispatch on the Carrom vision outcome.
    // (handleCarromOutcome, AgentCoordinator 1709-1777, verbatim except:
    // PlannedAction WAIT -> CarromStepAction.Wait; the AI-fallback return
    // (CarromActionResult(null, ...)) -> CarromStepAction.NotCarrom with
    // the diagnostic; _carromTurnHud -> lastHud.)
    private suspend fun handleCarromOutcome(
        outcome: CarromVisionOutcome,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        isFreestyle: Boolean,
        myCoinColor: CoinColor,
        allowRepositionByBudget: Boolean
    ): CarromActionResult {
        var aiPreCarromCounts: BoardPieceCounts? = null
        return when (outcome) {
            is CarromVisionOutcome.WaitStillnessOrTurn -> {
                carromOfflineWaits++
                pendingSliderVerify = null
                if (!turnMemory.isTurnActive) {
                    turnMemory.resetTurn()
                    // Diagnostic HUD: show the wait step on screen instead
                    // of hiding the HUD, so a "bot does nothing" report can
                    // be diagnosed from the phone (outcome + reason visible).
                    lastHud = turnMemory.buildHudState(
                        nowMs = nowCarromMs,
                        stage = TurnProbeStage.WAITING,
                        detail = outcome.reason.take(90),
                        tracedPieces = outcome.boardPieces,
                        forceActive = true
                    )
                }
                CarromActionResult(CarromStepAction.Wait(outcome.reason), false, null, null)
            }
            is CarromVisionOutcome.SolvedShot -> {
                handleSolvedShot(
                    outcome, currentCarromMem,
                    nowCarromMs, screenWidth, screenHeight,
                    isFreestyle, myCoinColor, allowRepositionByBudget
                )
            }
            is CarromVisionOutcome.NeedsAiVision -> {
                carromOfflineWaits = 0
                pendingSliderVerify = null
                turnMemory.resetTurn()
                // Diagnostic HUD: keep the "needs AI vision" step visible
                // on screen with its diagnostic instead of hiding the HUD.
                lastHud = turnMemory.buildHudState(
                    nowMs = nowCarromMs,
                    stage = TurnProbeStage.NEEDS_AI,
                    detail = outcome.diagnostic.take(90),
                    tracedPieces = outcome.boardPieces,
                    forceActive = true
                )
                lastOutcomeHint = "[Carrom Local Vision: ${outcome.diagnostic}] $lastOutcomeHint"
                if (outcome.boardPieces.isNotEmpty()) {
                    aiPreCarromCounts = CarromShotMemoryStore.countBoardPieces(outcome.boardPieces, myCoinColor)
                }
                CarromActionResult(CarromStepAction.NotCarrom(outcome.diagnostic), false, null, aiPreCarromCounts)
            }
        }
    }

    // (handleSolvedShot, AgentCoordinator 1778-1806, verbatim except the
    // PlannedAction -> CarromStepAction.Drag mapping.)
    private suspend fun handleSolvedShot(
        outcome: CarromVisionOutcome.SolvedShot,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        isFreestyle: Boolean,
        myCoinColor: CoinColor,
        allowRepositionByBudget: Boolean
    ): CarromActionResult {
        return when (val probeResult = probeCarromShot(
            outcome, currentCarromMem, nowCarromMs,
            screenWidth, screenHeight, isFreestyle, myCoinColor, allowRepositionByBudget
        )) {
            is CarromShotProbeResult.Slide ->
                CarromActionResult(CarromStepAction.Drag(probeResult.drag, isShot = false, solution = null), true, null, null)
            is CarromShotProbeResult.Shoot -> {
                val shotResult = fireCarromShot(
                    outcome, currentCarromMem,
                    nowCarromMs, screenWidth, screenHeight,
                    isFreestyle, myCoinColor, probeResult.probe
                )
                CarromActionResult(shotResult.action, true, shotResult.pendingShot, null)
            }
        }
    }

    // Probe phase of a solved Carrom shot: in-place solve, striker sweep,
    // and (when needed) the slider-probe drag. Returns the slide drag
    // or the context the fire phase needs.
    // (probeCarromShot, AgentCoordinator 1807-2098, verbatim except:
    // obs/analysis/state -> params/fields; the PlannedAction DRAG ->
    // CarromDrag; _carromTurnHud -> lastHud; carromShotMemoryStore ->
    // memoryStore.)
    private suspend fun probeCarromShot(
        outcome: CarromVisionOutcome.SolvedShot,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        isFreestyle: Boolean,
        myCoinColor: CoinColor,
        allowRepositionByBudget: Boolean
    ): CarromShotProbeResult {

        carromOfflineWaits = 0
        turnMemory.ensureTurnStarted(nowCarromMs)

        val striker = outcome.detectedStriker
        val calProfile = currentCarromMem.boardCalibration.takeIf { it.enabled }
        val sliderKnob = outcome.detectedSliderKnob ?: run {
            if (striker != null && calProfile != null) {
                val sliderLeftPx = calProfile.sliderTrackLeftNorm * screenWidth
                val sliderRightPx = calProfile.sliderTrackRightNorm * screenWidth
                val sliderY = calProfile.sliderTrackYNorm * screenHeight
                val strikerMargin = outcome.board.strikerRadiusPx * 2.1f
                val playableSpan = (outcome.board.width - strikerMargin * 2f).coerceAtLeast(40f)
                val strikerNorm = ((striker.center.x - (outcome.board.left + strikerMargin)) / playableSpan)
                    .coerceIn(0f, 1f)
                com.example.game.Point2D(
                    sliderLeftPx + strikerNorm * (sliderRightPx - sliderLeftPx),
                    sliderY
                )
            } else {
                null
            }
        }

        // Measure slider gain feedback from previous striker slide
        var observedGainThisStep: Float? = null
        val verifyState = pendingSliderVerify
        if (verifyState != null && striker != null) {
            val actualDelta = striker.center.x - verifyState.preStrikerX
            if (kotlin.math.abs(verifyState.commandedDeltaX) > 18f &&
                kotlin.math.abs(actualDelta) > 8f
            ) {
                val observedGain = (actualDelta / verifyState.commandedDeltaX).coerceIn(0.65f, 1.45f)
                observedGainThisStep = observedGain
                memoryStore.updateSliderGain(observedGain)
            }
        }

        // Save pre-slide / post-slide scan into Turn Memory BEFORE selecting next action
        turnMemory.recordObservation(
            nowMs = nowCarromMs,
            pieces = outcome.boardPieces,
            striker = striker,
            sliderKnob = sliderKnob,
            evaluatedSolution = outcome.solution,
            observedSliderGain = observedGainThisStep
        )

        // 5s hard rule: once the turn clock passes 4.5s, no more
        // probing or correcting — this cycle MUST fire (best sure
        // shot remembered so far, else a foul-safe safety shot),
        // with no pre-shot pause.
        val deadlineFire = turnMemory.mustFireNow(nowCarromMs)

        val consensusPieces = turnMemory.buildConsensusPieces(
            latestPieces = outcome.boardPieces,
            coinRadiusPx = outcome.board.coinRadiusPx
        )
        val fusedPiecesWithStriker = if (striker != null) {
            consensusPieces + striker
        } else {
            consensusPieces
        }

        // Step 1 of Turn: Full-board pre-scan builds top-4 physics-ranked baseline X queue
        if (!turnMemory.isProbeQueueInitialized) {
            val rankedSpots = CarromGeometrySolver.buildRankedBaselineProbeQueue(
                board = outcome.board,
                pieces = fusedPiecesWithStriker,
                myCoinColor = myCoinColor,
                existingStriker = striker,
                shotMemory = currentCarromMem,
                maxProbes = CarromTurnMemory.MAX_BASELINE_PROBES,
                isFreestyle = isFreestyle,
                queenPendingCover = queenPendingCover
            )
            turnMemory.initializeProbeQueueIfNeeded(rankedSpots)
        }

        val turnGain = turnMemory.effectiveTurnSliderGain(
            currentCarromMem.matchCalibration.sliderGain
        )
        val canAffordSlide = sliderKnob != null &&
            striker != null &&
            turnMemory.canAffordAnotherSliderMove(nowCarromMs, isLiveStream)

        // Step 2: Physics-verify the current striker spot (collision-assisted pots WITH 0 foul count as Sure Shots!)
        val inPlaceSol = if (striker != null) {
            CarromGeometrySolver.solveBestShot(
                board = outcome.board,
                pieces = fusedPiecesWithStriker,
                myCoinColor = myCoinColor,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                existingStriker = striker,
                canRepositionStriker = false,
                shotMemory = currentCarromMem,
                usePhysicsRollout = settings.usePhysicsRollout,
                isFreestyle = isFreestyle,
                queenPendingCover = queenPendingCover,
                difficulty = settings.carromDifficulty
            )
        } else {
            null
        }

        val isVerifiedSureHere = inPlaceSol != null && inPlaceSol.isSureShot

        val minCorrectionPx = (outcome.board.width * 0.032f).coerceIn(18f, 34f)
        val needsClosedLoopCorrection = !deadlineFire &&
            !isVerifiedSureHere &&
            canAffordSlide &&
            turnMemory.remainingMs(nowCarromMs) > 2_300L &&
            settings.useClosedLoopSliderVerify &&
            verifyState != null &&
            verifyState.correctionAttempts < 1 &&
            striker != null &&
            kotlin.math.abs(verifyState.targetStrikerX - striker.center.x) > minCorrectionPx &&
            !turnMemory.isSliderStalledOrOscillating(verifyState.targetStrikerX, striker.center.x)

        // Striker sweep (try-and-see): simulate
        // every baseline position for the current
        // best coin+pocket, once per board layout
        // (key changes when any piece moves).
        // The probe step IS this test — slide to
        // the first position that pots with sure
        // confidence; fall back to the next
        // untested probe spot when none pots.
        var sweepFirstPotsX: Float? = null
        var sweepHudLine: String? = null
        if (!deadlineFire && !isVerifiedSureHere && !needsClosedLoopCorrection && striker != null && outcome.solution != null) {
            val sweepKey = buildString {
                val sol = outcome.solution
                append(sol.targetCoin.center.x.toInt()); append(','); append(sol.targetCoin.center.y.toInt())
                append('|'); append(sol.pocketIndex)
                fusedPiecesWithStriker.sortedBy { it.center.x }.forEach { p ->
                    append('|'); append(p.center.x.toInt()); append(':'); append(p.center.y.toInt())
                }
            }
            if (sweepKey != carromSweepKey) {
                carromSweepKey = sweepKey
                carromSweepEntries = CarromGeometrySolver.sweepStrikerAim(
                    board = outcome.board,
                    coin = outcome.solution.targetCoin,
                    pocketIndex = outcome.solution.pocketIndex,
                    pieces = fusedPiecesWithStriker,
                    myCoinColor = myCoinColor,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    existingStriker = striker,
                    shotMemory = currentCarromMem,
                    usePhysicsRollout = settings.usePhysicsRollout,
                    isFreestyle = isFreestyle,
                    maxPositions = 9
                )
            }
            if (carromSweepEntries.isNotEmpty()) {
                val pots = carromSweepEntries.filter {
                    it.outcome == CarromGeometrySolver.StrikerSweepOutcome.POTS && it.solution?.isSureShot == true
                }
                sweepFirstPotsX = pots.firstOrNull()?.strikerPos?.x
                val potsXs = pots.joinToString(",") { ((it.strikerNormX * 100).toInt()).toString() }
                val missN = carromSweepEntries.count { it.outcome == CarromGeometrySolver.StrikerSweepOutcome.MISSES }
                val foulN = carromSweepEntries.count { it.outcome == CarromGeometrySolver.StrikerSweepOutcome.FOUL_RISK }
                sweepHudLine = "সুইপ ${carromSweepEntries.size}টি: পকেটে যাবে ${potsXs.ifEmpty { "—" }} • যাবে না $missN • ফাউল-ঝুঁকি $foulN"
            }
        }

        var nextProbeTargetX: Float? = null
        var returningToBestProbe = false
        if (!deadlineFire && !isVerifiedSureHere && !needsClosedLoopCorrection && striker != null) {
            // Reject current spot because collision missed/fouled or no Sure Shot found here
            val rejectWhy = when {
                inPlaceSol == null -> "no-line"
                inPlaceSol.rejectedDueToCollisionOrFoul -> "collision-miss/foul"
                else -> "unverified"
            }
            turnMemory.markSpotRejected(striker.center.x, rejectWhy)
            if (canAffordSlide) {
                // Sweep first: the first striker
                // position that pots with sure
                // confidence wins; otherwise the
                // next untested probe spot.
                nextProbeTargetX = sweepFirstPotsX
                    ?: turnMemory.nextUntestedProbeSpot(striker.center.x)?.strikerX
                if (nextProbeTargetX == null) {
                    val returnX = turnMemory.bestEvaluatedSpotToReturnTo(
                        currentStrikerX = striker.center.x,
                        currentInPlaceConfidence = inPlaceSol?.confidenceScore ?: 0f,
                        minShiftPx = minCorrectionPx
                    )
                    if (returnX != null) {
                        nextProbeTargetX = returnX
                        returningToBestProbe = true
                    }
                }
            }
        }

        val slideTargetX: Float? = when {
            isVerifiedSureHere -> null
            needsClosedLoopCorrection -> verifyState?.targetStrikerX
            nextProbeTargetX != null &&
                striker != null &&
                !turnMemory.isSliderStalledOrOscillating(nextProbeTargetX, striker.center.x) ->
                nextProbeTargetX
            else -> null
        }

        val slideDrag: CarromDrag? = if (slideTargetX != null && striker != null && sliderKnob != null) {
            if (returningToBestProbe) {
                turnMemory.markReturnedToBestSpot()
            }
            val rawDelta = slideTargetX - striker.center.x
            val maxSliderDelta = (outcome.board.width * 0.72f)
                .coerceIn(380f, (screenWidth * 0.85f).coerceAtLeast(380f))
            val calibratedDelta = (rawDelta / turnGain).coerceIn(-maxSliderDelta, maxSliderDelta)
            val targetKnobEndX = (sliderKnob.x + calibratedDelta)
                .toInt()
                .coerceIn(28, (screenWidth - 28).coerceAtLeast(29))

            didSlideCarromThisStep = true
            movedStrikerThisTurn = true
            turnMemory.recordSliderMoveDispatched(striker.center.x)
            pendingSliderVerify = PendingSliderVerify(
                targetStrikerX = slideTargetX,
                preStrikerX = striker.center.x,
                commandedDeltaX = (targetKnobEndX - sliderKnob.x.toInt()).toFloat(),
                correctionAttempts = if (needsClosedLoopCorrection) (verifyState?.correctionAttempts ?: 0) + 1 else 0
            )
            val remSec = turnMemory.remainingMs(nowCarromMs) / 1000f
            val previewSol = inPlaceSol ?: outcome.solution
            val previewAim = "${previewSol.targetCoin.color.name} -> ${CarromMemoryData.pocketName(previewSol.pocketIndex)} (${previewSol.cutAngleDeg.toInt()}°)" +
                (sweepHudLine?.let { " • $it" } ?: "")
            lastHud = turnMemory.buildHudState(
                nowMs = nowCarromMs,
                stage = TurnProbeStage.PROBING_SLIDER,
                detail = "Probe ${turnMemory.sliderMovesThisTurn}/${turnMemory.totalPlannedProbes.coerceAtLeast(1)} (${turnMemory.rejectedProbeCount} rej)",
                board = outcome.board,
                tracedPieces = fusedPiecesWithStriker,
                strikerPos = striker.center,
                solution = previewSol,
                aimSummary = previewAim
            )
            CarromDrag(
                startX = sliderKnob.x,
                startY = sliderKnob.y,
                endX = targetKnobEndX.toFloat(),
                endY = sliderKnob.y,
                // Human-like timing: vary the slide duration slightly
                // so the probe does not move like a metronome.
                durationMs = (if (kotlin.math.abs(calibratedDelta) < 120f) 210L else 280L) +
                    Random.Default.nextLong(-25L, 26L)
            )
        } else {
            null
        }
        if (slideDrag != null) {
            return CarromShotProbeResult.Slide(slideDrag)
        }
        return CarromShotProbeResult.Shoot(
            CarromShotProbe(
                striker = striker,
                inPlaceSol = inPlaceSol,
                isVerifiedSureHere = isVerifiedSureHere,
                deadlineFire = deadlineFire,
                consensusPieces = consensusPieces,
                fusedPiecesWithStriker = fusedPiecesWithStriker
            )
        )
    }

    // Fire phase of a solved Carrom shot: pick the final solution (sure
    // shot / deadline fire / safety), hold the safety when a sure shot
    // may still arrive, then dispatch the drag.
    // (fireCarromShot, AgentCoordinator 2099-2380, verbatim except:
    // obs/analysis/state -> params/fields; the PlannedAction DRAG/WAIT ->
    // CarromStepAction.Drag/Wait carrying a CarromDrag; _carromTurnHud ->
    // lastHud; the humanPauseMs delay stays — it runs here, before the
    // drag is returned to playTurn for execution.)
    private suspend fun fireCarromShot(
        outcome: CarromVisionOutcome.SolvedShot,
        currentCarromMem: CarromMemoryData,
        nowCarromMs: Long,
        screenWidth: Int,
        screenHeight: Int,
        isFreestyle: Boolean,
        myCoinColor: CoinColor,
        probe: CarromShotProbe
    ): CarromShotResult {
        var pendingShot: PendingCarromShotRecord? = null

        // Step 3: Sure Shot LOCKED (including clean carom pots),
        // or the 4.5s hard fire deadline (best sure shot
        // remembered, else a foul-safe safety shot), or the
        // strict deadline fallback (sure / break / safety only).
        pendingSliderVerify = null
        val didSlideBeforeThisShot = movedStrikerThisTurn
        movedStrikerThisTurn = false

        val finalSol = if (probe.isVerifiedSureHere && probe.inPlaceSol != null) {
            probe.inPlaceSol
        } else if (probe.deadlineFire) {
            // Past the 4.5s fire deadline: do NOT re-solve (that
            // costs time) — fire the best sure shot remembered
            // from this turn, else a foul-safe safety shot.
            // Never a low-confidence gamble pot.
            turnMemory.bestSureShotThisTurn(
                currentStrikerX = probe.striker?.center?.x,
                maxShiftPx = outcome.board.strikerRadiusPx * 1.6f
            )
                ?: CarromGeometrySolver.solveSafetyShot(
                    board = outcome.board,
                    pieces = outcome.boardPieces,
                    myCoinColor = myCoinColor,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    existingStriker = probe.striker,
                    shotMemory = currentCarromMem,
                    isFreestyle = isFreestyle
                )
                ?: turnMemory.solveCombinedFinalShot(
                    board = outcome.board,
                    latestPieces = outcome.boardPieces,
                    striker = probe.striker,
                    myCoinColor = myCoinColor,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    shotMemory = currentCarromMem,
                    usePhysicsRollout = settings.usePhysicsRollout,
                    fallbackSolution = outcome.solution,
                    isFreestyle = isFreestyle,
                    queenPendingCover = queenPendingCover,
                    difficulty = settings.carromDifficulty
                ) ?: outcome.solution
        } else {
            turnMemory.solveCombinedFinalShot(
                board = outcome.board,
                latestPieces = outcome.boardPieces,
                striker = probe.striker,
                myCoinColor = myCoinColor,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                shotMemory = currentCarromMem,
                usePhysicsRollout = settings.usePhysicsRollout,
                fallbackSolution = outcome.solution,
                isFreestyle = isFreestyle,
                queenPendingCover = queenPendingCover,
                difficulty = settings.carromDifficulty
            ) ?: outcome.solution.takeIf { it.isSureShot }
                ?: CarromGeometrySolver.solveSafetyShot(
                    board = outcome.board,
                    pieces = outcome.boardPieces,
                    myCoinColor = myCoinColor,
                    screenWidth = screenWidth,
                    screenHeight = screenHeight,
                    existingStriker = probe.striker,
                    shotMemory = currentCarromMem,
                    isFreestyle = isFreestyle
                ) ?: outcome.solution
        }

        val hudStage = when {
            finalSol.isCollisionAssistedSureShot -> TurnProbeStage.CAROM_SURE_SHOT
            finalSol.isSureShot -> TurnProbeStage.LOCKED_SURE_SHOT
            else -> TurnProbeStage.FALLBACK_FIRE
        }

        val retryYOffset = when (consecutiveUnregisteredCarromShots) {
            1 -> -outcome.board.strikerRadiusPx * 0.40f
            2 -> outcome.board.strikerRadiusPx * 0.40f
            else -> 0f
        }
        val shootStartX = probe.striker?.center?.x ?: finalSol.strikerPos.x
        val shootStartY = ((probe.striker?.center?.y ?: finalSol.strikerPos.y) + retryYOffset)
            .coerceIn(outcome.board.top + outcome.board.height * 0.68f, outcome.board.bottom - outcome.board.strikerRadiusPx)
        val strikerNormX = ((shootStartX - outcome.board.left) / outcome.board.width).coerceIn(0f, 1f)
        val (coinRowZone, coinColZone) = CarromMemoryData.coinZoneFor(finalSol.targetCoin.center, outcome.board)

        val countSource = probe.consensusPieces.ifEmpty { outcome.boardPieces }
        lastShotPrePieces = countSource
        lastShotAttemptStrikerX = shootStartX
        // Record the REAL shot geometry for distance-banded
        // learning: normalized coin position, true total path
        // length (striker -> coin -> pocket) / board width, and
        // the pull multiplier actually used for this shot.
        val shotCoinNormX = ((finalSol.targetCoin.center.x - outcome.board.left) / outcome.board.width).coerceIn(0f, 1f)
        val shotCoinNormY = ((finalSol.targetCoin.center.y - outcome.board.top) / outcome.board.height).coerceIn(0f, 1f)
        val shotDistRatio = if (finalSol.totalDistance > 0f) {
            (finalSol.totalDistance / outcome.board.width).coerceIn(0.15f, 1.8f)
        } else {
            ((kotlin.math.hypot(finalSol.ghostBall.x - shootStartX, finalSol.ghostBall.y - shootStartY) +
                kotlin.math.hypot(finalSol.targetPocket.x - finalSol.targetCoin.center.x, finalSol.targetPocket.y - finalSol.targetCoin.center.y)) / outcome.board.width).coerceIn(0.15f, 1.8f)
        }
        // Safety touches are deliberate non-scoring moves: never
        // record them as pot attempts, or the memory would learn
        // that a soft touch "misses" its pocket.
        if (countSource.isNotEmpty() && !finalSol.isSafetyShot) {
            pendingShot = PendingCarromShotRecord(
                myCoinColor = myCoinColor,
                targetCoinColor = finalSol.targetCoin.color,
                pocketIndex = finalSol.pocketIndex,
                cutAngleDeg = finalSol.cutAngleDeg,
                strikerNormalizedX = strikerNormX,
                coinRowZone = coinRowZone,
                coinColZone = coinColZone,
                powerStyle = finalSol.powerStyle,
                movedStrikerWithSlider = didSlideBeforeThisShot,
                isBankShot = finalSol.isBankShot,
                isComboShot = finalSol.isComboShot,
                isBreakShot = finalSol.isBreakShot,
                preCounts = CarromShotMemoryStore.countBoardPieces(countSource, myCoinColor),
                isFreestyle = isFreestyle,
                coinNormX = shotCoinNormX,
                coinNormY = shotCoinNormY,
                distRatio = shotDistRatio,
                observedPullScale = finalSol.pullMultiplier,
                // Vision-error guard: freeze
                // whether the pre-shot
                // detection was stable across
                // 2 frames, so the miss-cause
                // classifier only trusts
                // geometry from a stable
                // pre-shot board.
                preDetectionStable = CarromVisionDetector.isDetectionStableAcrossTwoFrames,
                hasSolverGeometry = true
            )
        }

        val rawDx = finalSol.dragEnd.x - finalSol.strikerPos.x
        val rawDy = finalSol.dragEnd.y - finalSol.strikerPos.y
        // 5s sure-shot / no-foul rule: never rotate a
        // physics-verified shot's aim — even a 1.5-3 deg error
        // can turn a sure shot into a miss or a foul.
        // Human-likeness comes from timing jitter only;
        // difficulty still gates which shots are accepted.
        val aimErrDeg = 0f
        val (dx, dy) = if (aimErrDeg > 0f) {
            val errRad = Math.toRadians((Random.Default.nextFloat() * 2f * aimErrDeg - aimErrDeg).toDouble()).toFloat()
            val c = kotlin.math.cos(errRad)
            val s = kotlin.math.sin(errRad)
            (rawDx * c - rawDy * s) to (rawDx * s + rawDy * c)
        } else {
            rawDx to rawDy
        }
        val minBoundX = 28f
        val maxBoundX = (screenWidth - 28f).coerceAtLeast(29f)
        val minBoundY = 24f
        val maxBoundY = (screenHeight - maxOf(56f, screenHeight * 0.05f)).coerceAtLeast(25f)
        var pullScale = if (consecutiveUnregisteredCarromShots >= 3) 0.84f else 1f
        if (dx < -1e-3f && shootStartX + dx * pullScale < minBoundX) {
            pullScale = minOf(pullScale, ((minBoundX - shootStartX) / dx).coerceIn(0.2f, 1f))
        } else if (dx > 1e-3f && shootStartX + dx * pullScale > maxBoundX) {
            pullScale = minOf(pullScale, ((maxBoundX - shootStartX) / dx).coerceIn(0.2f, 1f))
        }
        if (dy < -1e-3f && shootStartY + dy * pullScale < minBoundY) {
            pullScale = minOf(pullScale, ((minBoundY - shootStartY) / dy).coerceIn(0.2f, 1f))
        } else if (dy > 1e-3f && shootStartY + dy * pullScale > maxBoundY) {
            pullScale = minOf(pullScale, ((maxBoundY - shootStartY) / dy).coerceIn(0.2f, 1f))
        }
        val shootEndX = (shootStartX + dx * pullScale).toInt().coerceIn(minBoundX.toInt(), maxBoundX.toInt())
        val shootEndY = (shootStartY + dy * pullScale).toInt().coerceIn(minBoundY.toInt(), maxBoundY.toInt())

        val finalAimSummary = "${finalSol.targetCoin.color.name} -> ${CarromMemoryData.pocketName(finalSol.pocketIndex)} (${finalSol.cutAngleDeg.toInt()}°) • ${finalSol.powerStyle.label} • ${(finalSol.confidenceScore * 100).toInt()}%" +
            if (outcome.turnBanner) " • TURN ✓" else ""
        lastHud = turnMemory.buildHudState(
            nowMs = nowCarromMs,
            stage = hudStage,
            detail = finalSol.reason.take(64),
            board = outcome.board,
            tracedPieces = probe.fusedPiecesWithStriker,
            strikerPos = Point2D(shootStartX, shootStartY),
            solution = finalSol,
            dragEndOverride = Point2D(shootEndX.toFloat(), shootEndY.toFloat()),
            aimSummary = finalAimSummary
        )

        // Early-fire rule (user correction 2026-11-21):
        // 5s is only the MAXIMUM deadline, never a
        // waiting period. A physics-verified sure
        // shot fires the moment it is found — no
        // artificial delay. The human-like pre-shot
        // pause (0.4-1.2s) is therefore SKIPPED for
        // sure shots; it only ever applied to
        // fallback shots, which fire at/near the
        // deadline where the pause is 0 anyway.
        // Human-likeness now comes only from the
        // drag-duration jitter below. Bound the
        // pause inside the 5s budget: always leave
        // >= 1500ms for the drag dispatch + gesture start.
        val remainingBeforeShotMs = turnMemory.remainingMs(nowCarromMs)
        val humanPauseMs = if (!finalSol.isSureShot && remainingBeforeShotMs > 2_500L && hudStage != TurnProbeStage.FALLBACK_FIRE && !probe.deadlineFire) {
            Random.Default.nextLong(400L, 1_201L)
                .coerceAtMost((remainingBeforeShotMs - 1_500L).coerceAtLeast(0L))
        } else {
            0L
        }
        val telemetry = turnMemory.formatTurnTelemetry(nowCarromMs, finalSol)
        turnMemory.resetTurn()

        val modeTag = if (isFreestyle) "Freestyle" else myCoinColor.name
        val pullDistPx = kotlin.math.hypot(
            (shootEndX - shootStartX).toDouble(),
            (shootEndY - shootStartY).toDouble()
        ).toFloat()
        val baseDurationMs = (360L + (pullDistPx * 0.45f).toLong()).coerceIn(370L, 520L)
        // Human-like timing: vary the pull duration slightly so
        // every shot does not take exactly the same time.
        val humanJitterMs = Random.Default.nextLong(-45L, 46L)
        val shotDurationMs = (when (consecutiveUnregisteredCarromShots) {
            1 -> (baseDurationMs + 60L).coerceAtMost(580L)
            2 -> (baseDurationMs + 100L).coerceAtMost(620L)
            else -> baseDurationMs
        } + humanJitterMs).coerceIn(340L, 640L)
        val shotAction: CarromStepAction = if (!probe.deadlineFire && finalSol.isSafetyShot && turnMemory.canAffordToWaitForSureShot(nowCarromMs)) {
            // 5s rule (Fix 2): a safety shot is only
            // the DEADLINE fallback. Before the 4.5s
            // deadline, HOLD it and wait one more
            // cycle when the wait still fits — a sure
            // shot may still arrive inside the
            // budget (sim case D3). No pre-shot
            // pause on the wait path; the deadline
            // path below still fires the safety at
            // 4.5s. The wait is gated: a WAIT costs
            // a fixed 1500ms in GestureExecutor plus
            // one more detect+solve cycle, so on a
            // slow device the safety fires now
            // instead of waiting past 5s (sim D2).
            lastHud = turnMemory.buildHudState(
                nowMs = nowCarromMs,
                stage = TurnProbeStage.WAITING,
                detail = "No sure shot yet — waiting for one (safety held for deadline)",
                board = outcome.board,
                tracedPieces = probe.fusedPiecesWithStriker,
                strikerPos = Point2D(shootStartX, shootStartY),
                solution = finalSol,
                aimSummary = finalAimSummary
            )
            CarromStepAction.Wait("No sure shot yet — holding the safety shot for the 4.5s deadline.")
        } else {
            if (humanPauseMs > 0L) delay(humanPauseMs)
            CarromStepAction.Drag(
                drag = CarromDrag(
                    startX = shootStartX,
                    startY = shootStartY,
                    endX = shootEndX.toFloat(),
                    endY = shootEndY.toFloat(),
                    durationMs = shotDurationMs
                ),
                isShot = true,
                solution = finalSol
            )
        }
        // Note: the original logged "Carrom ($modeTag) $telemetry: ${finalSol.reason}"
        // into the PlannedAction's memoryNote/reason; the telemetry is kept
        // in the ShotFired result's hint via lastOutcomeHint below.
        lastOutcomeHint = "[Carrom $modeTag $telemetry: ${finalSol.reason}] $lastOutcomeHint"
        return CarromShotResult(shotAction, pendingShot)
    }

    // (resolveCarromRuleMode, AgentCoordinator 3162-3180, verbatim.)
    private fun resolveCarromRuleMode(
        goal: String,
        configured: CarromRuleMode
    ): CarromRuleMode {
        return when {
            goal.contains("freestyle", ignoreCase = true) ||
                goal.contains("ফ্রি স্টাইল") ||
                goal.contains("ফ্রিস্টাইল") ||
                goal.contains("সব গুটি") -> CarromRuleMode.FREESTYLE
            goal.contains("only black", ignoreCase = true) ||
                goal.contains("disc pool black", ignoreCase = true) ||
                goal.contains("শুধু কালো") -> CarromRuleMode.BLACK_ONLY
            goal.contains("only white", ignoreCase = true) ||
                goal.contains("disc pool white", ignoreCase = true) ||
                goal.contains("শুধু সাদা") -> CarromRuleMode.WHITE_ONLY
            else -> configured
        }
    }

    // (tryLaunchPackage, AgentCoordinator 3181-3192, verbatim.)
    private fun tryLaunchPackage(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pm = context.packageManager
        val launchIntent = runCatching { pm.getLaunchIntentForPackage(packageName) }.getOrNull() ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching {
            context.startActivity(launchIntent)
            Log.i(TAG, "Direct-launched package: $packageName")
            true
        }.getOrDefault(false)
    }

    // (tryLaunchInstalledCarromApp, AgentCoordinator 3193-3206, verbatim
    // except the Context comes from the constructor; without one it
    // returns false.)
    fun tryLaunchInstalledCarromApp(): Boolean {
        val ctx = context ?: return false
        val candidatePackages = listOf(
            "com.miniclip.carrom",
            "com.miniclip.carrom.freestyle",
            "com.moonfrog.carromgold",
            "com.gametion.carromking",
            "com.appsoleutgames.carrom"
        )
        for (pkg in candidatePackages) {
            if (tryLaunchPackage(ctx, pkg)) {
                return true
            }
        }
        return false
    }

    // (exportCarromTrainingJson, AgentCoordinator 239-248. TapPilot looked
    // the playbook up in its playbook store; here the host supplies it via
    // the carromPlaybook constructor parameter, or null.)
    fun exportCarromTrainingJson(): String {
        return CarromShotMemoryStore.exportTrainingBundleJson(
            memory = memoryStore.memory.value,
            carromPlaybook = carromPlaybook
        )
    }

    // (importCarromTrainingJson, AgentCoordinator 250-267. TapPilot saved
    // the imported playbook into AgentKnowledgeStore; this export has no
    // knowledge store, so the playbook is exposed via lastImportedPlaybook
    // for the host to persist.)
    suspend fun importCarromTrainingJson(raw: String): Result<String> {
        return runCatching {
            val (importedMem, importedPb) = CarromShotMemoryStore.parseTrainingBundleJson(raw)
            memoryStore.replaceWithImported(importedMem)
            lastImportedPlaybook = importedPb
            val totalShots = maxOf(
                importedMem.statsByBucket.values.sumOf { it.attempts },
                importedMem.matchCalibration.shotsThisMatch
            )
            val totalPots = importedMem.statsByBucket.values.sumOf { it.successes }
            val blockedFouls = importedMem.blockedFoulCount()
            "Imported $totalShots shots ($totalPots pots, $blockedFouls blocked fouls)!"
        }
    }

    // Self-play trainer (idle): call when the app is sitting idle (launcher
    // / settings screen) and training is enabled — runs one sweep in the
    // background of the caller's coroutine so the learned table fills up
    // without ever touching a live match's 5-second budget. Once per
    // session; the settings toggle can trigger more (see updateSettings).
    // (Self-play trigger, AgentCoordinator 925-950, adapted: the launcher
    // check lives with the host, which decides when the app is idle.)
    // Returns the training summary, or null when no sweep ran.
    suspend fun maybeRunIdleSelfPlay(): String? {
        if (!settings.carromSelfPlayEnabled || selfPlayRanThisSession) return null
        selfPlayRanThisSession = true
        val report = memoryStore.runSelfPlayTraining(
            SelfPlayConfig(
                profileName = memoryStore.memory.value.activeBoardProfileName,
                maxShots = settings.carromSelfPlayShots
            )
        ) ?: return null
        val hint = "[Self-Play] Trained ${report.shotsRun} shots " +
            "(${report.sureShotsFound} sure, ${report.bankSureShotsFound} bank, " +
            "${report.comboSureShotsFound} combo) on '${report.profileName}' " +
            "in ${report.elapsedMs}ms."
        lastOutcomeHint = "$hint $lastOutcomeHint"
        return hint
    }

    // (saveBoardCalibration, AgentCoordinator ~269-276. TapPilot launched
    // this in its scope; here it awaits directly.)
    suspend fun saveBoardCalibration(profile: BoardCalibrationProfile) {
        // Named-profile save: upserts into the profile book and
        // activates it (solver + learned tables switch to this board).
        memoryStore.saveNamedBoardProfile(profile)
    }

    // ---- Extracted data classes (AgentCoordinator 399-421) ----
    // `action: PlannedAction?` became `action: CarromStepAction?` — the
    // coordinator's PlannedAction (DRAG/WAIT/...) does not exist in this
    // export; the step the solver decided is a drag, a wait, or "not a
    // carrom turn". Everything else is verbatim.

    private data class PendingSliderVerify(
        val targetStrikerX: Float,
        val preStrikerX: Float,
        val commandedDeltaX: Float,
        val correctionAttempts: Int
    )

    // What the local Carrom solver decided for this cycle.
    private data class CarromActionResult(
        val action: CarromStepAction?,
        val usedLocalCarromSolver: Boolean,
        val nextPendingCarromShotCandidate: PendingCarromShotRecord?,
        val aiPreCarromCounts: BoardPieceCounts?
    )

    // Context the fire phase needs from the probe phase of a solved shot.
    private data class CarromShotProbe(
        val striker: CarromPiece?,
        val inPlaceSol: CarromShotSolution?,
        val isVerifiedSureHere: Boolean,
        val deadlineFire: Boolean,
        val consensusPieces: List<CarromPiece>,
        val fusedPiecesWithStriker: List<CarromPiece>
    )

    private sealed interface CarromShotProbeResult {
        data class Slide(val drag: CarromDrag) : CarromShotProbeResult
        data class Shoot(val probe: CarromShotProbe) : CarromShotProbeResult
    }

    private data class CarromShotResult(
        val action: CarromStepAction,
        val pendingShot: PendingCarromShotRecord?
    )
}

/**
 * One drag the loop decided: slingshot/slider drag from (startX, startY)
 * to (endX, endY) over [durationMs] ms. The host's [dragPerformer] (see
 * [CarromLiveLoop]) executes it — in TapPilot this was a PlannedAction
 * with actionType DRAG executed by the step loop's GestureExecutor.
 */
data class CarromDrag(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val durationMs: Long
)

/**
 * What the loop decided for one [CarromLiveLoop.playTurn] cycle.
 * The host reads this and decides what to do next (feed another
 * screenshot, wait, or fall back to AI when it is not a carrom turn).
 */
sealed interface CarromTurnResult {
    /** Latest diagnostic HUD state, if the loop produced one this cycle. */
    val hud: CarromTurnHudState?
    /** Newest-first outcome hints (last shot summary, self-play, ...). */
    val hint: String

    /**
     * Not a carrom turn (solver off, no board found, or local vision needs
     * AI help). The host decides: open the game, wait, or run its AI
     * fallback. [aiPreCarromCounts] carries the board counts when vision
     * saw pieces but could not solve, for the AI prompt.
     */
    data class NotCarromTurn(
        val reason: String,
        val diagnostic: String? = null,
        val aiPreCarromCounts: BoardPieceCounts? = null,
        override val hud: CarromTurnHudState? = null,
        override val hint: String = ""
    ) : CarromTurnResult

    /** Board is moving / not my turn yet — feed the next screenshot after ~1s. */
    data class Waiting(
        val reason: String,
        override val hud: CarromTurnHudState? = null,
        override val hint: String = ""
    ) : CarromTurnResult

    /** A slider-probe drag was performed to test another striker spot. */
    data class SliderMoved(
        val drag: CarromDrag,
        val dragOk: Boolean,
        val probeNumber: Int,
        override val hud: CarromTurnHudState? = null,
        override val hint: String = ""
    ) : CarromTurnResult

    /** The shot drag was performed. */
    data class ShotFired(
        val drag: CarromDrag,
        val dragOk: Boolean,
        val wasSafetyShot: Boolean,
        val wasSureShot: Boolean,
        /** True when the shot was registered for outcome evaluation on the
         *  next cycle(s). False for safety touches (never recorded as pot
         *  attempts) and when the drag failed to dispatch. */
        val pendingShotRegistered: Boolean,
        override val hud: CarromTurnHudState? = null,
        override val hint: String = ""
    ) : CarromTurnResult
}

/**
 * The step the local solver decided, replacing TapPilot's PlannedAction
 * (which carried actionType DRAG/WAIT plus accessibility-node fields this
 * export has no use for). Kept private: the host only ever sees
 * [CarromTurnResult] / [CarromDrag].
 */
private sealed interface CarromStepAction {
    /** Perform this drag now. [isShot] distinguishes a shot from a slider probe. */
    data class Drag(
        val drag: CarromDrag,
        val isShot: Boolean,
        /** The final shot solution, for shot drags (null for slider probes). */
        val solution: CarromShotSolution?
    ) : CarromStepAction

    /** Wait one cycle (~1s) — board moving, or safety shot held for the deadline. */
    data class Wait(val reason: String) : CarromStepAction

    /** Local vision cannot solve this screen — host's AI fallback decides. */
    data class NotCarrom(val diagnostic: String) : CarromStepAction
}
