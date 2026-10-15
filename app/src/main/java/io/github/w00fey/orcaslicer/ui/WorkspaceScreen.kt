package io.github.w00fey.orcaslicer.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import io.github.w00fey.orcaslicer.engine.PresetRef
import io.github.w00fey.orcaslicer.render.PlateRenderer
import io.github.w00fey.orcaslicer.render.PlateView
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun WorkspaceScreen(state: UiState, vm: AppViewModel) {
    val preview = state.screen == Screen.PREVIEW
    val dark = isSystemInDarkTheme()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                PlateView(ctx, vm.renderer).apply {
                    vm.requestRender = { requestRender() }
                    onSelect = { vm.select(it) }
                    onMoved = { o, i, dx, dy -> vm.moveBy(o, i, dx, dy) }
                }
            },
            update = { view ->
                view.bedBounds = vm.bedBounds
                vm.renderer.darkTheme = dark
                vm.renderer.previewMode = preview
                view.requestRender()
            },
            modifier = Modifier.fillMaxSize(),
        )
        DisposableEffect(Unit) { onDispose { vm.requestRender = {} } }

        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            TopBar(state, vm)
            PresetBar(state, vm)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            if (preview) PreviewPanel(state, vm) else PlatePanel(state, vm)
        }

        state.slicing?.let { SlicingDialog(it.percent, it.text, onCancel = vm::cancelSlice) }
        state.uploadProgress?.let { UploadDialog(it) }
        state.busy?.let {
            Surface(
                Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(it)
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: UiState, vm: AppViewModel) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            SegmentedButton(
                selected = state.screen == Screen.PLATE,
                onClick = { vm.navigate(Screen.PLATE) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                icon = {},
            ) { Text("Prepare") }
            SegmentedButton(
                selected = state.screen == Screen.PREVIEW,
                onClick = {
                    if (state.sliceResult != null) vm.navigate(Screen.PREVIEW) else vm.slice()
                },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                icon = {},
            ) { Text("Preview") }
        }
        IconButton(onClick = { vm.navigate(Screen.SETTINGS) }) { Icon(Icons.Default.Tune, "Print settings") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Change printer") }, onClick = { menu = false; vm.navigate(Screen.PRINTERS) })
                DropdownMenuItem(text = { Text("Printer brands") }, onClick = { menu = false; vm.navigate(Screen.VENDORS) })
                DropdownMenuItem(text = { Text("Network printer") }, onClick = { menu = false; vm.navigate(Screen.PRINT_HOST) })
                vm.sampleModels().forEach { name ->
                    DropdownMenuItem(
                        text = { Text("Add sample: " + name.substringBeforeLast('.').replace('_', ' ')) },
                        onClick = { menu = false; vm.loadSample(name) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("About") },
                    onClick = { menu = false; vm.showMessage("Orca Slicer for Android – engine OrcaSlicer ${state.engineVersion}") },
                )
            }
        }
    }
}

@Composable
private fun PresetBar(state: UiState, vm: AppViewModel) {
    var sheet by remember { mutableStateOf<String?>(null) }
    val sel = state.selection
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PresetChip("Printer", sel.printer) { vm.navigate(Screen.PRINTERS) }
        PresetChip("Filament", sel.filament) { sheet = "filament" }
        PresetChip("Process", sel.process) { sheet = "process" }
    }
    when (sheet) {
        "filament" -> PresetSheet("Filament", sel.filaments, sel.filament, { sheet = null }) { vm.selectFilament(it) }
        "process" -> PresetSheet("Process", sel.processes, sel.process, { sheet = null }) { vm.selectProcess(it) }
    }
}

