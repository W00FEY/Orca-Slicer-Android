package io.github.w00fey.orcaslicer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.w00fey.orcaslicer.data.FlashforgePrinter
import io.github.w00fey.orcaslicer.data.HostType
import io.github.w00fey.orcaslicer.data.PrintHost
import kotlinx.coroutines.launch

/** Flashforge models that speak the TCP protocol on port 8899. */
fun isLegacyFlashforge(printer: String) =
    printer.startsWith("Flashforge Adventurer 3", ignoreCase = true) ||
        printer.startsWith("Flashforge Adventurer 4", ignoreCase = true) ||
        printer.startsWith("Flashforge Finder", ignoreCase = true) ||
        printer.startsWith("Flashforge Dreamer", ignoreCase = true) ||
        printer.startsWith("Flashforge Guider", ignoreCase = true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintHostScreen(state: UiState, vm: AppViewModel) {
    val suggested = if (!state.printHost.isConfigured && isLegacyFlashforge(state.selection.printer)) HostType.FLASHFORGE else state.printHost.type
    var type by rememberSaveable { mutableStateOf(suggested) }
    var url by rememberSaveable { mutableStateOf(state.printHost.url) }
    var key by rememberSaveable { mutableStateOf(state.printHost.apiKey) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<FlashforgePrinter>?>(null) }
    val scope = rememberCoroutineScope()
    val host = PrintHost(type, url.trim(), if (type == HostType.FLASHFORGE) "" else key.trim())

    Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
        TopAppBar(
            title = { Text("Network printer") },
            navigationIcon = {
                IconButton(onClick = { vm.navigate(Screen.PLATE) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            },
        )
        Column(
            Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Send sliced G-code straight to a printer on your Wi-Fi. Bambu Lab printers use a proprietary " +
                    "protocol and are not supported here; save the G-code and use Bambu Handy instead.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HostType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, HostType.entries.size),
                        icon = {},
                    ) { Text(t.label.substringBefore(" ("), maxLines = 1, style = MaterialTheme.typography.labelMedium) }
                }
            }

            if (type == HostType.FLASHFORGE) {
                Text(
                    "For the Adventurer 3, 3 Pro, 4, Finder and other Flashforge printers controlled over port 8899. " +
                        "Connect the printer to the same Wi-Fi as your phone (printer menu: Settings → WiFi), " +
                        "then search for it or type the IP address shown on its screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        searching = true
                        scope.launch {
                            found = vm.discoverFlashforge()
                            searching = false
                        }
                    },
                    enabled = !searching,
                ) {
                    if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (searching) "Searching…" else "Find printers on this network")
                }
                found?.let { list ->
                    if (list.isEmpty()) {
                        Text(
                            "No printer answered. Check it is on and on the same Wi-Fi, or enter its IP address below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Card {
                            list.forEach { p ->
                                ListItem(
                                    leadingContent = { Icon(Icons.Default.Print, null) },
                                    headlineContent = { Text(p.name) },
                                    supportingContent = { Text(p.address + if (p.serial.isNotEmpty()) " · ${p.serial}" else "") },
                                    modifier = Modifier.clickable { url = p.address },
                                )
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(if (type == HostType.FLASHFORGE) "Printer IP address" else "Address") },
                placeholder = { Text(if (type == HostType.MOONRAKER) "192.168.1.50 or mainsail.local" else "192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (type != HostType.FLASHFORGE) {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text(if (type == HostType.MOONRAKER) "API key (optional)" else "API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(
                when (type) {
                    HostType.OCTOPRINT -> "Find the API key in OctoPrint under Settings → Application Keys."
                    HostType.MOONRAKER -> "Mainsail/Fluidd: the key is only needed if Moonraker authorization is enabled."
                    HostType.PRUSALINK -> "Find the API key on the printer's display or in PrusaLink settings."
                    HostType.FLASHFORGE -> "No password is needed. Files are saved to the printer's internal storage."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { vm.testPrintHost(host) }, enabled = host.isConfigured) { Text("Test connection") }
                Button(onClick = { vm.savePrintHost(host); vm.navigate(Screen.PLATE) }, enabled = host.isConfigured) { Text("Save") }
            }
        }
    }
}
