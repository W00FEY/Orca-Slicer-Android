package io.github.w00fey.orcaslicer.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.w00fey.orcaslicer.data.AppPrefs
import io.github.w00fey.orcaslicer.data.PrintHost
import io.github.w00fey.orcaslicer.data.PrintHostUploader
import io.github.w00fey.orcaslicer.data.ResourceInstaller
import io.github.w00fey.orcaslicer.data.VendorInfo
import io.github.w00fey.orcaslicer.engine.ModelObjectInfo
import io.github.w00fey.orcaslicer.engine.OptionDef
import io.github.w00fey.orcaslicer.engine.OptionKeys
import io.github.w00fey.orcaslicer.engine.OrcaEngine
import io.github.w00fey.orcaslicer.engine.PrinterPreset
import io.github.w00fey.orcaslicer.engine.Selection
import io.github.w00fey.orcaslicer.engine.SliceProgress
import io.github.w00fey.orcaslicer.engine.SliceResult
import io.github.w00fey.orcaslicer.render.PlateRenderer
import io.github.w00fey.orcaslicer.render.SceneInstance
import io.github.w00fey.orcaslicer.render.Toolpaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen { LOADING, VENDORS, PRINTERS, PLATE, PREVIEW, SETTINGS, PRINT_HOST, ERROR }