@Composable
private fun PresetChip(label: String, value: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(value.ifEmpty { "—" }, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(150.dp))
            }
        },
        trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetSheet(title: String, items: List<PresetRef>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
        SearchField(query, { query = it }, "Search ${title.lowercase()}")
        val filtered = items.filter { it.name.contains(query, true) }
        LazyColumn(Modifier.fillMaxWidth().height(480.dp)) {
            items(filtered, key = { it.name }) { p ->
                ListItem(
                    headlineContent = {
                        Text(p.name, fontWeight = if (p.name == current) FontWeight.Bold else FontWeight.Normal)
                    },
                    supportingContent = { if (!p.system) Text("User preset") },
                    modifier = Modifier.clickable { onPick(p.name); onDismiss() },
                )
            }
        }
    }
}

// ---------------------------------------------------------------- plate mode

@Composable
private fun PlatePanel(state: UiState, vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importModels(uris)
    }
    val sel = state.selected
    if (sel != null) {
        val obj = state.objects.getOrNull(sel.first)
        val inst = obj?.instances?.getOrNull(sel.second)
        if (obj != null && inst != null) ObjectPanel(obj.name, sel, inst, obj.instances.size, vm)
    } else if (state.objects.isEmpty()) {
        Card(
            Modifier.padding(16.dp).fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("Empty plate", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Import STL, 3MF, OBJ or STEP files. Drag to rotate the view, pinch to zoom, two fingers to pan. " +
                        "Tap a model to select it; long-press and drag to move it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if ("3DBenchy.drc" in vm.sampleModels()) {
                    TextButton(onClick = { vm.loadSample("3DBenchy.drc") }) { Text("Try a 3DBenchy") }
                }
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Icon(Icons.Default.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Import")
        }
        RoundIcon(Icons.Default.GridView, "Arrange", enabled = state.objects.isNotEmpty()) { vm.arrange() }
        RoundIcon(Icons.Default.DeleteSweep, "Clear plate", enabled = state.objects.isNotEmpty()) { vm.clearPlate() }
        Spacer(Modifier.weight(1f))
        Button(onClick = vm::slice, enabled = state.objects.isNotEmpty() && state.slicing == null) {
            Icon(Icons.Default.ViewInAr, null)
            Spacer(Modifier.width(6.dp))
            Text("Slice")
        }
    }
}

@Composable
private fun RoundIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = CircleShape, tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, desc) }
    }
}

@Composable
private fun ObjectPanel(
    name: String,
    sel: Pair<Int, Int>,
    inst: io.github.w00fey.orcaslicer.engine.ModelInstanceInfo,
    instanceCount: Int,
    vm: AppViewModel,
) {
    // Local edit buffers, reset whenever the engine reports a new transform.
    val key = inst.matrix
    var x by remember(key) { mutableStateOf(fmt(inst.offset[0])) }
    var y by remember(key) { mutableStateOf(fmt(inst.offset[1])) }
    var rot by remember(key) { mutableStateOf(fmt(inst.rotationDeg[2])) }
    var scale by remember(key) { mutableStateOf(fmt(inst.scale[0] * 100)) }

    fun apply() {
        val nx = x.toDoubleOrNull() ?: return
        val ny = y.toDoubleOrNull() ?: return
        val nr = rot.toDoubleOrNull() ?: return
        val ns = scale.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        vm.setTransform(sel.first, sel.second, nx, ny, nr, ns / 100.0)
    }

    Card(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "%.1f × %.1f × %.1f mm".format(Locale.US, inst.size[0], inst.size[1], inst.size[2]) +
                            if (instanceCount > 1) "  ·  $instanceCount copies" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { vm.select(null) }) { Icon(Icons.Default.Close, "Deselect") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NumberField("X", x, Modifier.weight(1f)) { x = it }
                NumberField("Y", y, Modifier.weight(1f)) { y = it }
                NumberField("Rot Z°", rot, Modifier.weight(1f)) { rot = it }
                NumberField("Scale %", scale, Modifier.weight(1f)) { scale = it }
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = ::apply) { Text("Apply") }
                IconButton(onClick = { vm.rotate(sel.first, sel.second, 0, 90.0) }) {
                    Text("X90", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { vm.rotate(sel.first, sel.second, 1, 90.0) }) {
                    Text("Y90", style = MaterialTheme.typography.labelLarge)
                }
                IconButton(onClick = { vm.rotate(sel.first, sel.second, 2, 90.0) }) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, "Rotate 90° around Z")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.duplicate(sel.first) }) { Icon(Icons.Default.ContentCopy, "Add copy") }
                IconButton(onClick = { vm.remove(sel.first) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        textStyle = MaterialTheme.typography.bodyMedium,
        modifier = modifier,
    )
}

