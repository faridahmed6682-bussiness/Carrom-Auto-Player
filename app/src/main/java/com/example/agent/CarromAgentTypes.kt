package com.example.agent

/**
 * Carrom-only types extracted VERBATIM from Auto-Agent5
 * (app/src/main/java/com/example/agent/AgentModels.kt) at commit ea02d43.
 *
 * The carrom engine (com.example.game.*) imports CarromDifficulty,
 * CarromRuleMode, TaskPlaybook and CarromContextHelper from this package.
 * Only the carrom-related declarations were copied here so the carrom code
 * can compile without the rest of the TapPilot agent app.
 *
 * NOT copied: the rest of AgentModels.kt (agent actions, AI providers,
 * safety policy, messaging types, …) — none of it is carrom.
 */

enum class CarromRuleMode(val label: String) {
    FREESTYLE("Freestyle (All Coins)"),
    WHITE_ONLY("Disc Pool: White"),
    BLACK_ONLY("Disc Pool: Black")
}

/**
 * Carrom auto-play difficulty: caps which shots the solver will take by
 * confidence, and how much human-like aim error is added to the shot.
 * HARD only takes high-confidence shots and adds no aim error; EASY accepts
 * riskier pots and adds up to +/-3 degrees of aim error.
 */
enum class CarromDifficulty(
    val label: String,
    val minConfidence: Float,
    val aimErrorDeg: Float
) {
    EASY("Easy — relaxed", 0.38f, 3.0f),
    MEDIUM("Medium — balanced", 0.50f, 1.5f),
    HARD("Hard — sure shots only", 0.62f, 0.0f)
}

data class TaskPlaybook(
    val topicKey: String,
    val topicTitle: String,
    val rulesSummary: String,
    val winningStrategies: List<String>,
    val foulLessons: List<String>,
    val trainingSessions: Int = 1,
    val lastUpdatedMs: Long = System.currentTimeMillis()
) {
    fun formatForPrompt(): String {
        val strategiesText = if (winningStrategies.isEmpty()) {
            "- None recorded yet."
        } else {
            winningStrategies.joinToString("\n") { "- $it" }
        }
        val foulsText = if (foulLessons.isEmpty()) {
            "- None recorded yet."
        } else {
            foulLessons.joinToString("\n") { "- AVOID: $it" }
        }
        return """
            PLAYBOOK TOPIC: $topicTitle (Trained $trainingSessions session(s))
            CORE RULES & CONTROLS:
            $rulesSummary
            WINNING / POCKET / EXECUTION STRATEGIES:
            $strategiesText
            FOULS & MISTAKES TO AVOID:
            $foulsText
        """.trimIndent()
    }
}

object CarromContextHelper {
    fun isCarromGoalOrApp(
        goal: String,
        packageName: String,
        playbookTopicKey: String?
    ): Boolean {
        return goal.contains("carrom", ignoreCase = true) ||
            goal.contains("ক্যারাম", ignoreCase = true) ||
            goal.contains("ক্যারম", ignoreCase = true) ||
            goal.contains("কেরাম", ignoreCase = true) ||
            goal.contains("কারাম", ignoreCase = true) ||
            goal.contains("caram", ignoreCase = true) ||
            goal.contains("disc pool", ignoreCase = true) ||
            packageName.contains("carrom", ignoreCase = true) ||
            packageName.contains("caram", ignoreCase = true) ||
            (playbookTopicKey?.contains("carrom", ignoreCase = true) == true)
    }

    fun isLikelyGameLobbyMenu(nodes: List<ScreenNode>): Boolean {
        val strongLobbyExact = setOf(
            "play classic", "disc pool", "freestyle",
            "tournament", "matchmaking", "find match",
            "play with friends", "vs computer", "খেলুন", "খেলা শুরু"
        )
        val weakLobbyExact = setOf(
            "play", "classic", "shop", "store",
            "claim", "collect", "login", "sign in", "continue"
        )
        var strongHit = false
        var weakSignalCount = 0
        for (node in nodes) {
            if (!node.isClickable) continue
            val label = node.displayLabel().trim().lowercase()
            if (label.isBlank()) continue
            if (label in strongLobbyExact || label.startsWith("play classic") || label.startsWith("find match")) {
                strongHit = true
                break
            }
            if (label in weakLobbyExact ||
                label.startsWith("play ") ||
                label.contains("disc pool") ||
                label.contains("freestyle")
            ) {
                weakSignalCount++
            }
        }
        return strongHit || weakSignalCount >= 2
    }

    fun buildDefaultCarromPlaybook(ruleMode: CarromRuleMode = CarromRuleMode.FREESTYLE): TaskPlaybook {
        val isFreestyle = ruleMode == CarromRuleMode.FREESTYLE
        val ruleDesc = if (isFreestyle) {
            "Freestyle Carrom: White, Black, and Red (Queen) coins are all legal targets. Only striker pocket scratch is a foul. Cover Red (Queen) when needed. Move bottom slider knob horizontally to align striker, then slingshot-drag striker center opposite pocket direction."
        } else {
            "Disc Pool Carrom (${ruleMode.label}): Pocket only your assigned color coins + Red (Queen). Pocketing only opponent coins or scratching striker into a pocket is a foul."
        }
        return TaskPlaybook(
            topicKey = "carrom_board_game",
            topicTitle = if (isFreestyle) "Carrom Freestyle" else "Carrom Disc Pool",
            rulesSummary = ruleDesc,
            winningStrategies = listOf(
                "Pre-scan board and rank up to 4 side-to-side baseline striker X spots before sliding.",
                "Slide bottom slider knob side-to-side (3-4 probes) using engine + training data until a zero-foul Sure Shot locks.",
                "Accept collision-assisted carom shots when they still pocket a valid coin cleanly with zero striker scratch.",
                "Pull slingshot opposite to pocket vector from striker center; shoot safest zero-foul Sure/Safe shot within 8-9s."
            ),
            foulLessons = listOf(
                "Striker falling into any pocket is a foul — reject straight scratch trajectories via 2D physics rollout.",
                "Reject colliding lines that deflect target away from pocket or cause striker scratch."
            ),
            trainingSessions = 1,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }
}
