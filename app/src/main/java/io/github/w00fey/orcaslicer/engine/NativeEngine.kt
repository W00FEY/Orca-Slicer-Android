package io.github.w00fey.orcaslicer.engine

/**
 * Raw JNI bindings to libslic3r (native/engine/jni/OrcaEngine.cpp).
 *
 * Most calls return a JSON object with an `ok` flag and either the payload or an
 * `error` message. Calls block, so use [OrcaEngine] which runs them off the main thread.
 */
internal object NativeEngine {
    init {
        System.loadLibrary("orca_jni")
    }

    @JvmStatic external fun nativeInit(resourcesDir: String, dataDir: String, tempDir: String): String
    @JvmStatic external fun nativeReloadPresets(): String

    @JvmStatic external fun nativeListPrinters(): String
    @JvmStatic external fun nativeSelectPresets(printer: String, filament: String, process: String): String
    @JvmStatic external fun nativeListOptionKeys(): String
    @JvmStatic external fun nativeGetOptions(keysJson: String): String
    @JvmStatic external fun nativeSetOverrides(valuesJson: String): String
    @JvmStatic external fun nativeClearOverrides(): String

    @JvmStatic external fun nativeLoadModel(path: String): String
    @JvmStatic external fun nativeGetObjects(): String
    @JvmStatic external fun nativeGetObjectMesh(objectIndex: Int): FloatArray?
    @JvmStatic external fun nativeSetInstanceTransform(
        objectIndex: Int, instanceIndex: Int, x: Double, y: Double, rotationZDeg: Double, scale: Double,
    ): String
    @JvmStatic external fun nativeRotateInstance(objectIndex: Int, instanceIndex: Int, axis: Int, degrees: Double): String
    @JvmStatic external fun nativeAddInstance(objectIndex: Int): String
    @JvmStatic external fun nativeRemoveObject(objectIndex: Int): String
    @JvmStatic external fun nativeClearModel(): String
    @JvmStatic external fun nativeArrange(): String

    @JvmStatic external fun nativeSlice(outputPath: String): String
    @JvmStatic external fun nativeCancel()
    @JvmStatic external fun nativeGetProgress(): String
    @JvmStatic external fun nativeGetToolpaths(): FloatArray?
}