private fun fmt(v: Double) = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.2f".format(Locale.US, v)

// ---------------------------------------------------------------- preview mode

@Composable
private fun PreviewPanel(state: UiState, vm: AppViewModel) {
    val result = state.sliceResult ?: return
    val context = LocalContext.current
    var showLegend by remember { mutableStateOf(false) }
    var confirmSend by remember { mutableStateOf(false) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x.gcode")) { uri ->
        if (uri != null) vm.exportGcode(uri)
    }

    if (showLegend) Legend(Modifier.padding(12.dp))

    Card(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (state.sliceStale) {
                Text(
                    "The plate or settings changed since this slice.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("Time", formatDuration(result.printTimeS))
                Stat("Filament", "%.1f g".format(Locale.US, result.filamentG))
                Stat("Length", "%.2f m".format(Locale.US, result.filamentMm / 1000))
                if (result.filamentCost > 0) Stat("Cost", "%.2f".format(Locale.US, result.filamentCost))
            }
            if (state.layerCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Layer ${state.visibleLayers}/${state.layerCount}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(110.dp))
                    Slider(
                        value = state.visibleLayers.toFloat(),
                        onValueChange = { vm.setVisibleLayers(it.roundToInt()) },
                        valueRange = 1f..state.layerCount.toFloat().coerceAtLeast(1.01f),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showLegend = !showLegend }) { Text(if (showLegend) "Hide legend" else "Legend") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { vm.gcodeFile()?.let { saver.launch(it.name) } }) { Icon(Icons.Default.Save, "Save G-code") }
                IconButton(onClick = {
                    val file = vm.gcodeFile() ?: return@IconButton
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/x.gcode"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(send, "Share G-code"))
                }) { Icon(Icons.Default.Share, "Share G-code") }
                Button(onClick = { if (state.printHost.isConfigured) confirmSend = true else vm.navigate(Screen.PRINT_HOST) }) {
                    Icon(Icons.Default.Print, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Print")
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))

    if (confirmSend) {
        var start by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("Send to ${state.printHost.type.label}") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(start, onCheckedChange = { start = it })
                    Text("Start printing after upload")
                }
            },
            confirmButton = { TextButton(onClick = { confirmSend = false; vm.sendToPrinter(start) }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Legend(modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))) {
        Column(Modifier.padding(12.dp)) {
            val shown = listOf(2, 1, 3, 4, 5, 6, 7, 9, 11, 8, 12, 13, 14, 15, 17)
            shown.chunked(2).forEach { pair ->
                Row {
                    pair.forEach { role ->
                        val c = PlateRenderer.ROLE_COLORS
                        Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(12.dp).clip(CircleShape)
                                    .background(Color(c[role * 4], c[role * 4 + 1], c[role * 4 + 2])),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(PlateRenderer.ROLE_NAMES[role], style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

fun formatDuration(seconds: Double): String {
    val s = seconds.roundToInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else if (m > 0) "${m}m ${s % 60}s" else "${s}s"
}

@Composable
private fun SlicingDialog(percent: Int, text: String, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Slicing…") },
        text = {
            Column {
                LinearProgressIndicator(progress = { percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("$percent%  $text", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { OutlinedButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun UploadDialog(progress: Float) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Uploading…") },
        text = { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = {},
    )
}
