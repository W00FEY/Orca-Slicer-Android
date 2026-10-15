package io.github.w00fey.orcaslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.w00fey.orcaslicer.engine.OptionDef
import io.github.w00fey.orcaslicer.engine.OptionType
import kotlinx.coroutines.launch

/** The most commonly changed settings, grouped like the desktop app's process/filament tabs. */
private val CATEGORIES: List<Pair<String, List<String>>> = listOf(
    "Quality" to listOf(
        "layer_height", "initial_layer_print_height", "line_width", "outer_wall_line_width", "inner_wall_line_width",
        "seam_position", "wall_generator", "ironing_type", "only_one_wall_top", "detect_thin_wall",
    ),
    "Strength" to listOf(
        "wall_loops", "top_shell_layers", "top_shell_thickness", "bottom_shell_layers", "bottom_shell_thickness",
        "sparse_infill_density", "sparse_infill_pattern", "top_surface_pattern", "bottom_surface_pattern",
    ),
    "Speed" to listOf(
        "initial_layer_speed", "outer_wall_speed", "inner_wall_speed", "sparse_infill_speed",
        "internal_solid_infill_speed", "top_surface_speed", "gap_infill_speed", "support_speed", "travel_speed",
        "default_acceleration",
    ),
    "Support" to listOf(
        "enable_support", "support_type", "support_style", "support_threshold_angle", "support_on_build_plate_only",
        "support_top_z_distance", "support_interface_top_layers", "raft_layers",
    ),
    "Adhesion & other" to listOf(
        "curr_bed_type", "brim_type", "brim_width", "skirt_loops", "skirt_distance", "print_sequence",
        "spiral_mode", "enable_prime_tower", "fuzzy_skin", "timelapse_type",
    ),
    "Filament" to listOf(
        "nozzle_temperature_initial_layer", "nozzle_temperature", "hot_plate_temp_initial_layer", "hot_plate_temp",
        "textured_plate_temp", "cool_plate_temp", "fan_min_speed", "fan_max_speed", "filament_flow_ratio",
        "filament_max_volumetric_speed", "filament_diameter", "filament_density", "filament_cost",
    ),
    "Printer" to listOf(
        "printable_height", "nozzle_diameter", "retraction_length", "retraction_speed", "z_hop", "gcode_flavor",
        "machine_start_gcode", "machine_end_gcode",
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var options by remember { mutableStateOf<List<OptionDef>?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val tabs = CATEGORIES.map { it.first } + "All"

    LaunchedEffect(tab, state.selection) {
        options = null
        val keys = if (tab < CATEGORIES.size) CATEGORIES[tab].second else {
            val k = vm.optionKeys()
            (k.process + k.filament + k.printer).distinct()
        }
        options = vm.loadOptions(keys)
    }

    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("Print settings")
                    Text(
                        "${state.overrides.size} changed from presets",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { vm.navigate(Screen.PLATE) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
            actions = {
                TextButton(onClick = { confirmReset = true }, enabled = state.overrides.isNotEmpty()) { Text("Reset all") }
            },
        )
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        if (tab == tabs.lastIndex) SearchField(query, { query = it }, "Search all settings")
        val list = options
        if (list == null) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            val filtered = if (tab == tabs.lastIndex && query.isNotBlank()) {
                list.filter { it.label.contains(query, true) || it.key.contains(query, true) || it.category.contains(query, true) }
            } else list
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.key }) { opt ->
                    OptionRow(opt, vm) { updated -> options = options?.map { if (it.key == updated.key) updated else it } }
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset all settings?") },
            text = { Text("All changes are discarded and the selected presets' values are used again.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetAllOptions() }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OptionRow(opt: OptionDef, vm: AppViewModel, onUpdated: (OptionDef) -> Unit) {
    val scope = rememberCoroutineScope()
    var error by remember(opt.key) { mutableStateOf<String?>(null) }
    var showHelp by remember { mutableStateOf(false) }

    fun commit(value: String?) {
        scope.launch {
            error = vm.setOption(opt.key, value)
            if (error == null) vm.loadOptions(listOf(opt.key)).firstOrNull()?.let(onUpdated)
        }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(opt.label.ifEmpty { opt.key }, style = MaterialTheme.typography.bodyLarge)
                if (opt.overridden) {
                    Text("Changed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            if (opt.tooltip.isNotEmpty()) {
                IconButton(onClick = { showHelp = true }) { Icon(Icons.Default.Info, "Help") }
            }
            if (opt.overridden) {
                IconButton(onClick = { commit(null) }) { Icon(Icons.Default.RestartAlt, "Reset to preset") }
            }
            if (opt.baseType == OptionType.BOOL) {
                Switch(
                    checked = opt.displayValue == "1",
                    onCheckedChange = { commit(if (it) "1" else "0") },
                    enabled = !opt.readonly,
                )
            }
        }
        when {
            opt.baseType == OptionType.BOOL -> Unit
            opt.baseType == OptionType.ENUM && opt.enumValues.isNotEmpty() -> EnumField(opt) { commit(it) }
            else -> TextValueField(opt) { commit(it) }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(opt.label) },
            text = { Text(opt.tooltip + "\n\nKey: ${opt.key}") },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("OK") } },
        )
    }
}

@Composable
private fun EnumField(opt: OptionDef, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val idx = opt.enumValues.indexOf(opt.displayValue)
    val label = opt.enumLabels.getOrNull(idx)?.ifEmpty { null } ?: opt.displayValue
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !opt.readonly) { open = true }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            opt.enumValues.forEachIndexed { i, v ->
                DropdownMenuItem(
                    text = { Text(opt.enumLabels.getOrNull(i)?.ifEmpty { null } ?: v) },
                    onClick = { open = false; onPick(v) },
                )
            }
        }
    }
}

@Composable
private fun TextValueField(opt: OptionDef, onCommit: (String) -> Unit) {
    val original = opt.displayValue
    var text by remember(opt.key, original) { mutableStateOf(original) }
    val multiline = opt.baseType == OptionType.STRING && (original.contains('\n') || opt.key.endsWith("gcode"))
    val numeric = opt.baseType in setOf(OptionType.FLOAT, OptionType.INT, OptionType.PERCENT)
    var suggestions by remember { mutableStateOf(false) }

    fun commitIfChanged() {
        if (text != original) onCommit(text)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = !opt.readonly,
            singleLine = !multiline,
            minLines = if (multiline) 4 else 1,
            maxLines = if (multiline) 12 else 1,
            suffix = { if (opt.sidetext.isNotEmpty() && !multiline) Text(opt.sidetext, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
                imeAction = if (multiline) ImeAction.Default else ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { commitIfChanged() }),
            textStyle = if (multiline) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).onFocusChanged { if (!it.isFocused) commitIfChanged() },
        )
        // Some numeric options offer preset values (e.g. infill density steps).
        if (opt.enumValues.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(onClick = { suggestions = true }) { Icon(Icons.Default.ArrowDropDown, "Suggestions") }
                DropdownMenu(suggestions, onDismissRequest = { suggestions = false }) {
                    opt.enumValues.forEachIndexed { i, v ->
                        DropdownMenuItem(
                            text = { Text(opt.enumLabels.getOrNull(i)?.ifEmpty { null } ?: v) },
                            onClick = { suggestions = false; text = v; onCommit(v) },
                        )
                    }
                }
            }
        }
    }
}
