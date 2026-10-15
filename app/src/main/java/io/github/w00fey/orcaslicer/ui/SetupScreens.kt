package io.github.w00fey.orcaslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** First-run: choose which printer brands to install profiles for. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VendorScreen(state: UiState, vm: AppViewModel) {
    var chosen by rememberSaveable { mutableStateOf(state.installedVendors.toList()) }
    var query by rememberSaveable { mutableStateOf("") }
    val canGoBack = state.selection.printer.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Printer brands") },
            navigationIcon = {
                if (canGoBack) IconButton(onClick = { vm.navigate(Screen.PRINTERS) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            actions = {
                TextButton(onClick = { vm.saveVendors(chosen.toSet()) }) { Text("Continue") }
            },
        )
        Text(
            "Pick the brands of the printers you own. Only these profiles are loaded, which keeps the app fast.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SearchField(query, { query = it }, "Search brands")
        LazyColumn(Modifier.weight(1f)) {
            val filtered = state.vendors.filter { it.name.contains(query, true) || it.id.contains(query, true) }
            items(filtered, key = { it.id }) { v ->
                val checked = v.id in chosen
                ListItem(
                    headlineContent = { Text(v.name) },
                    supportingContent = { Text("${v.modelCount} printer models") },
                    trailingContent = {
                        Checkbox(checked, onCheckedChange = { chosen = if (it) chosen + v.id else chosen - v.id })
                    },
                    modifier = Modifier.clickable { chosen = if (checked) chosen - v.id else chosen + v.id },
                )
            }
        }
        Button(
            onClick = { vm.saveVendors(chosen.toSet()) },
            enabled = chosen.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) { Text(if (chosen.isEmpty()) "Select at least one brand" else "Continue with ${chosen.size}") }
    }
}

/** Choose the active printer from the installed profiles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(state: UiState, vm: AppViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val canGoBack = state.selection.printer.isNotEmpty()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Choose printer") },
            navigationIcon = {
                if (canGoBack) IconButton(onClick = { vm.navigate(Screen.PLATE) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            actions = { TextButton(onClick = { vm.navigate(Screen.VENDORS) }) { Text("Brands") } },
        )
        SearchField(query, { query = it }, "Search printers")
        val filtered = remember(state.printers, query) {
            state.printers.filter { it.name.contains(query, true) || it.vendor.contains(query, true) }
                .groupBy { it.vendor.ifEmpty { "Other" } }
                .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        }
        LazyColumn(Modifier.weight(1f)) {
            filtered.forEach { (vendor, printers) ->
                item(key = "h-$vendor") {
                    Text(
                        vendor,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(printers, key = { it.name }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { if (p.nozzle.isNotEmpty()) Text("${p.nozzle} mm nozzle") },
                        trailingContent = {
                            if (p.name == state.selection.printer) Icon(Icons.Default.Check, "Selected")
                        },
                        modifier = Modifier.clickable { vm.selectPrinter(p.name) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text(hint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
