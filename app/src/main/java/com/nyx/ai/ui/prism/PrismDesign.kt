package com.nyx.ai.ui.prism

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.Layout
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation

val PrismObsidian = Color(0xFF09090D)
val PrismSurface = Color(0xFF14141F)
val PrismMuted = Color(0xFFBCB9CB)
val PrismBrand = Color(0xFFA78BFA)
val LocalPrismAccent = staticCompositionLocalOf { PrismBrand }

enum class PrismDestination(val title: String, val subtitle: String, val accent: Color) {
    CHAT("Chat", "Conversations and responses", Color(0xFF67DDEB)),
    MODELS("Models", "Providers, local models and recovery", Color(0xFF67DCC4)),
    AGENTS("Agents", "Roles, instructions and capabilities", Color(0xFFF3C46E)),
    AUTONOMY("Autonomy", "Missions, results and authorized work", PrismBrand),
    ARSENAL("Arsenal", "Skills and offline procedures", Color(0xFFDE9AF0)),
    GATEWAYS("Gateways", "Connections, health and tools", Color(0xFF91B9FF)),
    WORKFLOWS("Workflows", "Compose and run agent sequences", Color(0xFFFFAB98)),
    PERFORMANCE("Performance", "Resources and measured tuning", Color(0xFF72D6A3)),
    SETTINGS("Settings", "Connections, privacy and policy", Color(0xFFC3BCD8));
}

@Composable fun PrismTheme(destination: PrismDestination? = null, content: @Composable () -> Unit) {
    val accent = destination?.accent ?: PrismBrand
    CompositionLocalProvider(LocalPrismAccent provides accent) {
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, onPrimary = PrismObsidian,
            secondary = accent, background = PrismObsidian, surface = PrismSurface,
            onSurface = Color(0xFFF2F0F8), onBackground = Color(0xFFF2F0F8),
            onSurfaceVariant = PrismMuted, outline = Color(0xFF474452), error = Color(0xFFFFA1AD)),
            typography = Typography(bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
                bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold)),
            shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)), content = content)
    }
}

/** Existing labels, callbacks and disabled states stay intact; tiny legacy copy gets a readable floor. */
@Composable fun PrismText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontStyle: FontStyle? = null, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null, textAlign: TextAlign? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, softWrap: Boolean = true, maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1, onTextLayout: ((TextLayoutResult) -> Unit)? = null, style: TextStyle = LocalTextStyle.current) {
    val size = if (fontSize.isSp && fontSize.value < 12f) 12.sp else fontSize
    val height = if (lineHeight.isSp && size.isSp) maxOf(lineHeight.value, size.value * 1.4f).sp else lineHeight
    Text(text, modifier, if (color == PrismBrand || color == Color(0xFFC4B5FD)) LocalPrismAccent.current else color, size, fontStyle, fontWeight, fontFamily, letterSpacing, textDecoration,
        textAlign, height, overflow, softWrap, maxLines, minLines, onTextLayout ?: {}, style)
}

@Composable fun PrismButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, Modifier.heightIn(min = 48.dp).then(modifier), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}
@Composable fun PrismOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp), colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = ButtonDefaults.outlinedButtonBorder,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick, Modifier.heightIn(min = 48.dp).then(modifier), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}
@Composable fun PrismTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(10.dp), colors: ButtonColors = ButtonDefaults.textButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp).then(modifier), enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}
@Composable fun PrismIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(), interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit) {
    IconButton(onClick, Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).then(modifier), enabled, colors, interactionSource, content)
}
@Composable fun PrismSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null, enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(checkedTrackColor = LocalPrismAccent.current), interactionSource: MutableInteractionSource? = null) {
    Switch(checked, onCheckedChange, modifier.heightIn(min = 48.dp), thumbContent, enabled, colors, interactionSource)
}
@Composable fun PrismCheckbox(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: CheckboxColors = CheckboxDefaults.colors(checkedColor = LocalPrismAccent.current), interactionSource: MutableInteractionSource? = null) {
    Checkbox(checked, onCheckedChange, modifier.heightIn(min = 48.dp), enabled, colors, interactionSource)
}
@Composable fun PrismSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null, colors: SliderColors = SliderDefaults.colors(thumbColor = LocalPrismAccent.current, activeTrackColor = LocalPrismAccent.current),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }) {
    Slider(value = value, onValueChange = onValueChange, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        valueRange = valueRange, steps = steps, onValueChangeFinished = onValueChangeFinished, colors = colors, interactionSource = interactionSource)
}
@Composable fun PrismOutlinedTextField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null, suffix: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null, shape: Shape = RoundedCornerShape(12.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()) {
    OutlinedTextField(value, onValueChange, modifier.heightIn(min = 56.dp), enabled, readOnly, textStyle,
        label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError, visualTransformation,
        keyboardOptions, keyboardActions, singleLine, maxLines, minLines, interactionSource, shape, colors)
}

