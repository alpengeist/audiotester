package com.audiotester

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.*

private val BandColors = listOf(Color(0xFFEF7188), Color(0xFF61C8D9), Color(0xFFAE8BFF), Color(0xFFD8D5CC), Color(0xFF7FCD89), Color(0xFFFFBD6A))
// "0" labels the graph's left boundary; a logarithmic axis cannot represent DC.
private val FrequencyTicks = listOf(
    20f to "0", 32f to "32", 64f to "64", 125f to "125", 250f to "250", 500f to "500",
    1000f to "1k", 2000f to "2k", 4000f to "4k", 8000f to "8k", 16_000f to "16k",
)
private fun bandColor(index: Int) = BandColors[index % BandColors.size]
private fun decimal(value: Number, places: Int = 1) = String.format(Locale.US, "%.${places}f", value.toDouble())

@Composable
fun EqualizerControls(session: EqSession, plan: EqPlan, onChange: (EqSession) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Parametric EQ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = session.settings.enabled, onCheckedChange = {
                onChange(session.copy(settings = session.settings.copy(enabled = it)))
            }, modifier = Modifier.semantics { contentDescription = "Enable parametric EQ" })
        }
        Text(session.presetLabel ?: "Unsaved EQ", style = MaterialTheme.typography.bodyMedium)
        Text("${session.settings.bands.count { it.enabled }} active bands · Headroom −${decimal(plan.appliedHeadroomDb)} dB",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit equalizer") }
    }
    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                EqualizerEditor(session, plan, onChange)
            }
        }
    }
}

