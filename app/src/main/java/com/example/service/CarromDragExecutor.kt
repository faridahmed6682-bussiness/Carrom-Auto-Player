package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "CarromDragExecutor"

/**
 * Slingshot drag for Carrom shots, extracted VERBATIM (logic) from TapPilot's
 * app/src/main/java/com/example/service/GestureExecutor.kt (305 lines) at
 * commit ea02d43 — the DRAG branch of execute() (~lines 75-90), dispatchSwipe
 * (~line 241) and dispatchAndAwait (~line 265), including the OEM note
 * (~line 251) about Carrom shot drags being dropped by some OEM services.
 *
 * Everything else from GestureExecutor (tap, long-press, text input, scroll,
 * swipe, back/home, node-tree fallback, clipboard paste, …) is NOT carrom
 * and is deliberately left out.
 *
 * HOST WIRING — this class takes an AccessibilityService in its constructor.
 * The host app must bind it to its own service: TapPilot bound the original
 * GestureExecutor to TapPilotAccessibilityService. This repo's
 * AndroidManifest declares NO accessibility service on purpose — the host
 * supplies its own service and passes it here. The live loop calls
 * [performSlingshotDrag] through its dragPerformer callback; the signature
 * below matches that callback exactly.
 *
 * The drag is one unbroken StrokeDescription in one IPC dispatch, with the
 * 120–2500 ms duration clamp from the original DRAG branch.
 */
class CarromDragExecutor(
    private val service: AccessibilityService
) {

    /**
     * Slingshot-drag from the striker centre to [endX]/[endY] (screen pixels,
     * opposite the pocket direction) over [durationMs]. Returns true only
     * when the system confirms the gesture completed.
     */
    suspend fun performSlingshotDrag(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long
    ): Boolean {
        val duration = durationMs.coerceIn(120L, 2500L)
        return dispatchSwipe(startX, startY, endX, endY, duration)
    }

    private suspend fun dispatchSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 380L
    ): Boolean {
        // Always use a single unbroken StrokeDescription in one IPC dispatch:
        // two-step continueStroke introduces an IPC gap and is cancelled by some OEM
        // AccessibilityServices (One UI, HyperOS, ColorOS), dropping Carrom shot drags.
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            GestureDescription.StrokeDescription(path, 0L, durationMs, false)
        } else {
            GestureDescription.StrokeDescription(path, 0L, durationMs)
        }
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchAndAwait(gesture)
    }

    private suspend fun dispatchAndAwait(
        gesture: GestureDescription
    ): Boolean {
        return withTimeoutOrNull(2_500L) {
            suspendCancellableCoroutine { cont ->
                val dispatched = service.dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Gesture cancelled by system")
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null
                )
                if (!dispatched && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false
    }
}
