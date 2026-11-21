package com.example

import com.example.agent.AgentSettings
import com.example.agent.ScreenBounds
import com.example.agent.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarromLogicUnitTest {

@Test
    fun `knowledge store matches saved carrom playbook by goal and merges lessons without duplicates`() {
        val carrom = com.example.agent.TaskPlaybook(
            topicKey = "carrom_board_game",
            topicTitle = "Carrom Board Game",
            rulesSummary = "Drag striker opposite target direction.",
            winningStrategies = listOf("Cut shots at 30 degrees work well for corner pockets."),
            foulLessons = listOf("Pocketing striker causes a foul.")
        )
        val matched = com.example.agent.AgentKnowledgeStore.findBestMatchingPlaybook(
            listOf(carrom),
            "Open Carrom Board and practice pocketing coins"
        )
        assertEquals("carrom_board_game", matched?.topicKey)

        val merged = com.example.agent.AgentKnowledgeStore.mergeLessons(
            carrom.foulLessons,
            listOf("pocketing striker causes a foul.", "Avoid hitting opponent black coin first.")
        )
        assertEquals(2, merged.size)
    }

@Test
    fun `carrom solver computes slingshot ghost ball shot avoiding obstacles and foul pockets`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f), // top-left
                com.example.game.Point2D(960f, 560f), // top-right
                com.example.game.Point2D(120f, 1400f), // bottom-left
                com.example.game.Point2D(960f, 1400f)  // bottom-right
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        // White target coin near top-left; black obstacle blocks top-right pocket path
        val whiteTarget = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(310f, 750f),
            radiusPx = 28f
        )
        val blockingBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(635f, 655f), // directly between (310,750) and top-right (960,560)
            radiusPx = 28f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteTarget, blockingBlack),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400
        )

        org.junit.Assert.assertNotNull(solution)
        val sol = solution!!
        // Should aim for top-left pocket (120, 560), NOT blocked top-right
        assertEquals(120f, sol.targetPocket.x, 1f)
        assertEquals(560f, sol.targetPocket.y, 1f)
        // Slingshot pulls back opposite the upward shot (dragEnd.y > strikerPos.y)
        assertTrue(sol.dragEnd.y > sol.strikerPos.y)
        assertFalse(sol.isBankShot)

        // Straight-on follow-through into pocket must be flagged as a striker scratch foul
        val dirToTopLeft = (com.example.game.Point2D(120f, 560f) - whiteTarget.center).normalized()
        val scratchGhost = whiteTarget.center - dirToTopLeft * 63f
        assertTrue(
            com.example.game.CarromGeometrySolver.willStrikerScratchIntoPocket(
                strikerPos = com.example.game.Point2D(500f, 1320f),
                ghostBall = scratchGhost,
                dirSg = dirToTopLeft,
                dirCp = dirToTopLeft,
                cutAngleDeg = 0f,
                board = board
            )
        )
    }

@Test
    fun `carrom solver falls back to one rail bank shot when direct pocket paths are blocked`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val whiteCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(540f, 820f),
            radiusPx = 28f
        )
        // Place 4 blocker black coins between whiteCoin and each of the 4 pockets
        val blockers = board.pockets.map { pocket ->
            val mid = com.example.game.Point2D(
                (whiteCoin.center.x + pocket.x) * 0.5f,
                (whiteCoin.center.y + pocket.y) * 0.5f
            )
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, mid, 28f)
        }

        val bankShot = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteCoin) + blockers,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400
        )

        org.junit.Assert.assertNotNull(bankShot)
        assertTrue(bankShot!!.isBankShot)
    }

@Test
    fun `carrom solver shoots from existing striker position when slider repositioning is unavailable`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val currentStriker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(620f, 1320f),
            radiusPx = 35f
        )
        val whiteCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(360f, 800f),
            radiusPx = 28f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(currentStriker, whiteCoin),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = currentStriker,
            canRepositionStriker = false
        )

        org.junit.Assert.assertNotNull(solution)
        assertEquals(620f, solution!!.strikerPos.x, 1f)
        assertEquals(1320f, solution.strikerPos.y, 1f)
    }

@Test
    fun `carrom solver applies stronger pull power for angled cut shots than straight shots`() {
        val striker = com.example.game.Point2D(540f, 1320f)
        val shotDir = com.example.game.Point2D(0f, -1f)

        val straightEnd = com.example.game.CarromGeometrySolver.computeSlingshotDragEnd(
            strikerPos = striker,
            shotDir = shotDir,
            totalDistance = 750f,
            boardWidth = 960f,
            screenWidth = 1080,
            screenHeight = 2400,
            cutAngleDeg = 0f
        )
        val cutEnd = com.example.game.CarromGeometrySolver.computeSlingshotDragEnd(
            strikerPos = striker,
            shotDir = shotDir,
            totalDistance = 750f,
            boardWidth = 960f,
            screenWidth = 1080,
            screenHeight = 2400,
            cutAngleDeg = 48f
        )

        val straightPull = (straightEnd - striker).length()
        val cutPull = (cutEnd - striker).length()
        assertTrue("Expected angled cut pull ($cutPull) > straight pull ($straightPull)", cutPull > straightPull + 25f)
    }

