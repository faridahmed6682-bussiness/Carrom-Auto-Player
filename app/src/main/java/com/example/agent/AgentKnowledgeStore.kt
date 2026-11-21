package com.example.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val MAX_LESSONS_PER_CATEGORY = 12

/**
 * SLIMMED extraction — NOT the full AgentKnowledgeStore from Auto-Agent5.
 *
 * The carrom engine (CarromShotMemoryStore, training-bundle export/import)
 * only uses these two JSON helpers on TaskPlaybook, so only they were copied
 * here verbatim from app/src/main/java/com/example/agent/AgentKnowledgeStore.kt
 * at commit ea02d43. The full knowledge store (keyword search, memory,
 * Context usage) is part of the TapPilot agent app and is NOT in this repo.
 */
class AgentKnowledgeStore {
    companion object {
        fun mergeLessons(existing: List<String>, additions: List<String>): List<String> {
            val seen = LinkedHashSet<String>()
            val result = mutableListOf<String>()
            (existing + additions).forEach { raw ->
                val clean = raw.trim()
                val canonical = clean.lowercase(Locale.ROOT)
                if (clean.isNotBlank() && seen.add(canonical)) {
                    result.add(clean)
                }
            }
            return result.takeLast(MAX_LESSONS_PER_CATEGORY)
        }

        fun findBestMatchingPlaybook(playbooks: List<TaskPlaybook>, goal: String): TaskPlaybook? {
            if (playbooks.isEmpty() || goal.isBlank()) return null
            val goalNorm = goal.lowercase(Locale.ROOT)
            val goalTokens = tokenize(goalNorm)
            if (goalTokens.isEmpty()) return null

            var best: TaskPlaybook? = null
            var bestScore = 0
            for (pb in playbooks) {
                val keyTokens = tokenize(pb.topicKey.replace('_', ' ')) + tokenize(pb.topicTitle)
                val overlap = goalTokens.count { token ->
                    keyTokens.any { it == token || (token.length >= 4 && it.contains(token)) || (it.length >= 4 && token.contains(it)) }
                }
                val directPhrase = goalNorm.contains(pb.topicTitle.lowercase(Locale.ROOT))
                val score = overlap + if (directPhrase) 3 else 0
                if (score > bestScore && (score >= 2 || directPhrase)) {
                    bestScore = score
                    best = pb
                }
            }
            return best
        }

        private fun tokenize(text: String): Set<String> {
            val stopWords = setOf(
                "the", "and", "for", "with", "from", "into", "this", "that", "play", "open",
                "game", "app", "task", "please", "agent", "how", "will", "you", "can", "about"
            )
            return text.lowercase(Locale.ROOT)
                .split(Regex("[^a-z0-9\\u0980-\\u09FF]+"))
                .filter { it.length >= 3 && it !in stopWords }
                .toSet()
        }

        fun serializePlaybooksJson(playbooks: List<TaskPlaybook>): String {
            val array = JSONArray()
            for (pb in playbooks) {
                val obj = JSONObject()
                    .put("topicKey", pb.topicKey)
                    .put("topicTitle", pb.topicTitle)
                    .put("rulesSummary", pb.rulesSummary)
                    .put("winningStrategies", JSONArray(pb.winningStrategies))
                    .put("foulLessons", JSONArray(pb.foulLessons))
                    .put("trainingSessions", pb.trainingSessions)
                    .put("lastUpdatedMs", pb.lastUpdatedMs)
                array.put(obj)
            }
            return array.toString(2)
        }

        fun parsePlaybooksJson(raw: String): List<TaskPlaybook> {
            if (raw.isBlank()) return emptyList()
            val array = JSONArray(raw)
            val list = mutableListOf<TaskPlaybook>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("topicKey").takeIf { it.isNotBlank() } ?: continue
                val title = obj.optString("topicTitle").ifBlank { key }
                val rules = obj.optString("rulesSummary")
                val strategies = jsonArrayToList(obj.optJSONArray("winningStrategies"))
                val fouls = jsonArrayToList(obj.optJSONArray("foulLessons"))
                val sessions = obj.optInt("trainingSessions", 1).coerceAtLeast(1)
                val updated = obj.optLong("lastUpdatedMs", System.currentTimeMillis())
                list.add(
                    TaskPlaybook(
                        topicKey = key,
                        topicTitle = title,
                        rulesSummary = rules,
                        winningStrategies = strategies,
                        foulLessons = fouls,
                        trainingSessions = sessions,
                        lastUpdatedMs = updated
                    )
                )
            }
            return list
        }

        private fun jsonArrayToList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val items = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank()) items.add(value)
            }
            return items
        }
    }
}
