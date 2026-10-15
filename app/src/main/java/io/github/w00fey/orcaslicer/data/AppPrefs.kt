package io.github.w00fey.orcaslicer.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class HostType(val label: String) {
    OCTOPRINT("OctoPrint"),
    MOONRAKER("Klipper (Moonraker)"),
    PRUSALINK("PrusaLink"),
}

@Serializable
data class PrintHost(
    val type: HostType = HostType.MOONRAKER,
    val url: String = "",
    val apiKey: String = "",
) {
    val isConfigured get() = url.isNotBlank()
}

/** Small persisted settings: selected presets, setting overrides and print host. */
class AppPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("orca", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var printer: String
        get() = prefs.getString("printer", "") ?: ""
        set(value) = prefs.edit().putString("printer", value).apply()

    var filament: String
        get() = prefs.getString("filament", "") ?: ""
        set(value) = prefs.edit().putString("filament", value).apply()

    var process: String
        get() = prefs.getString("process", "") ?: ""
        set(value) = prefs.edit().putString("process", value).apply()

    var overrides: Map<String, String>
        get() = prefs.getString("overrides", null)?.let {
            runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull()
        } ?: emptyMap()
        set(value) = prefs.edit().putString("overrides", json.encodeToString(value)).apply()

    var printHost: PrintHost
        get() = prefs.getString("print_host", null)?.let {
            runCatching { json.decodeFromString<PrintHost>(it) }.getOrNull()
        } ?: PrintHost()
        set(value) = prefs.edit().putString("print_host", json.encodeToString(value)).apply()
}