@Test
    fun `carrom solver finds two coin combo shot when direct path to target coin is blocked`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        // finalWhite sits cleanly lined up with top-left pocket (120, 560),
        // helperWhite sits behind finalWhite along that pocket axis,
        // and a black blocker guards direct striker access to finalWhite from baseline.
        val finalWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(260f, 700f),
            radiusPx = 28f
        )
        val helperWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(380f, 820f),
            radiusPx = 28f
        )
        val guardBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(350f, 960f),
            radiusPx = 28f
        )
        val currentStriker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(finalWhite, helperWhite, guardBlack, currentStriker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = currentStriker,
            canRepositionStriker = false
        )

        org.junit.Assert.assertNotNull(solution)
        assertTrue(solution!!.dragEnd.y > solution.strikerPos.y)
    }

@Test
    fun `carrom solver falls back to power cluster break when all pockets are blocked`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val centerWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(540f, 980f),
            radiusPx = 28f
        )
        // Surround centerWhite with 6 black blockers blocking both direct and bank paths
        val blockers = listOf(
            com.example.game.Point2D(460f, 900f),
            com.example.game.Point2D(620f, 900f),
            com.example.game.Point2D(460f, 1060f),
            com.example.game.Point2D(620f, 1060f),
            com.example.game.Point2D(540f, 890f),
            com.example.game.Point2D(360f, 980f),
            com.example.game.Point2D(720f, 980f)
        ).map { com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, it, 28f) }

        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        val solution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(centerWhite, striker) + blockers,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false
        )

        org.junit.Assert.assertNotNull(solution)
        assertTrue(solution!!.reason.contains("Power Break", ignoreCase = true) || solution.confidenceScore >= 0.50f)
    }

@Test
    fun `carrom shot memory learns winning pockets softens power after fouls and guides future shots`() {
        var mem = com.example.game.CarromMemoryData()

        // 1. Successful pot in Top-Left pocket (index 0) at 20 deg cut with In-Place shot
        val winShot = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = 0,
            cutAngleDeg = 20f,
            powerStyle = com.example.game.CarromPowerStyle.MEDIUM,
            movedStrikerWithSlider = false,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 5, queenCount = 1)
        )
        val winEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = mem,
            pendingShot = winShot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 5, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.POCKETED_MY_COIN, winEval.outcomeType)
        org.junit.Assert.assertNotNull(winEval.learnedTacticLesson)
        mem = winEval.updatedMemory
        assertTrue(mem.confidenceBiasFor(0, 20f) > 0f)

        // 2. Foul on Top-Right pocket (index 1) straight shot (myCount increased due to penalty coin returned!)
        val foulShot = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = 1,
            cutAngleDeg = 6f,
            powerStyle = com.example.game.CarromPowerStyle.HARD,
            movedStrikerWithSlider = true,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 5, queenCount = 1)
        )
        val foulEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = mem,
            pendingShot = foulShot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 5, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY, foulEval.outcomeType)
        org.junit.Assert.assertNotNull(foulEval.learnedFoulLesson)
        mem = foulEval.updatedMemory
        assertTrue(mem.learnedPowerScaleFor(1, 6f) < 1.0f)
        assertTrue(mem.confidenceBiasFor(1, 6f) < 0f)
        assertTrue(mem.needsExtraScratchGuard())

        // 3. Symmetric board: leftWhite aims Top-Left (0), rightWhite aims Top-Right (1).
        // With learned memory favoring Top-Left & penalizing Top-Right, solver picks Top-Left!
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val leftWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(320f, 760f),
            radiusPx = 28f
        )
        val rightWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(760f, 760f),
            radiusPx = 28f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        val guidedSolution = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(leftWhite, rightWhite, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false,
            shotMemory = mem
        )

        org.junit.Assert.assertNotNull(guidedSolution)
        assertEquals(0, guidedSolution!!.pocketIndex)
    }

