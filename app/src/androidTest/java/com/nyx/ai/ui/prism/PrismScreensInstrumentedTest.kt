package com.nyx.ai.ui.prism

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nyx.ai.workbench.*
import com.nyx.ai.data.ObjectiveEntity
import com.nyx.ai.ollama.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nyx.ai.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production screens; no execution services constructed. Device runs remain a separate gate. */
@RunWith(AndroidJUnit4::class)
class PrismScreensInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val selected = mutableIntStateOf(0)
    private fun viewModel(): NyxViewModel {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val vm = (field.get(null) as sun.misc.Unsafe).allocateInstance(NyxViewModel::class.java) as NyxViewModel
        NyxViewModel::class.java.getDeclaredField("_state").apply { isAccessible = true }.set(vm, MutableStateFlow(NyxState()))
        return vm
    }
    private fun launch(state: NyxState = NyxState()) {
        val vm = viewModel()
        compose.setContent {
            PrismShell(selected.intValue, { selected.intValue = it }, "", false) { destination ->
                NyxDestinationContent(destination, state, vm, {}, {}, { selected.intValue = 8 })
            }
        }
    }
    private fun select(title: String) {
        compose.onNodeWithTag("prism_sections_header").performClick()
        compose.onNodeWithTag("prism_section_menu").performScrollToNode(hasTestTag("prism_destination_$title"))
        compose.onNodeWithTag("prism_destination_$title").performClick()
        compose.onNodeWithTag("prism_screen_$title").assertIsDisplayed()
        val bounds = compose.onNodeWithTag("prism_screen_$title").fetchSemanticsNode().boundsInRoot
        assertTrue("Content retains a usable viewport: $bounds", bounds.height > 48f && bounds.width > 100f)
    }
    @Test fun allNineProductionDestinationsAreReachable() {
        launch()
        PrismDestination.entries.forEach { select(it.title) }
    }
    @Test fun chatDraftSurvivesDestinationRoundTrip() {
        launch()
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Retained PRISM draft")
        select("Models"); select("Chat")
        compose.onNodeWithText("Retained PRISM draft").assertExists()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
    @Test fun settingsGroupsRemainReachable() {
        launch(); select("Settings")
        listOf("Inference", "Autonomy", "Advanced", "Connections").forEach {
            compose.onNodeWithTag("prism_settings_$it").performScrollTo().performClick()
        }
        compose.onNodeWithTag("prism_settings_Connections").assertExists()
    }
    @Test fun searchInputsRetainTheirQueryAcrossNavigation() {
        launch(); select("Arsenal")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("unlikely-arsenal-query")
        select("Gateways")
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("unlikely-gateway-query")
        select("Arsenal"); compose.onNodeWithText("unlikely-arsenal-query").assertExists()
        select("Gateways"); compose.onNodeWithText("unlikely-gateway-query").assertExists()
    }
    @Test fun resultsActionsRequireExplicitClicksAndLongOutputIsScrollable() {
        var technical = 0; var workbench = 0
        val result = MissionResult("historical", "Historical objective", "completed", "Stored result", "Recorded checks", emptyList(),
            listOf(MissionOutput("Output", (1..250).joinToString("\n") { "Result line $it" }, true)), false, false, "Stored result")
        compose.setContent { PrismTheme { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            MissionResultsCard(result, { technical++ }, { workbench++ })
        } } }
        compose.onNodeWithTag("mission_results").assertExists()
        assertEquals(0, technical); assertEquals(0, workbench)
        compose.onNodeWithTag("mission_output_0").performScrollTo().performClick()
        compose.onNodeWithTag("prism_full_text").performScrollToNode(hasText("Result line 250", substring = true))
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithTag("mission_technical").performScrollTo().performClick()
        assertEquals(1, technical); assertEquals(0, workbench)
    }
    @Test fun globalMissionLinkOnlySelectsOnExplicitClick() {
        var opened = 0
        compose.setContent { PrismShell(0, {}, "offline", false, "Completed · Saved objective", { opened++ }) { } }
        assertEquals(0, opened)
        compose.onNodeWithTag("prism_open_mission").performClick()
        assertEquals(1, opened)
    }
    @Test fun resizingKeepsChatDraftAndDoesNotSubmit() {
        val fraction = mutableFloatStateOf(1f)
        val vm = viewModel()
        compose.setContent { Box(Modifier.fillMaxWidth(fraction.floatValue).fillMaxHeight()) {
            PrismShell(0, {}, "", false) { NyxDestinationContent(0, NyxState(), vm, {}, {}, {}) }
        } }
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Draft during window resize")
        compose.runOnIdle { fraction.floatValue = .8f }
        compose.onNodeWithText("Draft during window resize").assertExists()
        compose.runOnIdle { fraction.floatValue = 1f }
        compose.onNodeWithText("Draft during window resize").assertExists()
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
    @Test fun failedArtifactDisablesSaveAndShareAndPreviewIsExplicit() {
        var previews = 0; var exports = 0
        val file = ArtifactRecord("f", "report.txt", ArtifactFormat.TEXT, 42, "abc", 1, ArtifactStatus.FAILED, relativePath = "artifacts/report.txt")
        compose.setContent { PrismTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            MissionFileCard(file, { previews++ }, { exports++ }, { exports++ })
        } } }
        compose.onNodeWithText("Save as…").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Share").performScrollTo().assertIsNotEnabled()
        assertEquals(0, previews); assertEquals(0, exports)
        compose.onNodeWithText("Preview").performScrollTo().performClick()
        assertEquals(1, previews); assertEquals(0, exports)
    }

    @Test fun historicalSelectionRemainsVisibleWhileAnotherMissionRuns() {
        selected.intValue = 3
        val history = ObjectiveEntity("old", "Saved historical mission", "goal", status = "completed", createdAt = 1, updatedAt = 2)
        val active = ObjectiveEntity("active", "Other running mission", "goal", status = "running", createdAt = 3, updatedAt = 4)
        launch(NyxState(objectives = listOf(active, history), selectedObjectiveId = "old", objectiveRunning = true))
        compose.onNodeWithTag("mission_result_status").assertTextEquals("Status: completed")
        compose.onNodeWithTag("mission_result_summary").assertTextContains("incomplete", substring = true)
        compose.onNodeWithText("Workbench", useUnmergedTree = true).performClick()
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasTestTag("mission_result_status"))
        compose.onNodeWithTag("mission_result_status").assertTextEquals("Status: completed")
    }
    @Test fun unresolvedServerOwnershipKeepsStartUnavailable() {
        selected.intValue = 1
        launch(NyxState(ollamaServer = OllamaServerState(status = OllamaServerStatus.ERROR, recoveryRequired = true, recoveryBlocked = true)))
        compose.onAllNodes(hasScrollToIndexAction()).onFirst().performScrollToNode(hasText("Reconcile"))
        compose.onNodeWithText("Reconcile").assertIsDisplayed()
        compose.onNodeWithText("Start").assertDoesNotExist()
        compose.onNodeWithText("Ownership recovery is unresolved", substring = true).assertExists()
    }

}