@Composable fun PrismOutlinedTextField(value: androidx.compose.ui.text.input.TextFieldValue, onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, readOnly: Boolean = false, textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null, trailingIcon: (@Composable () -> Unit)? = null,
    prefix: (@Composable () -> Unit)? = null, suffix: (@Composable () -> Unit)? = null, supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false, maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE, minLines: Int = 1,
    interactionSource: MutableInteractionSource? = null, shape: Shape = RoundedCornerShape(12.dp),
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors()) {
    OutlinedTextField(value, onValueChange, modifier.heightIn(min = 56.dp), enabled, readOnly, textStyle,
        label, placeholder, leadingIcon, trailingIcon, prefix, suffix, supportingText, isError, visualTransformation,
        keyboardOptions, keyboardActions, singleLine, maxLines, minLines, interactionSource, shape, colors)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PrismActionRow(modifier: Modifier = Modifier, horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(8.dp),
    verticalAlignment: androidx.compose.ui.Alignment.Vertical = androidx.compose.ui.Alignment.CenterVertically,
    content: @Composable RowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = horizontalArrangement, verticalArrangement = Arrangement.spacedBy(4.dp)) { content(this) }
}
@Composable fun PrismSectionHeading(title: String, description: String = "") {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PrismText(title, color = LocalPrismAccent.current, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (description.isNotBlank()) PrismText(description, color = PrismMuted, fontSize = 12.sp)
    }
}
@Composable fun PrismDisclosure(title: String, description: String, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        PrismOutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) { PrismText(title); PrismText(description, color = PrismMuted, fontSize = 12.sp) }
            PrismText(if (open) "Hide" else "Show")
        }
        val holder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        if (open) holder.SaveableStateProvider(title) { content() }
    }
}
/** Same composition slots while resizing: window changes never recreate business state or dispatch work. */
@Composable fun PrismAdaptivePair(primary: @Composable () -> Unit, secondary: @Composable () -> Unit) {
    Layout(content = { Column(Modifier.fillMaxWidth()) { primary() }; Column(Modifier.fillMaxWidth()) { secondary() } }) { measurables, constraints ->
        val gap = 16.dp.roundToPx()
        val wide = constraints.maxWidth >= 720.dp.roundToPx()
        val width = if (wide) (constraints.maxWidth - gap) / 2 else constraints.maxWidth
        val children = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = Constraints.Infinity)) }
        val height = if (wide) children.maxOf { it.height } else children.sumOf { it.height } + gap
        layout(constraints.maxWidth, constraints.constrainHeight(height)) {
            children[0].placeRelative(0, 0)
            children[1].placeRelative(if (wide) width + gap else 0, if (wide) 0 else children[0].height + gap)
        }
    }
}

@Composable fun PrismCard(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(16.dp),
    colors: CardColors = CardDefaults.cardColors(containerColor = PrismSurface), elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = BorderStroke(1.dp, Color(0xFF363341)), content: @Composable ColumnScope.() -> Unit) {
    Card(modifier, shape, colors, elevation, border, content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PrismCard(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(16.dp), colors: CardColors = CardDefaults.cardColors(containerColor = PrismSurface),
    elevation: CardElevation = CardDefaults.cardElevation(), border: BorderStroke? = BorderStroke(1.dp, Color(0xFF363341)),
    interactionSource: MutableInteractionSource? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(onClick, modifier, enabled, shape, colors, elevation, border, interactionSource, content)
}