@Test
    fun `carrom geometry solver falls back to available coins in freestyle and guarantees break shot`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1312f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1312f),
            radiusPx = 35f
        )

        // 1. Freestyle/late-game: no WHITE or QUEEN coins remain, only BLACK coins
        val onlyBlack = listOf(
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(520f, 960f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(580f, 980f), 28f),
            striker
        )
        val freestyleShot = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = onlyBlack,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false
        )
        org.junit.Assert.assertNotNull(freestyleShot)

        // 2. Tight center break rack with fixed center striker must still guarantee a break shot
        val breakRack = listOf(
            com.example.game.CarromPiece(com.example.game.CoinColor.QUEEN, com.example.game.Point2D(540f, 980f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(540f, 930f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(584f, 955f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.WHITE, com.example.game.Point2D(496f, 955f), 28f),
            com.example.game.CarromPiece(com.example.game.CoinColor.BLACK, com.example.game.Point2D(540f, 1030f), 28f),
            striker
        )
        val breakShot = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = breakRack,
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false
        )
        org.junit.Assert.assertNotNull(breakShot)
        assertTrue(breakShot!!.confidenceScore >= 0.50f)
    }

@Test
    fun `carrom coach helper distinguishes watch training goal from autonomous self train`() {
        assertTrue(com.example.game.CarromCoachHelper.isCarromWatchTrainGoal(com.example.game.CarromCoachHelper.CARROM_WATCH_TRAIN_GOAL))
        assertTrue(com.example.game.CarromCoachHelper.isCarromWatchTrainGoal("আমি ক্যারাম খেলবো তুমি দেখে ট্রেনিং নাও"))
        assertTrue(com.example.game.CarromCoachHelper.isCarromWatchTrainGoal("উভয় পক্ষের ক্যারাম খেলা দেখে ট্রেনিং নাও"))
        assertFalse(com.example.game.CarromCoachHelper.isCarromWatchTrainGoal("ট্রেনিং দিয়েছি এবার ক্যারাম খেলো"))
        assertFalse(com.example.game.CarromCoachHelper.isCarromWatchTrainGoal("ক্যারাম ট্রেনিং শেষ এখন খেলো"))
        assertFalse(com.example.game.CarromCoachHelper.isCarromWatchTrainGoal("ক্যারাম খেলবো"))
        assertFalse(
            com.example.game.CarromCoachHelper.isCarromWatchTrainGoal(
                "Open Carrom game, research board rules, position striker with DRAG, and pot my coins cleanly."
            )
        )
    }

@Test
    fun `carrom watch trainer learns opponent top turn symmetrically without polluting player foul blacklist`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),  // 0: top-left
                com.example.game.Point2D(960f, 560f),  // 1: top-right
                com.example.game.Point2D(120f, 1400f), // 2: bottom-left
                com.example.game.Point2D(960f, 1400f)  // 3: bottom-right
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val trainer = com.example.game.CarromWatchTrainer()
        val myWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(740f, 920f),
            radiusPx = 28f
        )
        val oppBlackNearBottomLeft = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(230f, 1270f),
            radiusPx = 28f
        )
        val oppStrikerStart = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 648f),
            radiusPx = 35f
        )
        val oppStrikerSlid = oppStrikerStart.copy(center = com.example.game.Point2D(410f, 648f))

        val u1 = trainer.processObservation(
            obs = com.example.game.CarromWatchObservation(
                board = board,
                pieces = listOf(myWhite, oppBlackNearBottomLeft),
                striker = null,
                sliderKnob = null,
                lumaDiff = 0f,
                isInMotion = false,
                isLikelyGameBoard = true,
                topStriker = oppStrikerStart
            ),
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 1_000L
        )
        assertEquals(com.example.game.WatchTrainerPhase.ARMED_PRE_SHOT, u1.phase)
        assertTrue(u1.hudStatusText.contains("Opponent Turn"))

        trainer.processObservation(
            obs = com.example.game.CarromWatchObservation(
                board = board,
                pieces = listOf(myWhite, oppBlackNearBottomLeft),
                striker = null,
                sliderKnob = null,
                lumaDiff = 2f,
                isInMotion = false,
                isLikelyGameBoard = true,
                topStriker = oppStrikerSlid
            ),
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 1_400L
        )

        val uMotion = trainer.processObservation(
            obs = com.example.game.CarromWatchObservation(
                board = board,
                pieces = listOf(myWhite, oppBlackNearBottomLeft),
                striker = null,
                sliderKnob = null,
                lumaDiff = 24f,
                isInMotion = true,
                isLikelyGameBoard = true,
                topStriker = oppStrikerSlid.copy(center = com.example.game.Point2D(340f, 760f))
            ),
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 1_650L
        )
        assertEquals(com.example.game.WatchTrainerPhase.TRACKING_MOTION, uMotion.phase)

        // Opponent pots BLACK coin into Bottom-Left (2), turn passes to player (bottom striker ready)
        val playerInitialStriker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )
        val settled = com.example.game.CarromWatchObservation(
            board = board,
            pieces = listOf(myWhite, playerInitialStriker),
            striker = playerInitialStriker,
            sliderKnob = com.example.game.Point2D(540f, 1520f),
            lumaDiff = 1f,
            isInMotion = false,
            isLikelyGameBoard = true,
            topStriker = null
        )
        trainer.processObservation(settled, com.example.game.CoinColor.WHITE, isFreestyle = false, nowMs = 2_300L)
        val uDone = trainer.processObservation(settled, com.example.game.CoinColor.WHITE, isFreestyle = false, nowMs = 2_450L)

        val shot = uDone.completedShot
        org.junit.Assert.assertNotNull(shot)
        assertEquals(com.example.game.WatchTurnSide.OPPONENT_TOP, shot!!.shooterSide)
        assertEquals(com.example.game.CoinColor.BLACK, shot.pendingShot.myCoinColor)
        assertEquals(com.example.game.CoinColor.BLACK, shot.pendingShot.targetCoinColor)
        assertEquals(0, shot.pendingShot.pocketIndex) // Bottom-Left (2) mirrored to Top-Left (0)
        assertTrue(shot.pendingShot.movedStrikerWithSlider)
        assertEquals(1, trainer.shotsObserved)
        assertEquals(1, trainer.potsObserved)

        // Verify opponent fouls never pollute player foulSignatures or placementStats
        val oppFoulEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = shot.pendingShot,
            postCounts = com.example.game.BoardPieceCounts(myCount = 2, opponentCount = 1, queenCount = 0),
            lessonPrefix = "[Opponent Coach] ",
            recordFoulSignatures = false,
            recordPlacementStats = false
        )
        assertEquals(com.example.game.CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY, oppFoulEval.outcomeType)
        assertEquals(0, oppFoulEval.updatedMemory.blockedFoulCount())
        assertEquals(0, oppFoulEval.updatedMemory.placementStats.movedAttempts)
        assertEquals(1, oppFoulEval.updatedMemory.statsByBucket.values.sumOf { it.fouls })
    }

