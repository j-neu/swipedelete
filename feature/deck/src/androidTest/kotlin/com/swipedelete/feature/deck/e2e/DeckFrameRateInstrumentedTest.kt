package com.swipedelete.feature.deck.e2e

import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Window
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swipedelete.core.model.ItemId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M1 exit criterion: "a 500-item deck triages end to end at >= 60 fps with full
 * undo" (SPEC 11.1: swipe frame time <= 16 ms).
 *
 * The assembled deck (see [DeckHarness]) is shown in a plain activity and swiped
 * with **real-time touch events** injected at the pace of a fast human thumb —
 * not through the Compose test clock, which would not render at the display's
 * cadence. Every frame the window draws is timed by the platform's own
 * [FrameMetrics], and the distribution is logged under the tag [TAG].
 *
 * This is a measurement of a debuggable build, which renders slower than a
 * release build; treat the figures as an upper bound on frame time.
 *
 * It takes minutes (far longer on an emulator), so it is skipped unless asked for:
 *
 * ```
 * gradlew :feature:deck:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.deckFps=true
 *   -Pandroid.testInstrumentationRunnerArguments.class=<this class>
 * ```
 *
 * `-Pandroid.testInstrumentationRunnerArguments.deckItems=N` overrides the deck
 * size for a quick run. The test records; it does not gate on a frame-time
 * threshold, because a debuggable build is not what ships.
 */
@RunWith(AndroidJUnit4::class)
class DeckFrameRateInstrumentedTest {

    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val itemCount: Int =
        InstrumentationRegistry.getArguments().getString("deckItems")?.toIntOrNull() ?: 500

    private val requested = InstrumentationRegistry.getArguments().getString("deckFps") == "true"

    // Built lazily so a skipped run does not write 500 images first.
    private val harness by lazy { DeckHarness(context, itemCount = itemCount, imageSizePx = 1080) }
    private val state get() = harness.viewModel.state.value
    private val frameThread = HandlerThread("deck-frame-metrics").apply { start() }

    @After
    fun tearDown() {
        frameThread.quitSafely()
        if (requested) harness.close()
    }

