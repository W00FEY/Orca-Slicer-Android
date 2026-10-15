package io.github.w00fey.orcaslicer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OrcaApp(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    // Secondary screens return to the plate on back.
    BackHandler(enabled = state.screen in setOf(Screen.SETTINGS, Screen.PRINT_HOST, Screen.PREVIEW) ||
        (state.screen in setOf(Screen.PRINTERS, Screen.VENDORS) && state.selection.printer.isNotEmpty())) {
        vm.navigate(Screen.PLATE)
    }

    // No Scaffold: the 3D view draws edge-to-edge and each screen handles its own insets.
    Surface(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when (state.screen) {
                Screen.LOADING -> CenteredMessage(state.loadingText, progress = true)
                Screen.ERROR -> ErrorScreen(state.fatalError ?: "Unknown error", onRetry = vm::retry)
                Screen.VENDORS -> VendorScreen(state, vm)
                Screen.PRINTERS -> PrinterScreen(state, vm)
                Screen.PLATE, Screen.PREVIEW -> WorkspaceScreen(state, vm)
                Screen.SETTINGS -> SettingsScreen(state, vm)
                Screen.PRINT_HOST -> PrintHostScreen(state, vm)
            }
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
            )
        }
    }
}

@Composable
fun CenteredMessage(text: String, progress: Boolean = false) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (progress) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ErrorScreen(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}