@Composable
private fun EqualizerEditor(session: EqSession, plan: EqPlan, onChange: (EqSession) -> Unit) {
    var selectedId by rememberSaveable { mutableStateOf(session.settings.bands.firstOrNull()?.id) }
    var parameter by rememberSaveable { mutableStateOf("dB") }
    var presetMenu by remember { mutableStateOf(false) }
    var bandMenuId by remember { mutableStateOf<Long?>(null) }
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var saveName by rememberSaveable { mutableStateOf("") }
    var sheetDialog by rememberSaveable { mutableStateOf(false) }
    var deletePreset by remember { mutableStateOf<String?>(null) }
    val settings = session.settings
    val selectedIndex = settings.bands.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: 0
    val selected = settings.bands.getOrNull(selectedIndex)
    fun changeSettings(next: EqSettings) = onChange(session.copy(settings = next))
    fun changeBand(next: EqBand) = changeSettings(settings.copy(bands = settings.bands.map { if (it.id == next.id) next else it }))

    EqualizerEditorLayout {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { presetMenu = true }, modifier = Modifier.weight(1f)
                .semantics { contentDescription = "Equalizer presets: ${session.presetLabel ?: "Unsaved EQ"}" }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(session.presetLabel ?: "Save EQ preset", modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
            TextButton(onClick = { sheetDialog = true }) { Text("Sheet") }
            Switch(settings.enabled, { changeSettings(settings.copy(enabled = it)) },
                modifier = Modifier.semantics { contentDescription = "Enable equalizer" })
        }
        EqGraph(settings, plan, selected?.id, onSelect = { selectedId = it; bandMenuId = null })
        Text(if (settings.enabled) "Headroom attenuation: −${decimal(plan.headroomDb)} dB"
            else "EQ bypassed · Headroom attenuation: 0.0 dB",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            settings.bands.forEachIndexed { index, band ->
                val isSelected = band.id == selected?.id
                Box {
                    FilterChip(selected = isSelected, onClick = {
                        if (isSelected) {
                            bandMenuId = band.id
                        } else {
                            selectedId = band.id
                            bandMenuId = null
                        }
                    }, label = { Text("${index + 1}${if (band.enabled) "" else " · off"}") },
                        trailingIcon = if (isSelected) {
                            { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = bandColor(index), selectedContainerColor = bandColor(index),
                            selectedLabelColor = Color.Black, labelColor = Color.Black,
                            iconColor = Color.Black, selectedTrailingIconColor = Color.Black),
                        modifier = Modifier.semantics {
                            contentDescription = "${if (isSelected) "Open menu for" else "Select"} band ${index + 1}, ${if (band.enabled) "active" else "disabled"}"
                        })
                    DropdownMenu(expanded = isSelected && bandMenuId == band.id, onDismissRequest = { bandMenuId = null }) {
                        DropdownMenuItem(text = { Text(if (band.enabled) "Disable band" else "Enable band") }, onClick = {
                            bandMenuId = null
                            changeBand(band.copy(enabled = !band.enabled))
                        })
                        DropdownMenuItem(text = { Text("Delete band") }, onClick = {
                            bandMenuId = null
                            val remaining = settings.bands.filterNot { it.id == band.id }
                            selectedId = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex).coerceAtLeast(0))?.id
                            changeSettings(settings.copy(bands = remaining))
                        })
                    }
                }
            }
            FilledTonalButton(onClick = {
                val band = EqBand((settings.bands.maxOfOrNull { it.id } ?: 0L) + 1L)
                changeSettings(settings.copy(bands = settings.bands + band))
                selectedId = band.id
                bandMenuId = null
            }, modifier = Modifier.width(48.dp).semantics { contentDescription = "Add band" },
                contentPadding = PaddingValues(0.dp)) { Text("+") }
        }
        if (selected == null) {
            Text("No bands. Add a band to begin.")
        } else {
            val value = when (parameter) { "Hz" -> selected.frequencyHz; "Q" -> selected.q; else -> selected.gainDb }
            Text(when (parameter) { "Hz" -> "${value.roundToInt()} Hz"; "Q" -> "${decimal(value, 2)} Q"; else -> "${decimal(value)} dB" },
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally))
            val position = when (parameter) {
                "Hz" -> frequencyToSlider(value)
                "Q" -> (ln(value / 0.1f) / ln(100f))
                else -> (value + 15f) / 30f
            }
            ParameterScale(position, parameter, bandColor(selectedIndex),
                type = selected.type, onTypeChange = { changeBand(selected.copy(type = it)) },
                onParameterChange = { parameter = it }) { fraction ->
                val next = when (parameter) {
                    "Hz" -> selected.copy(frequencyHz = sliderToFrequency(fraction).roundToInt().toFloat())
                    "Q" -> selected.copy(q = (0.1 * 100.0.pow(fraction.toDouble()) * 100).roundToInt() / 100f)
                    else -> selected.copy(gainDb = ((fraction * 30 - 15) * 10).roundToInt() / 10f)
                }
                changeBand(next.validated())
            }
        }
    }
    if (presetMenu) PresetMenu(session, onDismiss = { presetMenu = false }, onSave = { name ->
        presetMenu = false
        saveName = name
        saveDialog = true
    }, onLoad = { name ->
        val next = session.presets.getValue(name)
        selectedId = next.bands.firstOrNull()?.id
        bandMenuId = null
        onChange(session.copy(settings = next, activePreset = name))
        presetMenu = false
    }, onReset = {
        val next = session.resetToDefault()
        selectedId = next.settings.bands.firstOrNull()?.id
        bandMenuId = null
        onChange(next)
        presetMenu = false
    }, onDelete = { name ->
        presetMenu = false
        deletePreset = name
    })
    if (saveDialog) SavePresetDialog(session, saveName, onDismiss = { saveDialog = false }) { name ->
        onChange(session.copy(presets = session.presets + (name to settings), activePreset = name))
        saveDialog = false
    }
    deletePreset?.let { name ->
        AlertDialog(onDismissRequest = { deletePreset = null }, title = { Text("Delete preset?") },
            text = { Text("Delete “$name”? The current EQ settings will stay in place.") },
            confirmButton = { TextButton(onClick = {
                onChange(session.deletePreset(name))
                deletePreset = null
            }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deletePreset = null }) { Text("Cancel") } })
    }
    if (sheetDialog) ParameterSheet(session, plan) { sheetDialog = false }
}

