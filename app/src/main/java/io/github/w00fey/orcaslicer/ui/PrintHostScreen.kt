package io.github.w00fey.orcaslicer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.w00fey.orcaslicer.data.HostType
import io.github.w00fey.orcaslicer.data.PrintHost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintHostScreen(state: UiState, vm: AppViewModel) {
    var type by rememberSaveable { mutableStateOf(state.printHost.type) }
    var url by rememberSaveable { mutableStateOf(state.printHost.url) }
    var key by rememberSaveable { mutableStateOf(state.printHost.apiKey) }
    val host = PrintHost(type, url.trim(), key.trim())

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
                "Send sliced G-code straight to a printer on your network. Bambu Lab printers use a proprietary " +
                    "protocol and are not supported here; save the G-code and use Bambu Handy instead.",
                style = MaterialTheme.typography.bodyMedium,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                HostType.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = type == t,
                        onClick = { type = t },
                        shape = SegmentedButtonDefaults.itemShape(i, HostType.entries.size),
                    ) { Text(t.label.substringBefore(" (")) }
                }
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Address") },
                placeholder = { Text(if (type == HostType.MOONRAKER) "192.168.1.50 or mainsail.local" else "192.168.1.50") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text(if (type == HostType.MOONRAKER) "API key (optional)" else "API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                when (type) {
                    HostType.OCTOPRINT -> "Find the API key in OctoPrint under Settings → Application Keys."
                    HostType.MOONRAKER -> "Mainsail/Fluidd: the key is only needed if Moonraker authorization is enabled."
                    HostType.PRUSALINK -> "Find the API key on the printer's display or in PrusaLink settings."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { vm.testPrintHost(host) }, enabled = host.isConfigured) { Text("Test connection") }
                Button(onClick = { vm.savePrintHost(host); vm.navigate(Screen.PLATE) }) { Text("Save") }
            }
        }
    }
}
