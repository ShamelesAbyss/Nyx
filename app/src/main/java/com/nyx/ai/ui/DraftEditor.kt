package com.nyx.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A bounded inline editor: the field scrolls internally, its parent's actions stay reachable,
 * and Expand edits the same draft/selection in a separate edge-to-edge dialog window.
 * This is not a submit action; Done or Back returns to the draft without discarding changes.
 * Do not use for credentials: saveable state is Android instance state, not the credential vault.
 */
@Composable
internal fun ExpandableDraftField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    minLines: Int = 3,
    maxLines: Int = 6,
    maxCharacters: Int? = null,
    supportingText: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(value)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Imports and externally cleared drafts must update the editor; normal typing must not reset
    // selection/composition. TextFieldValue.copy clamps selection when the external text shrinks.
    LaunchedEffect(value) { if (field.text != value) field = field.copy(text = value, composition = null) }
    val update: (TextFieldValue) -> Unit = { next ->
        val accepted = if (maxCharacters == null) next else next.copy(text = next.text.take(maxCharacters))
        field = accepted
        onValueChange(accepted.text)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        OutlinedTextField(
            value = field, onValueChange = update,
            modifier = Modifier.fillMaxWidth(), label = { Text(label) },
            minLines = minLines, maxLines = maxLines, supportingText = supportingText,
            textStyle = textStyle, shape = RoundedCornerShape(14.dp),
        )
        TextButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = "Expand $label editor" }) {
            Text("Expand editor")
        }
    }
    if (expanded) FullScreenDraftEditor(
        field = field, onValueChange = update, label = label, textStyle = textStyle,
        onDone = { expanded = false },
    )
}

@Composable
internal fun FullScreenDraftEditor(
    field: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    onDone: () -> Unit,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    val focus = remember { FocusRequester() }
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Dialog owns a separate window: safeDrawing includes IME and is consumed exactly here.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDone, modifier = Modifier.semantics { contentDescription = "Done editing $label" }) { Text("Done") }
                }
                OutlinedTextField(
                    value = field, onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focus)
                        .semantics { contentDescription = "Full screen $label editor" },
                    textStyle = textStyle,
                )
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}