@Composable
private fun SavePresetDialog(session: EqSession, initialName: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    val cleanName = name.trim().replace(Regex("[\\t\\r\\n]+"), " ")
    val exists = cleanName in session.presets
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Save EQ preset") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Headphone / preset name") }, singleLine = true)
            if (exists) Text("This will replace the saved preset “$cleanName”.")
        } }, confirmButton = { TextButton(enabled = cleanName.isNotEmpty(), onClick = { onSave(cleanName) }) {
            Text(if (exists) "Replace" else "Save")
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetMenu(
    session: EqSession,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onLoad: (String) -> Unit,
    onReset: () -> Unit,
    onDelete: (String) -> Unit,
) {
    var actionPreset by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Equalizer presets", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Close preset menu") }
            }
            HorizontalDivider()
            TextButton(onClick = { onSave("") }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Create EQ preset", modifier = Modifier.weight(1f))
            }
            session.activePreset?.let { name ->
                TextButton(onClick = { onSave(name) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    Text("Save changes to current preset", modifier = Modifier.weight(1f))
                }
            }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text("Reset to default", modifier = Modifier.weight(1f))
            }
            HorizontalDivider()
            Text("SAVED EQ PRESETS", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (session.presets.isEmpty()) item {
                    Text("No saved presets", modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(session.presets.keys.sortedWith(String.CASE_INSENSITIVE_ORDER), key = { it }) { name ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onLoad(name) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = if (name == session.activePreset)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                            Text(name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Box {
                            IconButton(onClick = { actionPreset = name }) {
                                Icon(Icons.Filled.MoreHoriz, contentDescription = "Actions for preset $name")
                            }
                            DropdownMenu(expanded = actionPreset == name, onDismissRequest = { actionPreset = null }) {
                                DropdownMenuItem(text = { Text("Replace with current EQ") }, onClick = {
                                    actionPreset = null
                                    onSave(name)
                                })
                                DropdownMenuItem(text = { Text("Delete preset") }, onClick = {
                                    actionPreset = null
                                    onDelete(name)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterSheet(session: EqSession, plan: EqPlan, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val text = eqParameterSheet(session.settings, session.presetLabel, plan)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("EQ parameter sheet") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(session.presetLabel ?: "Unsaved EQ")
            Text("EQ ${if (session.settings.enabled) "on" else "off"} · Attenuation −${decimal(plan.appliedHeadroomDb)} dB")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    Text("#  On   Type         Hz       dB      Q", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    session.settings.bands.forEachIndexed { index, band ->
                        Text(String.format(Locale.US, "%2d %-3s %-10s %7.0f %6.1f %5.2f", index + 1,
                            if (band.enabled) "Yes" else "No", band.type.label, band.frequencyHz, band.gainDb, band.q),
                            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = bandColor(index))
                    }
                }
            }
            if (session.settings.bands.isEmpty()) Text("No filters")
        } }, confirmButton = { TextButton(onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("EQ parameters", text))
            copied = true
        }) { Text(if (copied) "Copied" else "Copy") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun EqualizerEditorLayout(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        val viewportHeight = maxHeight
        Layout(content = content, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { measurables, constraints ->
            val spacing = 12.dp.roundToPx()
            // Reserve the controls' natural height, then give the graph the remaining viewport.
            // On short screens, keep the graph usable and allow the whole editor to scroll.
            val measured = measurables.mapIndexed { index, measurable ->
                if (index == 1) null else measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            }.toMutableList()
            val controlsHeight = measured.sumOf { it?.height ?: 0 } + spacing * (measurables.size - 1)
            val graphHeight = max(240.dp.roundToPx(), viewportHeight.roundToPx() - controlsHeight)
            measured[1] = measurables[1].measure(constraints.copy(minHeight = graphHeight, maxHeight = graphHeight))
            layout(constraints.maxWidth, controlsHeight + graphHeight) {
                var top = 0
                measured.forEach { child ->
                    requireNotNull(child).placeRelative(0, top)
                    top += child.height + spacing
                }
            }
        }
    }
}

@Composable
private fun EqGraph(settings: EqSettings, plan: EqPlan, selectedId: Long?, onSelect: (Long) -> Unit) {
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val curveColor = MaterialTheme.colorScheme.primary
    val onSelectNow by rememberUpdatedState(onSelect)
    val selectedNow by rememberUpdatedState(selectedId)
    val responses = remember(plan) { (0..256).map { plan.responseDb(20.0 * 1000.0.pow(it / 256.0)).toFloat() } }
    val range = 15f
    val gainTicks = listOf(15, 10, 5, 0, -5, -10, -15)
    val axisWidth = 32.dp
    val axisGap = 8.dp
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Layout(content = {
                gainTicks.forEach { gain ->
                    Text(if (gain == 0) "00" else String.format(Locale.US, "%+03d", gain),
                        style = MaterialTheme.typography.labelSmall, color = foreground,
                        modifier = Modifier.semantics { contentDescription = "$gain dB" })
                }
            }, modifier = Modifier.width(axisWidth).fillMaxHeight()) { measurables, constraints ->
                val measured = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                layout(constraints.maxWidth, constraints.maxHeight) {
                    measured.forEachIndexed { index, label ->
                        val center = constraints.maxHeight * (0.5f - gainTicks[index] / (2 * range))
                        label.place(constraints.maxWidth - label.width, (center - label.height / 2f).roundToInt())
                    }
                }
            }
            Spacer(Modifier.width(axisGap))
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().background(background)) {
                val graphHeight = maxHeight
                Canvas(Modifier.fillMaxSize().clipToBounds()
                    .semantics { contentDescription = "Equalizer frequency response. Select bands using the markers or numbered buttons below." }
                    .pointerInput(settings.bands, range) {
                        detectTapGestures { tap ->
                            val candidates = settings.bands.map { band ->
                                val point = Offset(frequencyToSlider(band.frequencyHz) * size.width,
                                    size.height * (0.5f - band.gainDb / (2 * range)))
                                band.id to (tap - point).getDistance()
                            }.filter { it.second <= 26.dp.toPx() }.sortedBy { it.second }
                            // Cycle coincident markers so every overlapping band remains selectable.
                            val closest = candidates.firstOrNull()
                            if (closest != null) {
                                val tied = candidates.filter { abs(it.second - closest.second) < 2.dp.toPx() }
                                val current = tied.indexOfFirst { it.first == selectedNow }
                                onSelectNow(tied[(current + 1) % tied.size].first)
                            }
                        }
                    }) {
                    fun x(hz: Float) = frequencyToSlider(hz) * size.width
                    fun y(db: Float) = size.height * (0.5f - db / (2 * range))
                    for ((hz, _) in FrequencyTicks) {
                        val x = x(hz)
                        drawLine(foreground.copy(alpha = 0.4f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.25.dp.toPx())
                    }
                    for (step in -3..3) drawLine(foreground.copy(alpha = if (step == 0) 0.85f else 0.4f),
                        Offset(0f, y(step * range / 3)), Offset(size.width, y(step * range / 3)),
                        strokeWidth = (if (step == 0) 1.5.dp else 1.25.dp).toPx())
                    drawRect(foreground.copy(alpha = 0.4f), style = Stroke(1.25.dp.toPx()))
                    val path = Path()
                    responses.forEachIndexed { i, db ->
                        val x = i / 256f * size.width
                        val dbY = y(if (settings.enabled) db else 0f)
                        if (i == 0) path.moveTo(x, dbY) else path.lineTo(x, dbY)
                    }
                    drawPath(path, curveColor, style = Stroke(2.5.dp.toPx()))
                    val drawOrder = settings.bands.indices.sortedBy { if (settings.bands[it].id == selectedId) 1 else 0 }
                    drawOrder.forEach { index ->
                        val band = settings.bands[index]
                        val center = Offset(x(band.frequencyHz), y(band.gainDb))
                        val color = bandColor(index).copy(alpha = if (band.enabled && settings.enabled) 1f else 0.45f)
                        drawCircle(background, 10.dp.toPx(), center)
                        drawCircle(color, 10.dp.toPx(), center, style = Stroke(if (band.id == selectedId) 3.dp.toPx() else 1.5.dp.toPx()))
                        if (band.id == selectedId) drawCircle(color, 5.dp.toPx(), center)
                    }
                }
                val selectedIndex = settings.bands.indexOfFirst { it.id == selectedId }
                settings.bands.getOrNull(selectedIndex)?.let { band ->
                    val markerY = graphHeight * (0.5f - band.gainDb / (2 * range))
                    val labelTop = if (markerY >= 64.dp) markerY - 48.dp else markerY + 20.dp
                    Text("${decimal(band.gainDb)} dB · ${band.frequencyHz.roundToInt()} Hz · ${decimal(band.q, 2)} Q",
                        modifier = Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = labelTop)
                            .background(bandColor(selectedIndex), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                        color = Color.Black, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        val labels = FrequencyTicks
        Layout(content = { labels.forEach { Text(it.second, style = MaterialTheme.typography.labelSmall) } },
            modifier = Modifier.fillMaxWidth().padding(start = axisWidth + axisGap)) { measurables, constraints ->
            val measured = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            layout(constraints.maxWidth, measured.maxOf { it.height }) {
                measured.forEachIndexed { i, label ->
                    val center = frequencyToSlider(labels[i].first) * constraints.maxWidth
                    label.place((center - label.width / 2).roundToInt().coerceIn(0, constraints.maxWidth - label.width), 0)
                }
            }
        }
    }
}

/** A graduated fan scale with a line indicator, controlled by dragging or accessibility actions. */
@Composable
private fun ParameterScale(
    position: Float,
    parameter: String,
    color: Color,
    type: EqFilterType,
    onTypeChange: (EqFilterType) -> Unit,
    onParameterChange: (String) -> Unit,
    onChange: (Float) -> Unit,
) {
    var typeMenu by remember { mutableStateOf(false) }
    val changeNow by rememberUpdatedState(onChange)
    val positionNow by rememberUpdatedState(position)
    val ticks = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(Modifier.fillMaxSize()
                .semantics {
                    contentDescription = "$parameter adjustment scale"
                    progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..1f)
                    setProgress { changeNow(it.coerceIn(0f, 1f)); true }
                }
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.DirectionLeft || it.key == Key.DirectionRight)) {
                        changeNow((positionNow + if (it.key == Key.DirectionRight) 1f / 300 else -1f / 300).coerceIn(0f, 1f)); true
                    } else false
                }.focusable()
                .pointerInput(parameter) {
                    fun changeAt(point: Offset) {
                        val angle = atan2((point.x - size.width / 2f).toDouble(), (size.height + 35.dp.toPx() - point.y).toDouble()) * 180 / PI
                        changeNow(((angle.toFloat() + 65) / 130).coerceIn(0f, 1f))
                    }
                    detectTapGestures { changeAt(it) }
                }
                .pointerInput(parameter) {
                    fun changeAt(point: Offset) {
                        val angle = atan2((point.x - size.width / 2f).toDouble(), (size.height + 35.dp.toPx() - point.y).toDouble()) * 180 / PI
                        changeNow(((angle.toFloat() + 65) / 130).coerceIn(0f, 1f))
                    }
                    detectDragGestures(onDragStart = { changeAt(it) }) { change, _ -> change.consume(); changeAt(change.position) }
                }) {
                val pivot = Offset(size.width / 2, size.height + 35.dp.toPx())
                val radius = min(size.width * 0.52f, size.height + 25.dp.toPx())
                fun point(angle: Double, distance: Float) = Offset(pivot.x + sin(angle).toFloat() * distance, pivot.y - cos(angle).toFloat() * distance)
                for (i in 0..60) {
                    val angle = (-65 + 130.0 * i / 60) * PI / 180
                    val major = i % 5 == 0
                    drawLine(ticks.copy(alpha = if (major) 0.7f else 0.35f), point(angle, radius),
                        point(angle, radius - (if (major) 25.dp.toPx() else 12.dp.toPx())), if (major) 2.dp.toPx() else 1.dp.toPx())
                }
                val angle = (-65 + position * 130) * PI / 180
                drawLine(color, point(angle, radius + 5.dp.toPx()), point(angle, radius - 35.dp.toPx()), 3.dp.toPx())
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 52.dp)) {
                TextButton(onClick = { typeMenu = true }, modifier = Modifier.height(32.dp)
                    .semantics { contentDescription = "Filter type: ${type.label}" },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text("${type.label} ▾", style = MaterialTheme.typography.bodyMedium)
                }
                DropdownMenu(typeMenu, { typeMenu = false }) {
                    EqFilterType.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, onClick = {
                            onTypeChange(option)
                            typeMenu = false
                        })
                    }
                }
            }
            Row(Modifier.align(Alignment.BottomCenter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("dB", "Hz", "Q").forEach { item ->
                    val isSelected = parameter == item
                    Surface(onClick = { onParameterChange(item) }, shape = CircleShape,
                        color = if (isSelected) Color.White else Color.Transparent,
                        contentColor = if (isSelected) Color.Black else ticks,
                        border = if (isSelected) null else BorderStroke(1.5.dp, ticks),
                        modifier = Modifier.size(48.dp).semantics { selected = isSelected }) {
                        Box(contentAlignment = Alignment.Center) { Text(item, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(when (parameter) { "Hz" -> "20 Hz"; "Q" -> "0.10 Q"; else -> "−15 dB" }, style = MaterialTheme.typography.labelSmall)
            Text(when (parameter) { "Hz" -> "20,000 Hz"; "Q" -> "10.00 Q"; else -> "+15 dB" }, style = MaterialTheme.typography.labelSmall)
        }
    }
}
