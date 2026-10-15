package io.github.w00fey.orcaslicer.engine;

/** Host-side mirror of the app's NativeEngine.kt, binding the same JNI symbols. */
final class NativeEngine {
    static native String nativeInit(String resourcesDir, String dataDir, String tempDir);
    static native String nativeReloadPresets();
    static native String nativeListPrinters();
    static native String nativeSelectPresets(String printer, String filament, String process);
    static native String nativeListOptionKeys();
    static native String nativeGetOptions(String keysJson);
    static native String nativeSetOverrides(String valuesJson);
    static native String nativeClearOverrides();
    static native String nativeLoadModel(String path);
    static native String nativeGetObjects();
    static native float[] nativeGetObjectMesh(int objectIndex);
    static native String nativeSetInstanceTransform(int objectIndex, int instanceIndex, double x, double y, double rotationZDeg, double scale);
    static native String nativeRotateInstance(int objectIndex, int instanceIndex, int axis, double degrees);
    static native String nativeAddInstance(int objectIndex);
    static native String nativeRemoveObject(int objectIndex);
    static native String nativeClearModel();
    static native String nativeArrange();
    static native String nativeSlice(String outputPath);
    static native void nativeCancel();
    static native String nativeGetProgress();
    static native float[] nativeGetToolpaths();
}
