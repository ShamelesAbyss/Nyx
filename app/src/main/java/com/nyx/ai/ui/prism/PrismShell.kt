package com.nyx.ai.ui.prism

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nyx.ai.R

private val prismIcons = listOf(Icons.Rounded.ChatBubbleOutline, Icons.Rounded.Memory, Icons.Rounded.SmartToy,
    Icons.Rounded.PlayArrow, Icons.Rounded.AutoAwesome, Icons.Rounded.Hub, Icons.Rounded.AccountTree, Icons.Rounded.Tune, Icons.Rounded.Settings)

/** Presentation only. Resizing and selecting destinations never start inference or grant authority. */
@Composable fun PrismShell(selected: Int, onSelect: (Int) -> Unit, model: String, connected: Boolean,
    missionLabel: String = "", onMission: () -> Unit = {}, content: @Composable (Int) -> Unit) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val saved = rememberSaveableStateHolder()
    val destination = PrismDestination.entries[selected.coerceIn(0, 8)]
    PrismTheme(destination) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val expanded = maxWidth >= 840.dp
            val largeText = LocalDensity.current.fontScale >= 1.3f
            val primary = if (largeText) listOf(0, 3) else listOf(0, 1, 3)
            Scaffold(modifier = Modifier.imePadding(), containerColor = PrismObsidian, contentWindowInsets = WindowInsets.safeDrawing,
                bottomBar = {
                    if (!expanded) NavigationBar(modifier = Modifier.heightIn(min = if (largeText) 104.dp else 80.dp), containerColor = PrismSurface) {
                        primary.forEach { index ->
                            val section = PrismDestination.entries[index]
                            NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
                                modifier = Modifier.testTag("prism_nav_${section.title}"),
                                icon = { Icon(prismIcons[index], contentDescription = section.title) },
                                label = { Text(section.title, fontSize = 12.sp, maxLines = 2) }, alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(selectedIconColor = section.accent, selectedTextColor = section.accent,
                                    indicatorColor = section.accent.copy(alpha = .12f)))
                        }
                        NavigationBarItem(selected = selected !in primary, onClick = { menuOpen = true },
                            modifier = Modifier.testTag("prism_sections"), icon = { Icon(Icons.Rounded.MoreHoriz, "All sections") },
                            label = { Text("Sections", fontSize = 12.sp) }, alwaysShowLabel = true)
                    }
                }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    if (expanded) NavigationRail(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).testTag("prism_rail"), containerColor = PrismSurface) {
                        PrismText("NYX", color = PrismBrand, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
                        PrismDestination.entries.forEachIndexed { index, section ->
                            NavigationRailItem(selected = selected == index, onClick = { onSelect(index) },
                                modifier = Modifier.testTag("prism_nav_${section.title}"),
                                icon = { Icon(prismIcons[index], section.title) }, label = { Text(section.title, fontSize = 12.sp) }, alwaysShowLabel = true,
                                colors = NavigationRailItemDefaults.colors(selectedIconColor = section.accent, selectedTextColor = section.accent,
                                    indicatorColor = section.accent.copy(alpha = .12f)))
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Image(painterResource(R.drawable.nyx_launcher), "NYX Eclipse", Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                PrismText(destination.title, color = destination.accent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                PrismText("${if (connected) "Connected" else "Not connected"} · ${model.ifBlank { "Select a model" }}",
                                    color = PrismMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            PrismTextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("prism_sections_header")) { Text("Sections") }
                        }
                        if (missionLabel.isNotBlank()) PrismTextButton(onClick = onMission,
                            modifier = Modifier.fillMaxWidth().testTag("prism_open_mission")) {
                            Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            PrismText(missionLabel, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            PrismText("View", fontSize = 12.sp)
                        }
                        Surface(Modifier.weight(1f).fillMaxWidth().testTag("prism_screen_${destination.title}"), color = PrismObsidian) {
                            saved.SaveableStateProvider(selected) { content(selected) }
                        }
                    }
                }
            }
        }
        if (menuOpen) AlertDialog(onDismissRequest = { menuOpen = false }, title = { Text("All sections") },
            text = { LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("prism_section_menu"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(PrismDestination.entries) { section ->
                    val index = section.ordinal
                    PrismOutlinedButton(onClick = { onSelect(index); menuOpen = false }, modifier = Modifier.fillMaxWidth().testTag("prism_destination_${section.title}")) {
                        Icon(prismIcons[index], null, tint = section.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(section.title); PrismText(section.subtitle, color = PrismMuted, fontSize = 12.sp) }
                        if (selected == index) Icon(Icons.Rounded.Check, "Selected")
                    }
                }
            } }, confirmButton = { PrismTextButton(onClick = { menuOpen = false }) { Text("Close") } })
    }
}