@Test
    fun `carrom watch trainer learns human pocket cut angle slider move and power into shot memory`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f), // 0: top-left
                com.example.game.Point2D(960f, 560f), // 1: top-right
                com.example.game.Point2D(120f, 1400f), // 2: bottom-left
                com.example.game.Point2D(960f, 1400f)  // 3: bottom-right
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val trainer = com.example.game.CarromWatchTrainer()
        val targetWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(260f, 700f),
            radiusPx = 28f
        )
        val otherBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(720f, 880f),
            radiusPx = 28f
        )
        val initialStriker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )
        val slidStriker = initialStriker.copy(center = com.example.game.Point2D(410f, 1320f))

        // Step 1: Turn starts centered, then human slides striker left to x=410
        val u1 = trainer.processObservation(
            obs = com.example.game.CarromWatchObservation(
                board = board,
                pieces = listOf(targetWhite, otherBlack, initialStriker),
                striker = initialStriker,
                sliderKnob = com.example.game.Point2D(540f, 1520f),
                lumaDiff = 0f,
                isInMotion = false,
                isLikelyGameBoard = true
            ),
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 1_000L
        )
        assertEquals(com.example.game.WatchTrainerPhase.ARMED_PRE_SHOT, u1.phase)

        val u2 = trainer.processObservation(
            obs = com.example.game.CarromWatchObservation(
                board = board,
                pieces = listOf(targetWhite, otherBlack, slidStriker),
                striker = slidStriker,
                sliderKnob = com.example.game.Point2D(410f, 1520f),
                lumaDiff = 2.0f,
                isInMotion = false,
                isLikelyGameBoard = true
            ),
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 1_400L
        )
        assertEquals(com.example.game.WatchTrainerPhase.ARMED_PRE_SHOT, u2.phase)

        // Step 2: Human fires shot toward Top-Left targetWhite
        val uMotion = trainer.processObservation(
            obs = com.example.game.CarromWatchObservation(
                board = board,
                pieces = listOf(targetWhite, otherBlack),
                striker = com.example.game.CarromPiece(
                    color = com.example.game.CoinColor.STRIKER,
                    center = com.example.game.Point2D(335f, 1010f),
                    radiusPx = 35f
                ),
                sliderKnob = null,
                lumaDiff = 22f,
                isInMotion = true,
                isLikelyGameBoard = true
            ),
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 1_650L
        )
        assertEquals(com.example.game.WatchTrainerPhase.TRACKING_MOTION, uMotion.phase)

        // Step 3: Coins settle (targetWhite is potted! only otherBlack remains + striker returns)
        val stillSettled1 = com.example.game.CarromWatchObservation(
            board = board,
            pieces = listOf(otherBlack, initialStriker),
            striker = initialStriker,
            sliderKnob = com.example.game.Point2D(540f, 1520f),
            lumaDiff = 1.5f,
            isInMotion = false,
            isLikelyGameBoard = true
        )
        // First still frame waits for confirmation
        val uSettle1 = trainer.processObservation(
            obs = stillSettled1,
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 2_300L
        )
        assertEquals(null, uSettle1.completedShot)

        // Second still frame finalizes inference & feeds CarromMemoryData
        val uSettle2 = trainer.processObservation(
            obs = stillSettled1,
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 2_450L
        )
        val shot = uSettle2.completedShot
        org.junit.Assert.assertNotNull(shot)
        assertEquals(0, shot!!.pendingShot.pocketIndex) // Top-Left Pocket
        assertEquals(com.example.game.CoinColor.WHITE, shot.pendingShot.targetCoinColor)
        assertTrue(shot.pendingShot.movedStrikerWithSlider)
        assertEquals(1, trainer.shotsObserved)
        assertEquals(1, trainer.potsObserved)

        val memEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = shot.pendingShot,
            postCounts = shot.postCounts,
            lessonPrefix = "[Human Coach] "
        )
        assertEquals(com.example.game.CarromShotOutcomeType.POCKETED_MY_COIN, memEval.outcomeType)
        assertTrue(memEval.learnedTacticLesson!!.startsWith("[Human Coach]"))
        assertTrue(memEval.updatedMemory.placementStats.movedSuccesses == 1)
        assertTrue(memEval.updatedMemory.confidenceBiasFor(0, shot.pendingShot.cutAngleDeg) > 0f)

        // Step 4: False motion spike without moved coins must NOT record a bogus shot
        trainer.processObservation(
            obs = stillSettled1.copy(lumaDiff = 19f, isInMotion = true),
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 3_000L
        )
        trainer.processObservation(
            obs = stillSettled1,
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 3_200L
        )
        val afterFalseSpike = trainer.processObservation(
            obs = stillSettled1,
            myCoinColor = com.example.game.CoinColor.WHITE,
            isFreestyle = false,
            nowMs = 3_350L
        )
        assertEquals(null, afterFalseSpike.completedShot)
        assertEquals(1, trainer.shotsObserved)
    }

