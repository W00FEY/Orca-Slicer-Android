package io.github.w00fey.orcaslicer.data

import android.content.Context
import io.github.w00fey.orcaslicer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipInputStream

data class VendorInfo(val id: String, val name: String, val modelCount: Int)

/**
 * Manages the on-device copy of OrcaSlicer's resources directory and the
 * "system" profile folder libslic3r loads presets from.
 *
 * The desktop app only installs profiles for vendors chosen in its setup
 * wizard; we do the same, which keeps start-up fast on a phone.
 */
class ResourceInstaller(context: Context) {
    private val appContext = context.applicationContext
    val resourcesDir = File(appContext.filesDir, "orca/resources")
    val dataDir = File(appContext.filesDir, "orca/data")
    val tempDir = File(appContext.cacheDir, "orca-tmp")
    private val profilesDir get() = File(resourcesDir, "profiles")
    private val systemDir get() = File(dataDir, "system")
    private val marker get() = File(resourcesDir, ".installed-version")

    suspend fun ensureExtracted() = withContext(Dispatchers.IO) {
        val stamp = "${BuildConfig.VERSION_CODE}:${BuildConfig.VERSION_NAME}"
        if (marker.isFile && marker.readText() == stamp) return@withContext
        resourcesDir.deleteRecursively()
        resourcesDir.mkdirs()
        val root = resourcesDir.canonicalPath + File.separator
        appContext.assets.open(ASSET_NAME).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(resourcesDir, entry.name)
                    // Reject entries that would escape the target directory.
                    if (!out.canonicalPath.startsWith(root)) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        // Installed vendor profiles may be stale after an app update; refresh them.
        val installed = installedVendorIds()
        if (installed.isNotEmpty()) installVendors(installed)
        marker.writeText(stamp)
    }

    fun availableVendors(): List<VendorInfo> {
        val files = profilesDir.listFiles { f -> f.isFile && f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { f ->
            val id = f.nameWithoutExtension
            if (id == FILAMENT_LIBRARY) return@mapNotNull null
            runCatching {
                val obj = json.parseToJsonElement(f.readText()).jsonObject
                val name = obj["name"]?.jsonPrimitive?.content ?: id
                val models = obj["machine_model_list"]?.jsonArray?.size ?: 0
                VendorInfo(id, name, models)
            }.getOrNull()
        }.filter { it.modelCount > 0 }.sortedBy { it.name.lowercase() }
    }

    fun installedVendorIds(): Set<String> =
        systemDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.filter { it != FILAMENT_LIBRARY }
            ?.toSet() ?: emptySet()

    /** Makes exactly [vendorIds] (plus the shared filament library) installed. */
    suspend fun installVendors(vendorIds: Set<String>) = withContext(Dispatchers.IO) {
        systemDir.mkdirs()
        val wanted = vendorIds + FILAMENT_LIBRARY
        systemDir.listFiles()?.forEach { f ->
            val id = if (f.isFile) f.nameWithoutExtension else f.name
            if (id !in wanted) f.deleteRecursively()
        }
        for (id in wanted) {
            val srcJson = File(profilesDir, "$id.json")
            if (!srcJson.isFile) continue
            srcJson.copyTo(File(systemDir, "$id.json"), overwrite = true)
            val srcDir = File(profilesDir, id)
            val dstDir = File(systemDir, id)
            dstDir.deleteRecursively()
            if (srcDir.isDirectory) srcDir.copyRecursively(dstDir, overwrite = true)
        }
    }

    companion object {
        const val ASSET_NAME = "orca-resources.zip"
        const val FILAMENT_LIBRARY = "OrcaFilamentLibrary"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
