package com.nyx.ai.ui

import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nyx.ai.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only regressions; compiling these is not a runtime/fold verification claim.
 * Use a software IME, unlocked screen and accessibility-enabled instrumentation. No inference,
 * terminal command, permission grant, network-consent change or persisted endpoint edit is made.
 */
@RunWith(AndroidJUnit4::class)
class EditorLayoutInstrumentedTest {
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val longPaste = (1..300).joinToString("\n") { "Line $it: pasted text remains editable near the cursor." }

    @Before
    fun retrieveImeWindows() {
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    @Test
    fun productionChatPasteSurvivesRecreationAndDestinationSwitch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click("Expand Message editor")
            setText("Full screen Message editor", longPaste)
            assertEquals(longPaste, awaitNode { it.contentDescription?.toString() == "Full screen Message editor" && it.text?.toString() == longPaste }.text.toString())
            scenario.recreate()
            awaitNode { it.contentDescription?.toString() == "Full screen Message editor" && it.text?.toString() == longPaste }
            click("Done editing Message")
            click("Models")
            click("Chat")
            click("Expand Message editor")
            awaitNode { it.contentDescription?.toString() == "Full screen Message editor" && it.text?.toString() == longPaste }
            click("Done editing Message")
            val send = awaitNode { it.contentDescription?.toString() == "Send" || it.contentDescription?.toString() == "Stop generation" }
            assertTrue("Chat submission/stop control must have a visible hit area", bounds(send).height() > 0)
        }
    }

    @Test
    fun fullScreenChatCursorAndDoneRemainInsideImeViewportAfterRotation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            click("Expand Message editor")
            setText("Full screen Message editor", longPaste)
            checkEditorImeBounds("Message")
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            awaitNode { it.contentDescription?.toString() == "Full screen Message editor" && it.text?.toString() == longPaste }
            checkEditorImeBounds("Message")
            click("Done editing Message")
        }
    }

    @Test
    fun longInlinePasteIsBoundedAndFullScreenReturnsWithoutSubmitting() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        var draft by rememberSaveable { mutableStateOf("") }
                        var submitted by rememberSaveable { mutableStateOf(false) }
                        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                            .verticalScroll(rememberScrollState()).padding(12.dp)) {
                            ExpandableDraftField(draft, { draft = it }, label = "Regression task", maxCharacters = 8_000)
                            Button(onClick = { submitted = true }, modifier = Modifier.fillMaxWidth()) { Text("Submit regression draft") }
                            Text(if (submitted) "Submitted" else "Not submitted")
                        }
                    }
                }
            }
            click("Expand Regression task editor")
            setText("Full screen Regression task editor", longPaste)
            awaitNode { it.contentDescription?.toString() == "Full screen Regression task editor" && it.text?.length == 8_000 }
            checkEditorImeBounds("Regression task")
            click("Done editing Regression task")
            val submit = awaitNode { it.text?.toString() == "Submit regression draft" }
            assertTrue("Submission remains a separate visible control after a long paste", bounds(submit).height() > 0 && submit.isVisibleToUser)
            awaitNode { it.text?.toString() == "Not submitted" }
        }
    }

    private fun checkEditorImeBounds(label: String) {
        // Software IME presence is an explicit precondition, never silently claimed when absent.
        waitUntil { automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }
        val ime = automation.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        assumeTrue("Requires an enabled software IME for inset bounds checks", ime != null)
        val imeBounds = Rect().also { ime!!.getBoundsInScreen(it) }
        val field = awaitNode { it.contentDescription?.toString() == "Full screen $label editor" }
        val done = awaitNode { it.contentDescription?.toString() == "Done editing $label" }
        val fieldBounds = bounds(field)
        assertTrue("Focused editor must have remaining height", fieldBounds.height() > 0)
        assertTrue("Focused editor must end above the IME", fieldBounds.bottom <= imeBounds.top)
        assertTrue("Done must be reachable above the IME", bounds(done).bottom <= imeBounds.top && done.isVisibleToUser)
        // End selection exercises the internally scrolling field's cursor bring-into-view path.
        val length = field.text?.length ?: 0
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, length)
        }))
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        var lastCharacter: RectF? = null
        assertTrue("Caret-adjacent pasted character must become visible", waitUntil {
            val current = automation.rootInActiveWindow?.let { root -> find(root) { it.contentDescription?.toString() == "Full screen $label editor" } }
            current?.refreshWithExtraData(key, Bundle().apply {
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, (length - 1).coerceAtLeast(0))
                putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
            })
            @Suppress("DEPRECATION")
            val locations = current?.extras?.getParcelableArray(key)
            lastCharacter = locations?.firstOrNull() as? RectF
            lastCharacter?.let { it.height() > 0 && it.top >= fieldBounds.top && it.bottom <= fieldBounds.bottom && it.bottom <= imeBounds.top } == true
        })
    }

    private fun setText(description: String, text: String) {
        val field = awaitNode { it.contentDescription?.toString() == description && it.isEditable }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }))
    }

    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = awaitNode { it.contentDescription?.toString() == label || it.text?.toString() == label }
        while (node != null) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            node = node.parent
        }
        throw AssertionError("No clickable accessibility action for $label")
    }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also { node.getBoundsInScreen(it) }

    private fun awaitNode(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var match: AccessibilityNodeInfo? = null
        assertTrue("Expected accessibility node within five seconds", waitUntil {
            match = automation.rootInActiveWindow?.let { find(it, predicate) }
            match != null
        })
        return requireNotNull(match)
    }

    private fun find(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            find(child, predicate)?.let { return it }
        }
        return null
    }

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            if (condition()) return true
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        return condition()
    }
}
