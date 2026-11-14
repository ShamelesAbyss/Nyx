package com.nyx.ai.ui
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.nyx.ai.autonomy.WorkerActivity
import com.nyx.ai.autonomy.WorkerActivityKind
import org.junit.*
import org.junit.runner.RunWith
@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], qualifiers = "w640dp-h360dp-mdpi")
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class BaselineConsoleViewportHostTest {
 @get:Rule val compose = createComposeRule()
 @Test fun baselineDetailMustHaveUsableViewportInShortWindow() {
  val events = (0 until 300).map { WorkerActivity("e$it", it.toLong(), "objective", "one", WorkerActivityKind.TOOL,
   "Event $it", "detail", toolOutput = "long output\n".repeat(300), runId = "run", status = "waiting") }
  compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
   BaselineWorkerConsoleDialog(events, true, objectiveTitle = "Long Kotlin/JVM incremental planning objective ".repeat(15),
    phase = "waiting for inference", objectiveStatus = "waiting", activeTask = "Long active task ".repeat(12),
    currentObjectiveId = "objective", currentRunId = "run", onDismiss = {}, onPause = {}, onCancel = {})
  } }
  compose.onNode(hasScrollAction()).performScrollToNode(hasText("Tap for operation details"))
  compose.onNodeWithText("Tap for operation details").performClick()
  val bounds = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
  println("BASELINE_DETAIL_VIEWPORT_HEIGHT_PX=" + bounds.height)
  Assert.assertTrue("Baseline operation detail viewport collapsed: $bounds", bounds.height >= 48f)
 }
}