@Test
    fun `carrom foul signature blacklists spatial shot in solver and physics simulator and prunes after clean pots`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f), // P0 top-left
                com.example.game.Point2D(960f, 560f), // P1 top-right
                com.example.game.Point2D(120f, 1400f), // P2 bottom-left
                com.example.game.Point2D(960f, 1400f)  // P3 bottom-right
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        val whiteCoin = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(260f, 700f),
            radiusPx = 28f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )

        // First verify the baseline solver picks Top-Left Pocket (index 0) for whiteCoin
        val baselineSol = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteCoin, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false
        )
        org.junit.Assert.assertNotNull(baselineSol)
        assertEquals(0, baselineSol!!.pocketIndex)

        val (rowZone, colZone) = com.example.game.CarromMemoryData.coinZoneFor(whiteCoin.center, board)
        val normStrikerX = ((striker.center.x - board.left) / board.width).coerceIn(0f, 1f)

        // Record a foul on that exact spatial shot pattern across all power styles at MID sector (or exact power)
        val foulRecord = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = baselineSol.pocketIndex,
            cutAngleDeg = baselineSol.cutAngleDeg,
            strikerNormalizedX = normStrikerX,
            coinRowZone = rowZone,
            coinColZone = colZone,
            powerStyle = baselineSol.powerStyle,
            movedStrikerWithSlider = false,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 4, queenCount = 0)
        )
        val foulEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = foulRecord,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 4, queenCount = 0)
        )
        var mem = foulEval.updatedMemory
        assertEquals(1, mem.blockedFoulCount())
        assertTrue(
            mem.isFoulBlacklisted(
                board = board,
                coinCenter = whiteCoin.center,
                strikerPos = striker.center,
                pocketIndex = baselineSol.pocketIndex,
                cutAngleDeg = baselineSol.cutAngleDeg,
                powerStyle = baselineSol.powerStyle
            )
        )
        assertTrue(mem.formatForPrompt().contains("HARD-BLOCKED FOUL SIGNATURES"))

        // Verify physics simulator directly rejects candidate matching that blacklisted foul signature
        val blacklistedCandidate = com.example.game.RolloutCandidate(
            strikerPos = striker.center,
            baseDir = (baselineSol.ghostBall - striker.center).normalized(),
            ghostBall = baselineSol.ghostBall,
            targetCoin = whiteCoin,
            targetPocket = board.pockets[0],
            pocketIndex = 0,
            cutAngleDeg = baselineSol.cutAngleDeg,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            basePullMultiplier = when (baselineSol.powerStyle) {
                com.example.game.CarromPowerStyle.SOFT -> 0.80f
                com.example.game.CarromPowerStyle.MEDIUM -> 1.0f
                com.example.game.CarromPowerStyle.HARD -> 1.25f
            },
            staticScore = 0.90f,
            totalDistance = 750f
        )
        val simAfterFoul = com.example.game.CarromPhysicsSimulator.verifyAndRefineCandidates(
            board = board,
            pieces = listOf(whiteCoin, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            candidates = listOf(blacklistedCandidate),
            shotMemory = mem,
            matchCalibration = mem.matchCalibration
        )
        assertEquals(null, simAfterFoul)

        // Verify solveBestShot NEVER selects the blocked (P0, MID, rowZone, colZone, cutAngle, powerStyle) direct shot!
        val afterFoulSol = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(whiteCoin, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false,
            shotMemory = mem
        )
        org.junit.Assert.assertNotNull(afterFoulSol)
        assertFalse(
            "Blacklisted foul shot (pocket=${afterFoulSol!!.pocketIndex}, power=${afterFoulSol.powerStyle}, break=${afterFoulSol.isBreakShot}) should not be chosen again",
            !afterFoulSol.isBreakShot &&
                afterFoulSol.pocketIndex == baselineSol.pocketIndex &&
                afterFoulSol.powerStyle == baselineSol.powerStyle &&
                com.example.game.CarromAngleBucket.fromAngle(afterFoulSol.cutAngleDeg) ==
                com.example.game.CarromAngleBucket.fromAngle(baselineSol.cutAngleDeg)
        )

        // Record 3 clean pots on that same signature -> cleanSuccessCount (3) >= foulCount (1) + 2 -> prunes block automatically
        repeat(3) {
            mem = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
                current = mem,
                pendingShot = foulRecord,
                postCounts = com.example.game.BoardPieceCounts(myCount = 3, opponentCount = 4, queenCount = 0)
            ).updatedMemory
        }
        assertEquals(0, mem.blockedFoulCount())
        assertFalse(
            mem.isFoulBlacklisted(
                board = board,
                coinCenter = whiteCoin.center,
                strikerPos = striker.center,
                pocketIndex = baselineSol.pocketIndex,
                cutAngleDeg = baselineSol.cutAngleDeg,
                powerStyle = baselineSol.powerStyle
            )
        )
    }

