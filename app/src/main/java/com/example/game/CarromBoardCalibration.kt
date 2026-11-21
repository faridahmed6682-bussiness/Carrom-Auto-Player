package com.example.game

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class CalibratedHsvColor(
    val h: Float,
    val s: Float,
    val v: Float
) {
    fun matches(hsv: FloatArray, role: CoinColor): Boolean {
        val hueDiff = circularHueDiff(hsv[0], h)
        val satDiff = abs(hsv[1] - s)
        val valDiff = abs(hsv[2] - v)
        return when (role) {
            CoinColor.WHITE -> satDiff <= 0.20f && valDiff <= 0.22f && hsv[1] <= 0.42f
            CoinColor.BLACK -> satDiff <= 0.24f && valDiff <= 0.18f && hsv[2] <= 0.42f
            CoinColor.QUEEN -> hueDiff <= 22f && satDiff <= 0.28f && valDiff <= 0.30f && hsv[1] >= 0.32f
            CoinColor.STRIKER -> hueDiff <= 24f && satDiff <= 0.28f && valDiff <= 0.28f
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("h", h.toDouble())
        .put("s", s.toDouble())
        .put("v", v.toDouble())

    companion object {
        fun fromColorInt(colorInt: Int): CalibratedHsvColor {
            val hsv = FloatArray(3)
            Color.colorToHSV(colorInt, hsv)
            return CalibratedHsvColor(hsv[0], hsv[1], hsv[2])
        }

        fun fromJson(obj: JSONObject?): CalibratedHsvColor? {
            obj ?: return null
            return CalibratedHsvColor(
                h = obj.optDouble("h", 0.0).toFloat().coerceIn(0f, 360f),
                s = obj.optDouble("s", 0.0).toFloat().coerceIn(0f, 1f),
                v = obj.optDouble("v", 0.0).toFloat().coerceIn(0f, 1f)
            )
        }

        private fun circularHueDiff(a: Float, b: Float): Float {
            val raw = abs(a - b) % 360f
            return if (raw > 180f) 360f - raw else raw
        }
    }
}

/**
 * User-calibrated board geometry, 4 pocket centers, baseline/slider track positions,
 * pull-power scale, and optional coin/striker color swatches persisted across matches.
 */
data class BoardCalibrationProfile(
    val profileName: String = "Default",
    val enabled: Boolean = false,
    val boardLeftNorm: Float = 0.055f,
    val boardTopNorm: Float = 0.24f,
    val boardRightNorm: Float = 0.945f,
    val boardBottomNorm: Float = 0.72f,
    val pocketsNorm: List<Point2D> = emptyList(),
    val baselineYNorm: Float = 0.845f,
    val sliderTrackYNorm: Float = 0.79f,
    val sliderTrackLeftNorm: Float = 0.18f,
    val sliderTrackRightNorm: Float = 0.82f,
    val coinRadiusRatio: Float = 0.029f,
    val strikerRadiusRatio: Float = 0.036f,
    val maxPullScale: Float = 1.0f,
    val slingshotDeadzonePx: Float = 22f,
    val slingshotCurveGamma: Float = 1.0f,
    val cutThrowBias: Float = 1.0f,
    val whiteColor: CalibratedHsvColor? = null,
    val blackColor: CalibratedHsvColor? = null,
    val queenColor: CalibratedHsvColor? = null,
    val strikerColor: CalibratedHsvColor? = null
) {
    fun resolvedPocketsScreen(widthPx: Float, heightPx: Float): List<Point2D> {
        if (pocketsNorm.size == 4) {
            return pocketsNorm.map { Point2D(it.x * widthPx, it.y * heightPx) }
        }
        val bLeft = boardLeftNorm * widthPx
        val bTop = boardTopNorm * heightPx
        val bRight = boardRightNorm * widthPx
        val bBottom = boardBottomNorm * heightPx
        val inset = ((bRight - bLeft).coerceAtLeast(40f)) * 0.064f
        return listOf(
            Point2D(bLeft + inset, bTop + inset),
            Point2D(bRight - inset, bTop + inset),
            Point2D(bLeft + inset, bBottom - inset),
            Point2D(bRight - inset, bBottom - inset)
        )
    }

    fun defaultCornerPocketsNorm(): List<Point2D> {
        if (pocketsNorm.size == 4) return pocketsNorm
        val bw = (boardRightNorm - boardLeftNorm).coerceAtLeast(0.1f)
        val bh = (boardBottomNorm - boardTopNorm).coerceAtLeast(0.1f)
        val insetX = bw * 0.064f
        val insetY = bh * 0.064f
        return listOf(
            Point2D(boardLeftNorm + insetX, boardTopNorm + insetY),
            Point2D(boardRightNorm - insetX, boardTopNorm + insetY),
            Point2D(boardLeftNorm + insetX, boardBottomNorm - insetY),
            Point2D(boardRightNorm - insetX, boardBottomNorm - insetY)
        )
    }

    fun colorFor(role: CoinColor): CalibratedHsvColor? = when (role) {
        CoinColor.WHITE -> whiteColor
        CoinColor.BLACK -> blackColor
        CoinColor.QUEEN -> queenColor
        CoinColor.STRIKER -> strikerColor
    }

    fun withColorFor(role: CoinColor, color: CalibratedHsvColor?): BoardCalibrationProfile = when (role) {
        CoinColor.WHITE -> copy(whiteColor = color)
        CoinColor.BLACK -> copy(blackColor = color)
        CoinColor.QUEEN -> copy(queenColor = color)
        CoinColor.STRIKER -> copy(strikerColor = color)
    }

    fun customColorCount(): Int = listOfNotNull(whiteColor, blackColor, queenColor, strikerColor).size

    fun toJson(): JSONObject {
        val pocketsArr = JSONArray()
        for (p in pocketsNorm) {
            pocketsArr.put(
                JSONObject()
                    .put("x", p.x.toDouble())
                    .put("y", p.y.toDouble())
            )
        }
        return JSONObject()
            .put("profileName", profileName)
            .put("enabled", enabled)
            .put("boardLeftNorm", boardLeftNorm.toDouble())
            .put("boardTopNorm", boardTopNorm.toDouble())
            .put("boardRightNorm", boardRightNorm.toDouble())
            .put("boardBottomNorm", boardBottomNorm.toDouble())
            .put("pocketsNorm", pocketsArr)
            .put("baselineYNorm", baselineYNorm.toDouble())
            .put("sliderTrackYNorm", sliderTrackYNorm.toDouble())
            .put("sliderTrackLeftNorm", sliderTrackLeftNorm.toDouble())
            .put("sliderTrackRightNorm", sliderTrackRightNorm.toDouble())
            .put("coinRadiusRatio", coinRadiusRatio.toDouble())
            .put("strikerRadiusRatio", strikerRadiusRatio.toDouble())
            .put("maxPullScale", maxPullScale.toDouble())
            .put("slingshotDeadzonePx", slingshotDeadzonePx.toDouble())
            .put("slingshotCurveGamma", slingshotCurveGamma.toDouble())
            .put("cutThrowBias", cutThrowBias.toDouble())
            .apply {
                whiteColor?.let { put("whiteColor", it.toJson()) }
                blackColor?.let { put("blackColor", it.toJson()) }
                queenColor?.let { put("queenColor", it.toJson()) }
                strikerColor?.let { put("strikerColor", it.toJson()) }
            }
    }

    companion object {
        fun fromJson(obj: JSONObject?): BoardCalibrationProfile {
            obj ?: return BoardCalibrationProfile()
            val pocketsArr = obj.optJSONArray("pocketsNorm")
            val pockets = if (pocketsArr != null && pocketsArr.length() == 4) {
                (0 until 4).mapNotNull { idx ->
                    val po = pocketsArr.optJSONObject(idx) ?: return@mapNotNull null
                    Point2D(
                        po.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        po.optDouble("y", 0.5).toFloat().coerceIn(0f, 1f)
                    )
                }.takeIf { it.size == 4 } ?: emptyList()
            } else {
                emptyList()
            }
            val left = obj.optDouble("boardLeftNorm", 0.055).toFloat().coerceIn(0f, 0.45f)
            val top = obj.optDouble("boardTopNorm", 0.24).toFloat().coerceIn(0f, 0.70f)
            val right = obj.optDouble("boardRightNorm", 0.945).toFloat().coerceIn(left + 0.20f, 1f)
            val bottom = obj.optDouble("boardBottomNorm", 0.72).toFloat().coerceIn(top + 0.20f, 1f)
            val sliderLeft = obj.optDouble("sliderTrackLeftNorm", 0.18).toFloat().coerceIn(0f, 0.45f)
            val sliderRight = obj.optDouble("sliderTrackRightNorm", 0.82).toFloat().coerceIn(sliderLeft + 0.20f, 1f)
            return BoardCalibrationProfile(
                profileName = obj.optString("profileName", "Default").takeIf { it.isNotBlank() } ?: "Default",
                enabled = obj.optBoolean("enabled", false),
                boardLeftNorm = left,
                boardTopNorm = top,
                boardRightNorm = right,
                boardBottomNorm = bottom,
                pocketsNorm = pockets,
                baselineYNorm = obj.optDouble("baselineYNorm", 0.845).toFloat().coerceIn(0.65f, 0.96f),
                sliderTrackYNorm = obj.optDouble("sliderTrackYNorm", 0.79).toFloat().coerceIn(0.50f, 0.96f),
                sliderTrackLeftNorm = sliderLeft,
                sliderTrackRightNorm = sliderRight,
                coinRadiusRatio = obj.optDouble("coinRadiusRatio", 0.029).toFloat().coerceIn(0.018f, 0.048f),
                strikerRadiusRatio = obj.optDouble("strikerRadiusRatio", 0.036).toFloat().coerceIn(0.024f, 0.058f),
                maxPullScale = obj.optDouble("maxPullScale", 1.0).toFloat().coerceIn(0.65f, 1.45f),
                slingshotDeadzonePx = obj.optDouble("slingshotDeadzonePx", 22.0).toFloat().coerceIn(0f, 60f),
                slingshotCurveGamma = obj.optDouble("slingshotCurveGamma", 1.0).toFloat().coerceIn(0.75f, 1.35f),
                cutThrowBias = obj.optDouble("cutThrowBias", 1.0).toFloat().coerceIn(0f, 2.0f),
                whiteColor = CalibratedHsvColor.fromJson(obj.optJSONObject("whiteColor")),
                blackColor = CalibratedHsvColor.fromJson(obj.optJSONObject("blackColor")),
                queenColor = CalibratedHsvColor.fromJson(obj.optJSONObject("queenColor")),
                strikerColor = CalibratedHsvColor.fromJson(obj.optJSONObject("strikerColor"))
            )
        }
    }
}

/**
 * Matches a live-detected board (Auto-Snap geometry from the current screen)
 * against the saved named board profiles.
 *
 * A profile matches when its pocket geometry is close to the live board:
 * mean pocket-centre distance <= 3.5% of screen size and board-rect
 * difference <= 6%. Colour swatches (when the live board has them) add a
 * small agreement bonus but never decide a match on their own — geometry
 * is the reliable signal, because two skins can share colours while their
 * pockets sit in different places.
 *
 * Boards the bot has never seen (new skin, new pocket layout) match
 * NOTHING: the caller must then keep auto-detection for that match and
 * offer the Aim Lab to save it as a new named profile. There is no silent
 * fallback to a wrong profile.
 */
data class BoardProfileMatch(
    val profile: BoardCalibrationProfile,
    val score: Float
)

object BoardProfileMatcher {

    /** Max mean pocket-centre distance (normalised screen units) for a match. */
    const val MAX_MEAN_POCKET_DISTANCE = 0.035f

    /** Max board-rect difference (normalised) for a match. */
    const val MAX_BOARD_RECT_DIFF = 0.06f

    fun matchProfile(
        live: BoardCalibrationProfile,
        profiles: List<BoardCalibrationProfile>
    ): BoardProfileMatch? {
        if (live.pocketsNorm.size != 4) return null
        var best: BoardProfileMatch? = null
        for (profile in profiles) {
            if (!profile.enabled || profile.pocketsNorm.size != 4) continue
            val pocketDistance = meanPocketDistance(live.pocketsNorm, profile.pocketsNorm)
            val rectDiff = boardRectDiff(live, profile)
            if (pocketDistance > MAX_MEAN_POCKET_DISTANCE || rectDiff > MAX_BOARD_RECT_DIFF) continue
            val colorBonus = colorAgreementBonus(live, profile)
            val score = (1f - (pocketDistance * 0.7f + rectDiff * 0.3f) + colorBonus)
                .coerceIn(0f, 1f)
            if (best == null || score > best.score) {
                best = BoardProfileMatch(profile, score)
            }
        }
        return best
    }

    fun meanPocketDistance(livePockets: List<Point2D>, storedPockets: List<Point2D>): Float {
        if (livePockets.size != 4 || storedPockets.size != 4) return 1f
        var total = 0f
        for (i in 0 until 4) {
            total += kotlin.math.hypot(
                livePockets[i].x - storedPockets[i].x,
                livePockets[i].y - storedPockets[i].y
            )
        }
        return total / 4f
    }

    fun boardRectDiff(a: BoardCalibrationProfile, b: BoardCalibrationProfile): Float {
        return (abs(a.boardLeftNorm - b.boardLeftNorm) +
            abs(a.boardTopNorm - b.boardTopNorm) +
            abs(a.boardRightNorm - b.boardRightNorm) +
            abs(a.boardBottomNorm - b.boardBottomNorm)) / 4f
    }

    /**
     * Small bonus (0..0.05) when live colour swatches agree with the stored
     * ones. Roles present in only one side are ignored. Returns 0 when the
     * live board has no swatches (Auto-Snap geometry alone).
     */
    fun colorAgreementBonus(live: BoardCalibrationProfile, stored: BoardCalibrationProfile): Float {
        val roles = listOf(
            live.whiteColor to stored.whiteColor,
            live.blackColor to stored.blackColor,
            live.queenColor to stored.queenColor,
            live.strikerColor to stored.strikerColor
        )
        var total = 0f
        var count = 0
        for ((liveColor, storedColor) in roles) {
            if (liveColor == null || storedColor == null) continue
            total += colorDistance(liveColor, storedColor)
            count++
        }
        if (count == 0) return 0f
        return ((1f - total / count) * 0.05f).coerceIn(0f, 0.05f)
    }

    /** Normalised HSV distance between two swatches (0 = identical). */
    fun colorDistance(a: CalibratedHsvColor, b: CalibratedHsvColor): Float {
        val hueDiff = run {
            val raw = abs(a.h - b.h) % 360f
            (if (raw > 180f) 360f - raw else raw) / 180f
        }
        return (hueDiff * 0.5f + abs(a.s - b.s) * 0.25f + abs(a.v - b.v) * 0.25f)
            .coerceIn(0f, 1f)
    }
}