    @Test
    fun a_500_item_deck_is_triaged_by_touch_with_frame_times_recorded_and_fully_undone() {
        assumeTrue("frame-rate run not requested (pass deckFps=true)", requested)
        harness // Write the images and assemble the deck before the activity starts.
        val frames = CopyOnWriteArrayList<Long>()
        val stages = CopyOnWriteArrayList<LongArray>()
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                frames += metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                stages += LongArray(STAGES.size) { metrics.getMetric(STAGES[it].second) }
            }
        }

        ActivityScenario.launch(DeckHostActivity::class.java).use { scenario ->
            waitUntil("the first card") { state.current != null }
            val first = state.current!!.id
            var width = 0
            var height = 0
            var refreshRate = 60f
            scenario.onActivity { activity ->
                width = activity.window.decorView.width
                height = activity.window.decorView.height
                @Suppress("DEPRECATION")
                refreshRate = activity.windowManager.defaultDisplay.refreshRate
                activity.window.addOnFrameMetricsAvailableListener(listener, Handler(frameThread.looper))
            }
            // Let the first card's thumbnail land before the timed run starts.
            SystemClock.sleep(SETTLE_MS)
            // The size is read after layout; a zero size would aim every swipe at
            // the status bar instead of the card.
            scenario.onActivity { activity ->
                width = activity.window.decorView.width
                height = activity.window.decorView.height
            }
            check(width > 0 && height > 0) { "the deck window was never laid out" }
            Log.i(TAG, "window=${width}x$height hasFocus=${hasWindowFocus(scenario)}")
            frames.clear()

            val started = SystemClock.uptimeMillis()
            repeat(itemCount) { index ->
                // One delete in five, the rest kept: a plausible triage mix.
                val toLeft = index % 5 == 0
                swipe(width, height, toLeft)
                waitUntil("decision ${index + 1}") { state.deck.history.size == index + 1 }
                SystemClock.sleep(DWELL_MS)
            }
            val elapsedMs = SystemClock.uptimeMillis() - started
            SystemClock.sleep(SETTLE_MS)
            scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) }

            report(frames.toList(), refreshRate, elapsedMs)
            reportStages(stages.toList())

            assertEquals(itemCount, state.deck.history.size, "every card must have been decided by touch")
            assertEquals(itemCount / 5 + if (itemCount % 5 == 0) 0 else 1, state.staged.size)
            assertTrue(frames.size > itemCount, "the run must have drawn frames to measure, was ${frames.size}")

            // "With full undo": every decision is reverted, back to the first card.
            val undoStarted = SystemClock.uptimeMillis()
            repeat(itemCount) { harness.viewModel.undoLast() }
            waitForProgress("the full undo", { state.deck.history.size }) { state.deck.history.isEmpty() }
            Log.i("DeckFps", "full undo of $itemCount decisions took ${SystemClock.uptimeMillis() - undoStarted} ms")
            assertEquals<ItemId?>(first, state.current?.id)
            assertTrue(state.staged.isEmpty() && state.deck.kept.isEmpty())
            assertTrue(harness.files.all { it.exists() }, "staging and undo never touch a file")
        }
    }

    private fun hasWindowFocus(scenario: ActivityScenario<DeckHostActivity>): Boolean {
        var focused = false
        scenario.onActivity { focused = it.hasWindowFocus() }
        return focused
    }

    /** A thumb-speed horizontal swipe across the card, injected in real time. */
    private fun swipe(width: Int, height: Int, toLeft: Boolean) {
        val y = height * 0.45f
        val fromX = width * 0.5f
        val toX = if (toLeft) width * 0.08f else width * 0.92f
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, down, fromX, y)
        for (step in 1..SWIPE_STEPS) {
            SystemClock.sleep(SWIPE_STEP_MS)
            val x = fromX + (toX - fromX) * step / SWIPE_STEPS
            send(MotionEvent.ACTION_MOVE, down, SystemClock.uptimeMillis(), x, y)
        }
        send(MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis(), toX, y)
    }

    private fun send(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what; error=${state.error}" }
            SystemClock.sleep(2)
        }
    }

    /** Like [waitUntil], but the clock restarts whenever [progress] changes: only a stall fails. */
    private fun waitForProgress(what: String, progress: () -> Int, condition: () -> Boolean) {
        var last = progress()
        var deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (!condition()) {
            val now = progress()
            if (now != last) {
                last = now
                deadline = SystemClock.uptimeMillis() + WAIT_MS
            }
            check(SystemClock.uptimeMillis() < deadline) { "stalled waiting for $what at $now; error=${state.error}" }
            SystemClock.sleep(2)
        }
    }

    private fun report(totalNanos: List<Long>, refreshRate: Float, elapsedMs: Long) {
        val ms = totalNanos.map { it / 1_000_000.0 }.sorted()
        fun percentile(p: Double) = ms[((ms.size - 1) * p).toInt()]
        fun over(limit: Double) = 100.0 * ms.count { it > limit } / ms.size
        val vsync = 1000.0 / refreshRate
        Log.i(
            TAG,
            "items=$itemCount frames=${ms.size} elapsedMs=$elapsedMs refreshHz=$refreshRate " +
                "p50=%.1f p90=%.1f p95=%.1f p99=%.1f max=%.1f ".format(
                    percentile(0.50), percentile(0.90), percentile(0.95), percentile(0.99), ms.last(),
                ) +
                "over16.7ms=%.2f%% overVsync(%.1fms)=%.2f%% over33ms=%.2f%%".format(
                    over(16.67), vsync, over(vsync), over(33.3),
                ),
        )
    }

    /** Where the frame time goes: the median and p90 of each pipeline stage. */
    private fun reportStages(perFrame: List<LongArray>) {
        if (perFrame.isEmpty()) return
        val line = STAGES.indices.joinToString(" ") { stage ->
            val ms = perFrame.map { it[stage] / 1_000_000.0 }.sorted()
            "%s=%.1f/%.1f".format(STAGES[stage].first, ms[(ms.size - 1) / 2], ms[((ms.size - 1) * 0.9).toInt()])
        }
        Log.i(TAG, "stages p50/p90 ms: $line")
    }

    private companion object {
        const val TAG = "DeckFps"
        val STAGES = listOf(
            "delay" to FrameMetrics.UNKNOWN_DELAY_DURATION,
            "input" to FrameMetrics.INPUT_HANDLING_DURATION,
            "anim" to FrameMetrics.ANIMATION_DURATION,
            "layout" to FrameMetrics.LAYOUT_MEASURE_DURATION,
            "draw" to FrameMetrics.DRAW_DURATION,
            "sync" to FrameMetrics.SYNC_DURATION,
            "issue" to FrameMetrics.COMMAND_ISSUE_DURATION,
            "swap" to FrameMetrics.SWAP_BUFFERS_DURATION,
        )
        const val SWIPE_STEPS = 12
        const val SWIPE_STEP_MS = 10L
        const val DWELL_MS = 120L
        const val SETTLE_MS = 800L
        const val WAIT_MS = 15_000L
    }
}