@Test
    fun `carrom improvements cover queen urgency soft watch shot best probe return and bounce out friction`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )

        // 1. Queen Cover urgency prioritizes own WHITE coin instead of straying when queenPendingCover = true
        val coverWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(760f, 760f),
            radiusPx = 28f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )
        val coverSol = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(coverWhite, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false,
            queenPendingCover = true
        )
        org.junit.Assert.assertNotNull(coverSol)
        assertEquals(com.example.game.CoinColor.WHITE, coverSol!!.targetCoin.color)

        // 2. Soft hanger shot with low lumaDiff (2.5f) still gets learned by Watch & Learn when coin disappears
        val trainer = com.example.game.CarromWatchTrainer()
        val hangerWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(175f, 615f),
            radiusPx = 28f
        )
        val otherBlack = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(700f, 920f),
            radiusPx = 28f
        )
        val preObs = com.example.game.CarromWatchObservation(
            board = board,
            pieces = listOf(hangerWhite, otherBlack, striker),
            striker = striker,
            sliderKnob = com.example.game.Point2D(540f, 1520f),
            lumaDiff = 0f,
            isInMotion = false,
            isLikelyGameBoard = true
        )
        trainer.processObservation(preObs, com.example.game.CoinColor.WHITE, isFreestyle = false, nowMs = 1_000L)
        val softSettled = com.example.game.CarromWatchObservation(
            board = board,
            pieces = listOf(otherBlack, striker),
            striker = striker,
            sliderKnob = com.example.game.Point2D(540f, 1520f),
            lumaDiff = 2.5f,
            isInMotion = false,
            isLikelyGameBoard = true
        )
        val softUpdate1 = trainer.processObservation(softSettled, com.example.game.CoinColor.WHITE, isFreestyle = false, nowMs = 1_500L)
        assertEquals(com.example.game.WatchTrainerPhase.TRACKING_MOTION, softUpdate1.phase)
        val softUpdate2 = trainer.processObservation(softSettled, com.example.game.CoinColor.WHITE, isFreestyle = false, nowMs = 1_750L)
        org.junit.Assert.assertNotNull("Expected soft shot with low lumaDiff to be learned", softUpdate2.completedShot)

        // 3. CarromTurnMemory returns best evaluated non-sure spot once when current spot is weak
        val turnMem = com.example.game.CarromTurnMemory()
        turnMem.ensureTurnStarted(1_000L)
        val strongNonSure = coverSol.copy(
            strikerPos = com.example.game.Point2D(260f, 1320f),
            isSureShot = false,
            isBreakShot = false,
            rejectedDueToCollisionOrFoul = false,
            confidenceScore = 0.84f
        )
        turnMem.recordObservation(
            nowMs = 1_500L,
            pieces = listOf(coverWhite),
            striker = striker.copy(center = com.example.game.Point2D(260f, 1320f)),
            sliderKnob = com.example.game.Point2D(260f, 1520f),
            evaluatedSolution = strongNonSure
        )
        assertEquals(260f, turnMem.bestEvaluatedSpotToReturnTo(620f, 0.52f) ?: -1f, 0.1f)
        turnMem.markReturnedToBestSpot()
        assertEquals(null, turnMem.bestEvaluatedSpotToReturnTo(620f, 0.52f))

        // 4. Straight medium/hard bounce-out miss lowers frictionScale and shotPowerGain
        val straightMiss = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.WHITE,
            pocketIndex = 0,
            cutAngleDeg = 8f,
            powerStyle = com.example.game.CarromPowerStyle.MEDIUM,
            movedStrikerWithSlider = false,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 4, queenCount = 1)
        )
        val missEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = straightMiss,
            postCounts = com.example.game.BoardPieceCounts(myCount = 4, opponentCount = 4, queenCount = 1)
        )
        assertTrue(missEval.updatedMemory.matchCalibration.frictionScale < 1.0f)
        assertTrue(missEval.updatedMemory.matchCalibration.shotPowerGain < 1.0f)
    }

    // --- The 5 tests below were left out of the first export because they
    // need agent-app classes. They are now included verbatim from
    // Auto-Agent5's AgentLogicUnitTest.kt (commit ea02d43) — the classes
    // they need (AgentSettings, ScreenNode/ScreenBounds,
    // GeminiAiPlanner.resolveVisionStrategy) now exist as small stubs in
    // this repo (agent/AgentSettings.kt, agent/ScreenNode.kt,
    // ai/AiVisionStrategy.kt), so all 5 run standalone. ---

    @Test
    fun `forceVision keeps screenshot and high accuracy for carrom even with 8 HUD buttons`() {
        val settings = AgentSettings(smartTextFastPath = true)

        // Normal UI screen on step 3 with 8 buttons uses FAST_UI without screenshot
        val (uiProfile, uiAttach) = com.example.ai.GeminiAiPlanner.resolveVisionStrategy(
            settings = settings,
            interactiveNodes = 8,
            lastOutcomeHint = "",
            stepNumber = 3,
            forceVision = false
        )
        assertEquals(com.example.ai.AiTaskProfile.FAST_UI, uiProfile)
        assertFalse(uiAttach)

        // Carrom match screen with 8 HUD buttons (chat, emoji, mic, avatar, gift, settings) keeps HIGH_ACCURACY + screenshot
        val (carromProfile, carromAttach) = com.example.ai.GeminiAiPlanner.resolveVisionStrategy(
            settings = settings,
            interactiveNodes = 8,
            lastOutcomeHint = "",
            stepNumber = 3,
            forceVision = true
        )
        assertEquals(com.example.ai.AiTaskProfile.HIGH_ACCURACY, carromProfile)
        assertTrue(carromAttach)
    }

    @Test
    fun `carrom context matches bengali spellings and distinguishes match HUD from lobby menu`() {
        assertTrue(
            com.example.agent.CarromContextHelper.isCarromGoalOrApp(
                goal = "ক্যারম খেলো",
                packageName = "com.unknown.game",
                playbookTopicKey = null
            )
        )
        assertTrue(
            com.example.agent.CarromContextHelper.isCarromGoalOrApp(
                goal = "কেরাম বোর্ড",
                packageName = "com.example",
                playbookTopicKey = null
            )
        )

        fun hudNode(id: Int, label: String) = ScreenNode(
            id = id,
            className = "android.widget.ImageButton",
            text = label,
            contentDesc = label,
            viewId = "hud_$id",
            bounds = ScreenBounds(10, 10, 90, 90),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        val matchHudNodes = listOf(
            hudNode(1, "Chat"),
            hudNode(2, "Emoji"),
            hudNode(3, "Mic"),
            hudNode(4, "Gift"),
            hudNode(5, "Settings"),
            hudNode(6, "Pause")
        )
        assertFalse(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(matchHudNodes))

        val lobbyNodes = matchHudNodes + hudNode(7, "Play Classic")
        assertTrue(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(lobbyNodes))
    }

    @Test
    fun `freestyle carrom pots white black and red freely and only treats striker scratch as foul`() {
        val board = com.example.game.CarromBoardLayout(
            left = 60f,
            top = 500f,
            right = 1020f,
            bottom = 1460f,
            pockets = listOf(
                com.example.game.Point2D(120f, 560f),
                com.example.game.Point2D(960f, 560f),
                com.example.game.Point2D(120f, 1400f),
                com.example.game.Point2D(960f, 1400f)
            ),
            baselineY = 1320f,
            coinRadiusPx = 28f,
            strikerRadiusPx = 35f,
            pocketRadiusPx = 50f
        )
        val striker = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.STRIKER,
            center = com.example.game.Point2D(540f, 1320f),
            radiusPx = 35f
        )
        // Only BLACK coin positioned cleanly in front of Top-Right pocket
        val blackTarget = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.BLACK,
            center = com.example.game.Point2D(790f, 680f),
            radiusPx = 28f
        )
        // White coin tucked away on bottom side (hard cut), so Black coin is the clear Freestyle Sure Shot
        val distantWhite = com.example.game.CarromPiece(
            color = com.example.game.CoinColor.WHITE,
            center = com.example.game.Point2D(460f, 940f),
            radiusPx = 28f
        )

        val sol = com.example.game.CarromGeometrySolver.solveBestShot(
            board = board,
            pieces = listOf(blackTarget, distantWhite, striker),
            myCoinColor = com.example.game.CoinColor.WHITE,
            screenWidth = 1080,
            screenHeight = 2400,
            existingStriker = striker,
            canRepositionStriker = false,
            shotMemory = null,
            usePhysicsRollout = true,
            isFreestyle = true
        )
        org.junit.Assert.assertNotNull(sol)
        assertEquals(com.example.game.CoinColor.BLACK, sol!!.targetCoin.color)
        assertTrue(sol.isSureShot)
        assertFalse(sol.rejectedDueToCollisionOrFoul)

        // Memory evaluation in Freestyle: potting a black coin counts as clean pot, NOT opponent foul
        val preCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 5, queenCount = 1)
        val shotRecord = com.example.game.PendingCarromShotRecord(
            myCoinColor = com.example.game.CoinColor.WHITE,
            targetCoinColor = com.example.game.CoinColor.BLACK,
            pocketIndex = 1,
            cutAngleDeg = 18f,
            strikerNormalizedX = 0.5f,
            powerStyle = com.example.game.CarromPowerStyle.MEDIUM,
            movedStrikerWithSlider = false,
            isBankShot = false,
            isComboShot = false,
            isBreakShot = false,
            preCounts = preCounts,
            isFreestyle = true
        )
        val blackPotEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = shotRecord,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 4, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.POCKETED_MY_COIN, blackPotEval.outcomeType)

        // Only striker scratch (total coin count increasing due to penalty coin return) marks FOUL in Freestyle
        val scratchEval = com.example.game.CarromShotMemoryStore.evaluateShotOutcome(
            current = com.example.game.CarromMemoryData(),
            pendingShot = shotRecord,
            postCounts = com.example.game.BoardPieceCounts(myCount = 5, opponentCount = 6, queenCount = 1)
        )
        assertEquals(com.example.game.CarromShotOutcomeType.FOUL_SCRATCH_OR_PENALTY, scratchEval.outcomeType)

        // Default AgentSettings also defaults to Freestyle mode
        assertEquals(com.example.agent.CarromRuleMode.FREESTYLE, com.example.agent.AgentSettings().carromRuleMode)
    }

    @Test
    fun `built in carrom playbook builds instantly and avoids cycle zero research stall`() {
        val freestylePb = com.example.agent.CarromContextHelper.buildDefaultCarromPlaybook(
            com.example.agent.CarromRuleMode.FREESTYLE
        )
        assertEquals("carrom_board_game", freestylePb.topicKey)
        assertTrue(freestylePb.topicTitle.contains("Freestyle", ignoreCase = true))
        assertTrue(freestylePb.rulesSummary.contains("White, Black, and Red", ignoreCase = true))
        assertTrue(freestylePb.foulLessons.any { it.contains("Striker falling into any pocket", ignoreCase = true) })
        assertTrue(freestylePb.winningStrategies.size >= 4)

        val presetGoal = "Open Carrom game, research board rules and foul conditions first, position the striker with DRAG, aim opposite the pocket to pot my coins cleanly, evaluate each shot outcome, and save winning angles & foul mistakes in my playbook."
        assertTrue(
            com.example.agent.CarromContextHelper.isCarromGoalOrApp(
                goal = presetGoal,
                packageName = "",
                playbookTopicKey = null
            )
        )

        val lobbyNodes = listOf(
            ScreenNode(
                id = 1,
                className = "android.widget.Button",
                text = "Freestyle",
                contentDesc = "",
                viewId = "btn_freestyle",
                bounds = ScreenBounds(200, 800, 600, 900),
                isClickable = true,
                isEditable = false,
                isScrollable = false,
                isChecked = false,
                isFocused = false
            )
        )
        assertTrue(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(lobbyNodes))
        assertFalse(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(emptyList()))
    }

    @Test
    fun `carrom post-training auto-play goal and single HUD badges do not block autonomous shooting`() {
        assertFalse(
            com.example.game.CarromCoachHelper.isCarromWatchTrainGoal(
                com.example.game.CarromCoachHelper.CARROM_AUTO_PLAY_GOAL
            )
        )
        assertFalse(
            com.example.game.CarromCoachHelper.isCarromWatchTrainGoal(
                "২-৩ ম্যাচ ট্রেনিং নিলাম এবার আসল ম্যাচ খেলো"
            )
        )
        assertFalse(
            com.example.game.CarromCoachHelper.isCarromWatchTrainGoal(
                "watch & learn শেষ এখন ক্যারাম শট নেও"
            )
        )

        fun clickableBadge(id: Int, label: String) = ScreenNode(
            id = id,
            className = "android.widget.Button",
            text = label,
            contentDesc = "",
            viewId = "hud_$id",
            bounds = ScreenBounds(10, 10, 120, 80),
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isChecked = false,
            isFocused = false
        )

        // Single generic HUD button during active match must NOT mark board as lobby
        assertFalse(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(listOf(clickableBadge(1, "Shop"))))
        assertFalse(com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(listOf(clickableBadge(2, "Continue"))))

        // Multi-button lobby or explicit Play Classic menu still returns true
        assertTrue(
            com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(
                listOf(clickableBadge(1, "Shop"), clickableBadge(2, "Play"))
            )
        )
        assertTrue(
            com.example.agent.CarromContextHelper.isLikelyGameLobbyMenu(
                listOf(clickableBadge(3, "Play Classic"))
            )
        )
    }

}