data class UiState(
    val screen: Screen = Screen.LOADING,
    val loadingText: String = "Starting…",
    val fatalError: String? = null,
    val engineVersion: String = "",
    val vendors: List<VendorInfo> = emptyList(),
    val installedVendors: Set<String> = emptySet(),
    val printers: List<PrinterPreset> = emptyList(),
    val selection: Selection = Selection(),
    val objects: List<ModelObjectInfo> = emptyList(),
    val selected: Pair<Int, Int>? = null,
    val busy: String? = null,
    val slicing: SliceProgress? = null,
    val sliceResult: SliceResult? = null,
    /** The plate changed since [sliceResult] was produced. */
    val sliceStale: Boolean = true,
    val layerCount: Int = 0,
    val visibleLayers: Int = 0,
    val overrides: Map<String, String> = emptyMap(),
    val printHost: PrintHost = PrintHost(),
    val uploadProgress: Float? = null,
    val message: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val installer = ResourceInstaller(app)
    private val prefs = AppPrefs(app)
    val renderer = PlateRenderer()

    private val _state = MutableStateFlow(UiState(printHost = prefs.printHost))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var engineReady = false
    private var sliceJob: Job? = null
    private var optionKeys: OptionKeys? = null
    private var pendingImports: List<Uri> = emptyList()

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        try {
            setLoading("Unpacking printer profiles…")
            installer.ensureExtracted()
            val installed = installer.installedVendorIds()
            _state.update { it.copy(vendors = installer.availableVendors(), installedVendors = installed) }
            if (installed.isEmpty()) {
                _state.update { it.copy(screen = Screen.VENDORS) }
                return
            }
            startEngine()
        } catch (e: Throwable) {
            fail(e)
        }
    }

    private suspend fun startEngine() {
        setLoading("Loading printer profiles…")
        if (!engineReady) {
            val version = OrcaEngine.init(installer.resourcesDir, installer.dataDir, installer.tempDir)
            engineReady = true
            _state.update { it.copy(engineVersion = version) }
        } else {
            OrcaEngine.reloadPresets()
        }
        var selection = OrcaEngine.selectPresets(prefs.printer, prefs.filament, prefs.process)
        val saved = prefs.overrides
        if (saved.isNotEmpty()) {
            val (errors, sel) = OrcaEngine.setOverrides(saved)
            selection = sel
            if (errors.isNotEmpty()) prefs.overrides = saved - errors.keys
        }
        val (printers, _) = OrcaEngine.listPrinters()
        _state.update {
            it.copy(
                printers = printers, selection = selection, overrides = prefs.overrides,
                screen = if (prefs.printer.isEmpty() || printers.none { p -> p.name == prefs.printer }) Screen.PRINTERS else Screen.PLATE,
            )
        }
        applyBed(selection)
        refreshScene(reloadMeshes = true)
        if (pendingImports.isNotEmpty()) {
            val uris = pendingImports
            pendingImports = emptyList()
            importModels(uris)
        }
    }

    private fun setLoading(text: String) = _state.update { it.copy(screen = Screen.LOADING, loadingText = text) }

    private fun fail(e: Throwable) {
        _state.update { it.copy(screen = Screen.ERROR, fatalError = e.message ?: e.toString()) }
    }

    fun retry() {
        _state.update { it.copy(fatalError = null) }
        viewModelScope.launch { start() }
    }

    fun showMessage(text: String) = _state.update { it.copy(message = text) }
    fun messageShown() = _state.update { it.copy(message = null) }

    fun navigate(screen: Screen) {
        renderer.previewMode = screen == Screen.PREVIEW
        _state.update { it.copy(screen = screen) }
    }

    /** Runs an engine action with a busy indicator and error reporting. */
    private fun action(label: String?, block: suspend () -> Unit) {
        viewModelScope.launch {
            if (label != null) _state.update { it.copy(busy = label) }
            try {
                block()
            } catch (e: Throwable) {
                showMessage(e.message ?: e.toString())
            } finally {
                if (label != null) _state.update { it.copy(busy = null) }
            }
        }
    }

    // ------------------------------------------------------------ vendors & presets

    fun saveVendors(ids: Set<String>) {
        if (ids.isEmpty()) {
            showMessage("Pick at least one printer brand")
            return
        }
        viewModelScope.launch {
            try {
                setLoading("Installing profiles…")
                installer.installVendors(ids)
                _state.update { it.copy(installedVendors = ids) }
                startEngine()
                _state.update { it.copy(screen = Screen.PRINTERS) }
            } catch (e: Throwable) {
                fail(e)
            }
        }
    }

    fun selectPrinter(name: String) = action("Loading printer…") {
        val selection = OrcaEngine.selectPresets(printer = name)
        savePresets(selection)
        _state.update { it.copy(selection = selection, screen = Screen.PLATE) }
        applyBed(selection)
        OrcaEngine.arrange().let { setObjects(it, reloadMeshes = false) }
        markStale()
    }

    fun selectFilament(name: String) = action(null) {
        val selection = OrcaEngine.selectPresets(filament = name)
        savePresets(selection)
        _state.update { it.copy(selection = selection) }
        markStale()
    }

    fun selectProcess(name: String) = action(null) {
        val selection = OrcaEngine.selectPresets(process = name)
        savePresets(selection)
        _state.update { it.copy(selection = selection) }
        markStale()
    }

    private fun savePresets(selection: Selection) {
        prefs.printer = selection.printer
        prefs.filament = selection.filament
        prefs.process = selection.process
    }

    private fun applyBed(selection: Selection) {
        val bed = selection.bed
        renderer.setBed(bed.shape.map { it[0].toFloat() to it[1].toFloat() })
        bedBounds = floatArrayOf(bed.minX.toFloat(), bed.minY.toFloat(), bed.maxX.toFloat(), bed.maxY.toFloat(), bed.maxHeight.toFloat())
        renderer.camera.frame(bedBounds[0], bedBounds[1], bedBounds[2], bedBounds[3], bedBounds[4])
    }

    var bedBounds = floatArrayOf(0f, 0f, 220f, 220f, 250f)
        private set

    // ------------------------------------------------------------ settings

    suspend fun optionKeys(): OptionKeys = optionKeys ?: OrcaEngine.listOptionKeys().also { optionKeys = it }

    suspend fun loadOptions(keys: List<String>): List<OptionDef> =
        try {
            OrcaEngine.getOptions(keys)
        } catch (e: Throwable) {
            showMessage(e.message ?: "Could not load settings")
            emptyList()
        }

    /** Applies one setting override (null resets it to the preset value). Returns an error message, if any. */
    suspend fun setOption(key: String, value: String?): String? {
        return try {
            val (errors, selection) = OrcaEngine.setOverrides(mapOf(key to value))
            val error = errors[key]
            if (error == null) {
                val updated = if (value == null) prefs.overrides - key else prefs.overrides + (key to value)
                prefs.overrides = updated
                _state.update { it.copy(overrides = updated, selection = selection) }
                markStale()
                if (key == "printable_area") applyBed(selection)
            }
            error
        } catch (e: Throwable) {
            e.message
        }
    }

    fun resetAllOptions() = action(null) {
        OrcaEngine.clearOverrides()
        prefs.overrides = emptyMap()
        val selection = OrcaEngine.selectPresets()
        _state.update { it.copy(overrides = emptyMap(), selection = selection) }
        markStale()
    }

    // ------------------------------------------------------------ model

    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (!engineReady) {
            pendingImports = pendingImports + uris
            return
        }
        action("Importing…") {
            for (uri in uris) {
                val file = copyToCache(uri)
                try {
                    setObjects(OrcaEngine.loadModel(file), reloadMeshes = true)
                } finally {
                    file.delete()
                }
            }
            markStale()
        }
    }

    /** Sample models bundled with OrcaSlicer (resources/handy_models). */
    fun sampleModels(): List<String> =
        File(installer.resourcesDir, "handy_models").listFiles()?.map { it.name }?.sorted().orEmpty()

    fun loadSample(name: String) = action("Importing…") {
        setObjects(OrcaEngine.loadModel(File(installer.resourcesDir, "handy_models/$name")), reloadMeshes = true)
        markStale()
    }

    private suspend fun copyToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "model.stl"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        require(ext in SUPPORTED_EXTENSIONS) {
            "Unsupported file type '.$ext'. Supported: ${SUPPORTED_EXTENSIONS.joinToString { ".$it" }}"
        }
        val dir = File(getApplication<Application>().cacheDir, "imports").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val out = File(dir, safeName)
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: error("Could not open $name")
        out
    }

    private suspend fun setObjects(objects: List<ModelObjectInfo>, reloadMeshes: Boolean) {
        _state.update { s ->
            val sel = s.selected?.takeIf { (o, i) -> o < objects.size && i < objects[o].instances.size }
            s.copy(objects = objects, selected = sel)
        }
        renderer.selected = _state.value.selected
        if (reloadMeshes) {
            val meshes = HashMap<Int, FloatArray>()
            for (i in objects.indices) OrcaEngine.getObjectMesh(i)?.let { meshes[i] = it }
            renderer.setMeshes(meshes)
        }
        renderer.setInstances(objects.flatMapIndexed { oi, obj ->
            obj.instances.mapIndexed { ii, inst ->
                SceneInstance(
                    oi, ii, PlateRenderer.matrixFrom(inst.matrix),
                    inst.bboxMin.map { it.toFloat() }.toFloatArray(),
                    inst.bboxMax.map { it.toFloat() }.toFloatArray(),
                )
            }
        })
        renderer.dragOffset = floatArrayOf(0f, 0f)
        requestRender()
    }

    private suspend fun refreshScene(reloadMeshes: Boolean) = setObjects(OrcaEngine.getObjects(), reloadMeshes)

    /** Set by the plate view so model changes trigger a redraw. */
    var requestRender: () -> Unit = {}

    fun select(sel: Pair<Int, Int>?) {
        renderer.selected = sel
        _state.update { it.copy(selected = sel) }
        requestRender()
    }

    fun moveBy(obj: Int, inst: Int, dx: Float, dy: Float) = action(null) {
        val info = _state.value.objects.getOrNull(obj)?.instances?.getOrNull(inst) ?: return@action
        setObjects(
            OrcaEngine.setInstanceTransform(obj, inst, info.offset[0] + dx, info.offset[1] + dy, info.rotationDeg[2], info.scale[0]),
            reloadMeshes = false,
        )
        markStale()
    }

    fun setTransform(obj: Int, inst: Int, x: Double, y: Double, rotZ: Double, scale: Double) = action(null) {
        setObjects(OrcaEngine.setInstanceTransform(obj, inst, x, y, rotZ, scale), reloadMeshes = false)
        markStale()
    }

    fun rotate(obj: Int, inst: Int, axis: Int, degrees: Double) = action(null) {
        setObjects(OrcaEngine.rotateInstance(obj, inst, axis, degrees), reloadMeshes = false)
        markStale()
    }

    fun duplicate(obj: Int) = action("Arranging…") {
        setObjects(OrcaEngine.addInstance(obj), reloadMeshes = false)
        markStale()
    }

    fun remove(obj: Int) = action(null) {
        select(null)
        setObjects(OrcaEngine.removeObject(obj), reloadMeshes = true)
        markStale()
    }

    fun clearPlate() = action(null) {
        select(null)
        setObjects(OrcaEngine.clearModel(), reloadMeshes = true)
        renderer.setToolpaths(null)
        _state.update { it.copy(sliceResult = null) }
        markStale()
    }

    fun arrange() = action("Arranging…") {
        setObjects(OrcaEngine.arrange(), reloadMeshes = false)
        markStale()
    }

    private fun markStale() = _state.update { it.copy(sliceStale = true) }

    // ------------------------------------------------------------ slicing

    fun slice() {
        if (sliceJob?.isActive == true) return
        if (_state.value.objects.isEmpty()) {
            showMessage("Import a model first")
            return
        }
        sliceJob = viewModelScope.launch {
            _state.update { it.copy(slicing = SliceProgress(0, "Preparing")) }
            val poller = launch {
                while (isActive) {
                    delay(250)
                    _state.update { s -> if (s.slicing != null) s.copy(slicing = OrcaEngine.progress()) else s }
                }
            }
            try {
                val dir = File(getApplication<Application>().filesDir, "gcode").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val name = (_state.value.objects.firstOrNull()?.name ?: "plate").substringBeforeLast('.')
                val result = OrcaEngine.slice(File(dir, "${sanitize(name)}.gcode"))
                _state.update { it.copy(slicing = SliceProgress(100, "Building preview")) }
                val paths = withContext(Dispatchers.Default) { Toolpaths.fromSegments(OrcaEngine.toolpaths()) }
                renderer.setToolpaths(paths)
                val layers = paths.layerEnds.size
                renderer.visibleLayers = layers
                _state.update {
                    it.copy(sliceResult = result, sliceStale = false, layerCount = layers, visibleLayers = layers)
                }
                navigate(Screen.PREVIEW)
                result.warnings.firstOrNull()?.let { showMessage(it) }
            } catch (e: Throwable) {
                showMessage(e.message ?: "Slicing failed")
            } finally {
                poller.cancel()
                _state.update { it.copy(slicing = null) }
            }
        }
    }

    fun cancelSlice() = OrcaEngine.cancel()

    fun setVisibleLayers(n: Int) {
        renderer.visibleLayers = n
        _state.update { it.copy(visibleLayers = n) }
        requestRender()
    }

    fun gcodeFile(): File? = _state.value.sliceResult?.gcodePath?.let(::File)?.takeIf { it.isFile }

    fun exportGcode(target: Uri) = action("Saving…") {
        val src = gcodeFile() ?: error("Nothing to save. Slice first.")
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("Could not write file")
        }
        showMessage("G-code saved")
    }

    // ------------------------------------------------------------ print host

    fun savePrintHost(host: PrintHost) {
        prefs.printHost = host
        _state.update { it.copy(printHost = host) }
    }

    fun testPrintHost(host: PrintHost) = action("Connecting…") {
        PrintHostUploader.test(host)
        showMessage("Connected to ${host.type.label}")
    }

    fun sendToPrinter(startPrint: Boolean) {
        val host = _state.value.printHost
        val file = gcodeFile()
        if (!host.isConfigured) {
            navigate(Screen.PRINT_HOST)
            return
        }
        if (file == null) {
            showMessage("Slice first")
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(uploadProgress = 0f) }
            try {
                PrintHostUploader.upload(host, file, file.name, startPrint) { p ->
                    _state.update { it.copy(uploadProgress = p) }
                }
                showMessage(if (startPrint) "Uploaded, print started" else "Uploaded to printer")
            } catch (e: Throwable) {
                showMessage("Upload failed: ${e.message}")
            } finally {
                _state.update { it.copy(uploadProgress = null) }
            }
        }
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60).ifEmpty { "plate" }

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("stl", "3mf", "obj", "step", "stp", "amf", "drc")
    }
}
