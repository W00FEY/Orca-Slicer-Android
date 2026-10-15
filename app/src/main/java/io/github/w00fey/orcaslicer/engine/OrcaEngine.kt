package io.github.w00fey.orcaslicer.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Coroutine-friendly facade over [NativeEngine]. Engine calls are confined to a
 * single background thread because libslic3r's model/preset state is not thread safe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object OrcaEngine {
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun parse(raw: String): JsonObject {
        val obj = json.parseToJsonElement(raw).jsonObject
        if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw EngineException(obj["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown engine error")
        }
        return obj
    }

    private inline fun <reified T> JsonObject.field(name: String): T =
        json.decodeFromJsonElement(this[name] ?: throw EngineException("Missing '$name' in engine reply"))

    private suspend fun <T> call(block: () -> T): T = withContext(engineDispatcher) { block() }

    suspend fun init(resourcesDir: File, dataDir: File, tempDir: File): String = call {
        tempDir.mkdirs()
        parse(NativeEngine.nativeInit(resourcesDir.absolutePath, dataDir.absolutePath, tempDir.absolutePath))
            .field<String>("version")
    }

    suspend fun reloadPresets() = call { parse(NativeEngine.nativeReloadPresets()) }

    suspend fun listPrinters(): Pair<List<PrinterPreset>, Selection> = call {
        val obj = parse(NativeEngine.nativeListPrinters())
        obj.field<List<PrinterPreset>>("printers") to obj.field<Selection>("selection")
    }

    suspend fun selectPresets(printer: String = "", filament: String = "", process: String = ""): Selection = call {
        parse(NativeEngine.nativeSelectPresets(printer, filament, process)).field("selection")
    }

    suspend fun listOptionKeys(): OptionKeys = call {
        json.decodeFromJsonElement(parse(NativeEngine.nativeListOptionKeys()))
    }

    suspend fun getOptions(keys: List<String>): List<OptionDef> = call {
        val arg = JsonArray(keys.map { JsonPrimitive(it) }).toString()
        parse(NativeEngine.nativeGetOptions(arg)).field("options")
    }

    /** Sets (or with a null value, clears) per-session overrides. Returns per-key errors. */
    suspend fun setOverrides(values: Map<String, String?>): Pair<Map<String, String>, Selection> = call {
        val arg = JsonObject(values.mapValues { (_, v) -> if (v == null) JsonNull else JsonPrimitive(v) })
        val obj = parse(NativeEngine.nativeSetOverrides(arg.toString()))
        val errors = obj["errors"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
        errors to obj.field<Selection>("selection")
    }

    suspend fun clearOverrides() = call { parse(NativeEngine.nativeClearOverrides()) }

    private fun objects(raw: String): List<ModelObjectInfo> = parse(raw).field("objects")

    suspend fun loadModel(file: File): List<ModelObjectInfo> = call { objects(NativeEngine.nativeLoadModel(file.absolutePath)) }
    suspend fun getObjects(): List<ModelObjectInfo> = call { objects(NativeEngine.nativeGetObjects()) }
    suspend fun getObjectMesh(index: Int): FloatArray? = call { NativeEngine.nativeGetObjectMesh(index) }

    suspend fun setInstanceTransform(objectIndex: Int, instanceIndex: Int, x: Double, y: Double, rotationZDeg: Double, scale: Double) =
        call { objects(NativeEngine.nativeSetInstanceTransform(objectIndex, instanceIndex, x, y, rotationZDeg, scale)) }

    suspend fun rotateInstance(objectIndex: Int, instanceIndex: Int, axis: Int, degrees: Double) =
        call { objects(NativeEngine.nativeRotateInstance(objectIndex, instanceIndex, axis, degrees)) }

    suspend fun addInstance(objectIndex: Int) = call { objects(NativeEngine.nativeAddInstance(objectIndex)) }
    suspend fun removeObject(objectIndex: Int) = call { objects(NativeEngine.nativeRemoveObject(objectIndex)) }
    suspend fun clearModel() = call { objects(NativeEngine.nativeClearModel()) }
    suspend fun arrange() = call { objects(NativeEngine.nativeArrange()) }

    /** Slices the current plate. Poll [progress] from another coroutine meanwhile. */
    suspend fun slice(output: File): SliceResult = call {
        json.decodeFromJsonElement(parse(NativeEngine.nativeSlice(output.absolutePath)))
    }

    fun cancel() = NativeEngine.nativeCancel()

    fun progress(): SliceProgress = json.decodeFromString(NativeEngine.nativeGetProgress())

    suspend fun toolpaths(): FloatArray = call { NativeEngine.nativeGetToolpaths() ?: FloatArray(0) }
}
