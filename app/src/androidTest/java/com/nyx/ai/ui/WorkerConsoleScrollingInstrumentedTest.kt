package com.nyx.ai.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nyx.ai.autonomy.WorkerActivity
import com.nyx.ai.autonomy.WorkerActivityKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production dialog and real Compose scroll gestures. Requires an ARM64 Android test target.
 * Run on phone and expanded/foldable windows; compilation alone is NOT a UI acceptance pass. */
@RunWith(AndroidJUnit4::class)
class WorkerConsoleScrollingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val events = mutableStateOf(history(300))
    private val shown = mutableStateOf(true)
    private var pauses = 0
    private var cancels = 0

    private fun event(index: Int, task: String = "one", detail: String = "Event details $index") = WorkerActivity(
        "e$index", index.toLong(), "objective", task, WorkerActivityKind.TOOL, "Event $index", detail,
        toolName = "project_inspect", toolInput = "input $index", toolOutput = "output $index", runId = "run", status = "waiting")
    private fun history(count: Int) = (0 until count).map { event(it) }
    private fun launch() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (shown.value) WorkerConsoleDialog(events.value, running = true,
                    objectiveTitle = "Long Kotlin/JVM incremental planning objective ".repeat(15),
                    phase = "waiting for inference", objectiveStatus = "waiting", activeTask = "Long active task ".repeat(12),
                    currentObjectiveId = "objective", currentRunId = "run", onDismiss = { shown.value = false },
                    onPause = { pauses++ }, onCancel = { cancels++ })
            }
        }
        compose.waitForIdle()
    }
    private fun list() = compose.onNodeWithTag("console_list")
    private fun reveal(tag: String) { list().performScrollToNode(hasTestTag(tag)); compose.onNodeWithTag(tag).assertIsDisplayed() }
    private fun open(task: String = "one") { reveal("console_operation_task:$task"); compose.onNodeWithTag("console_operation_task:$task").performClick() }
    private fun first() { compose.onNodeWithTag("console_jump_first").performClick() }
    private fun latest() { compose.onNodeWithTag("console_jump_latest").performClick() }
    private fun positionStart(): String = compose.onNodeWithTag("console_position").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].first().text.substringBefore(" of ")

    private fun snapshot(name: String) {
        val directory = System.getProperty("nyx.console.screenshots") ?: return
        val file = java.io.File(directory, "${javaClass.simpleName}_$name.png").apply { parentFile.mkdirs() }
        file.outputStream().use { output ->
            check(compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    @Test fun detailStartsAtBeginningAndBothEndsOfLongHistoryAreReachable() {
        launch(); open(); snapshot("detail")
        val viewport = list().fetchSemanticsNode().boundsInRoot
        assertTrue("Operation details need a usable viewport: $viewport", viewport.height >= 48f)
        reveal("console_operation_heading")
        reveal("console_event_e0"); snapshot("first-event")
        latest(); compose.onNodeWithTag("console_event_e299").assertIsDisplayed(); snapshot("last-event")
        // Real gestures must also move freely back toward history, without snap-back effects.
        list().performTouchInput { swipeDown() }
        compose.onNodeWithTag("console_position").assertTextContains("reading history", substring = true)
        first(); reveal("console_event_e0")
        compose.onNodeWithTag("console_back").performClick()
        reveal("console_operation_task:one")
        compose.onNodeWithContentDescription("Close console").performClick()
        compose.onNodeWithText("LIVE WORKER CONSOLE").assertDoesNotExist()
    }

    @Test fun longPayloadExpandsIntoReachableRowsAndCollapsesWithoutDeletingEvents() {
        val long = "BEGIN_OUTPUT\n" + "readable terminal output\n".repeat(2_000) + "END_OUTPUT"
        events.value = listOf(event(0).copy(toolOutput = long))
        launch(); open(); reveal("console_event_e0")
        compose.onNodeWithText("BEGIN_OUTPUT", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("console_event_e0").performClick()
        reveal("console_part_e0_3_0")
        compose.onNodeWithText("BEGIN_OUTPUT", substring = true).assertExists()
        latest(); compose.onNodeWithText("END_OUTPUT", substring = true).assertIsDisplayed(); snapshot("long-output-end")
        first(); reveal("console_event_e0"); compose.onNodeWithTag("console_event_e0").performClick()
        compose.onNodeWithText("BEGIN_OUTPUT", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("console_event_e0").assertExists()
    }

    @Test fun liveArrivalPreservesHistoryAndFollowIsExplicitAndStoppedByGesture() {
        launch(); open(); reveal("console_event_e50")
        val before = positionStart()
        compose.runOnIdle { events.value = events.value + event(300) }
        compose.waitForIdle(); assertEquals(before, positionStart())
        compose.onNodeWithTag("console_follow_live").performClick()
        compose.onNodeWithTag("console_event_e300").assertIsDisplayed()
        compose.runOnIdle { events.value = events.value + event(301) }
        compose.onNodeWithTag("console_event_e301").assertIsDisplayed()
        list().performTouchInput { swipeDown() }
        compose.onNodeWithTag("console_follow_live").assertTextContains("Follow live", substring = true)
        val afterGesture = positionStart()
        compose.runOnIdle { events.value = events.value + event(302) }
        compose.waitForIdle(); assertEquals(afterGesture, positionStart())
    }

    @Test fun switchingOperationsResetsDetailAndBackRestoresGroupedListPosition() {
        events.value = history(300) + (300 until 400).map { event(it, task = "two") }
        launch(); open("one"); latest()
        compose.onNodeWithTag("console_event_e299").assertIsDisplayed()
        compose.onNodeWithTag("console_back").performClick()
        open("two"); reveal("console_operation_heading"); reveal("console_event_e300")
        compose.onNodeWithTag("console_back").performClick()
        open("one"); reveal("console_operation_heading")
        reveal("console_event_e0")
    }

    @Test fun rawAndGroupedNavigationKeepAllEventsAndLatestMeansNewestOperation() {
        events.value = history(300) + event(300, task = "newest")
        launch(); latest(); compose.onNodeWithTag("console_operation_task:newest").assertIsDisplayed()
        first(); list().performScrollToNode(hasText("Raw events")); compose.onNodeWithText("Raw events").performClick()
        reveal("console_event_e0"); latest(); compose.onNodeWithTag("console_event_e300").assertIsDisplayed()
        first(); list().performScrollToNode(hasText("Operations")); compose.onNodeWithText("Operations", useUnmergedTree = true).performClick()
        latest(); compose.onNodeWithTag("console_operation_task:newest").assertIsDisplayed()
    }

    @Test fun longRunHistoryMenuCanSelectOldestAndNewestRunsWithoutLeakingDetailPosition() {
        events.value = (0 until 40).flatMap { run ->
            listOf(event(run * 2).copy(runId = "r$run"), event(run * 2 + 1).copy(runId = "r$run"))
        }
        launch()
        fun select(label: String) {
            first(); list().performScrollToNode(hasText("History · 40"))
            compose.onNodeWithText("History · 40").performClick()
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        select("objective · r0"); open(); reveal("console_event_e0"); latest()
        compose.onNodeWithTag("console_event_e1").assertIsDisplayed()
        compose.onNodeWithTag("console_back").performClick()
        select("objective · r39"); open(); reveal("console_operation_heading"); reveal("console_event_e78")
        latest(); compose.onNodeWithTag("console_event_e79").assertIsDisplayed()
    }

    @Test fun expandingAnEventStopsFollowingAndSystemBackReturnsBeforeClosing() {
        events.value = history(2)
        launch(); open()
        compose.onNodeWithTag("console_follow_live").performClick()
        reveal("console_event_e1"); compose.onNodeWithTag("console_event_e1").performClick()
        compose.onNodeWithTag("console_follow_live").assertTextContains("Follow live", substring = true)
        androidx.test.espresso.Espresso.pressBack()
        reveal("console_operation_task:one")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("LIVE WORKER CONSOLE").assertDoesNotExist()
    }

    @Test fun copySharePauseCancelAndCloseRemainAvailableThroughScrollableContext() {
        launch(); open(); latest(); first()
        list().performScrollToNode(hasText("Pause")); compose.onNodeWithText("Pause").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, pauses); assertEquals(1, cancels)
        compose.onNodeWithContentDescription("Copy diagnostic log").performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertTrue(clipboard.primaryClip!!.getItemAt(0).text.contains("NYX LIVE ACTIVITY"))
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            compose.onNodeWithContentDescription("Share diagnostic log").performClick()
            compose.waitForIdle(); assertEquals(1, monitor.hits)
        } finally { instrumentation.removeMonitor(monitor) }
        compose.onNodeWithContentDescription("Close console").performClick()
        compose.onNodeWithText("LIVE WORKER CONSOLE").assertDoesNotExist()
    }
}
