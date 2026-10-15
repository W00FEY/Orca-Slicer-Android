package io.github.w00fey.orcaslicer.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end check of the JNI bridge on the host: load system profiles, pick a
 * printer, import a model, transform/arrange it and slice to G-code.
 *
 * Usage: EngineSmokeTest <libdir> <orca resources dir> <work dir> [printer name]
 */
public final class EngineSmokeTest {
    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        System.load(Paths.get(args[0], "liborca_jni.so").toAbsolutePath().toString());
        Path resources = Paths.get(args[1]).toAbsolutePath();
        Path work = Paths.get(args[2]).toAbsolutePath();
        String printer = args.length > 3 ? args[3] : "Prusa MK4 0.4 nozzle";

        String init = NativeEngine.nativeInit(resources.toString(), work.resolve("data").toString(), work.resolve("tmp").toString());
        expectOk("init", init);

        String printers = NativeEngine.nativeListPrinters();
        expectOk("listPrinters", printers);
        check("printer list contains " + printer, printers.contains("\"" + printer + "\""));

        String sel = NativeEngine.nativeSelectPresets(printer, "", "");
        expectOk("selectPrinter", sel);
        check("printer selected", sel.contains("\"printer\":\"" + printer + "\""));
        check("has compatible filaments", !sel.contains("\"filaments\":[]"));
        check("has compatible processes", !sel.contains("\"processes\":[]"));
        System.out.println("selection: " + abbreviate(sel));

        expectOk("optionKeys", NativeEngine.nativeListOptionKeys());
        String opts = NativeEngine.nativeGetOptions("[\"layer_height\",\"sparse_infill_density\",\"enable_support\",\"no_such_option\"]");
        expectOk("getOptions", opts);
        check("layer_height option present", opts.contains("\"key\":\"layer_height\""));
        System.out.println("options: " + abbreviate(opts));

        String ov = NativeEngine.nativeSetOverrides("{\"layer_height\":\"0.28\",\"sparse_infill_density\":\"25%\",\"wall_loops\":\"bad\"}");
        expectOk("setOverrides", ov);
        check("invalid override reported", ov.contains("\"wall_loops\""));
        check("valid overrides accepted", !ov.contains("\"layer_height\":\""));

        Path stl = work.resolve("cube.stl");
        writeCube(stl, 20f);
        String objs = NativeEngine.nativeLoadModel(stl.toString());
        expectOk("loadModel", objs);
        check("one object loaded", objs.contains("\"name\":\"cube.stl\""));

        float[] mesh = NativeEngine.nativeGetObjectMesh(0);
        check("mesh has 12 triangles", mesh != null && mesh.length == 12 * 3 * 6);

        expectOk("addInstance", NativeEngine.nativeAddInstance(0));
        expectOk("rotate", NativeEngine.nativeRotateInstance(0, 1, 2, 45));
        expectOk("transform", NativeEngine.nativeSetInstanceTransform(0, 0, 60, 60, 0, 1.5));
        expectOk("arrange", NativeEngine.nativeArrange());
        String after = NativeEngine.nativeGetObjects();
        check("two instances after copy", count(after, "\"matrix\"") == 2);
        System.out.println("objects: " + abbreviate(after));

        Path gcode = work.resolve("out.gcode");
        long t0 = System.currentTimeMillis();
        String result = NativeEngine.nativeSlice(gcode.toString());
        System.out.println("slice (" + (System.currentTimeMillis() - t0) + " ms): " + abbreviate(result));
        expectOk("slice", result);
        Matcher m = Pattern.compile("\"gcode_path\":\"([^\"]+)\"").matcher(result);
        if (m.find()) {
            Path out = Paths.get(m.group(1));
            String text = Files.readString(out);
            check("G-code written", text.length() > 10000);
            check("G-code has layer height 0.28", text.contains("; layer_height = 0.28"));
            check("G-code has 25% infill", text.contains("; sparse_infill_density = 25%"));
            check("G-code has extrusion moves", text.contains("G1 X"));
        } else {
            check("gcode_path reported", false);
        }
        float[] paths = NativeEngine.nativeGetToolpaths();
        check("toolpaths returned", paths != null && paths.length > 8 * 100 && paths.length % 8 == 0);
        System.out.println("toolpath segments: " + (paths == null ? 0 : paths.length / 8));

        expectOk("removeObject", NativeEngine.nativeRemoveObject(0));
        expectOk("clear", NativeEngine.nativeClearModel());
        String empty = NativeEngine.nativeSlice(gcode.toString());
        check("slicing an empty plate fails cleanly", empty.contains("\"ok\":false"));

        if (failures > 0) {
            System.out.println("FAILED: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("ALL CHECKS PASSED");
    }

    private static void expectOk(String what, String json) {
        check(what + " ok", json != null && json.contains("\"ok\":true"));
        if (json == null || !json.contains("\"ok\":true")) System.out.println("  reply: " + abbreviate(json));
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    private static int count(String s, String needle) {
        int n = 0;
        for (int i = s.indexOf(needle); i >= 0; i = s.indexOf(needle, i + 1)) n++;
        return n;
    }

    private static String abbreviate(String s) {
        return s == null ? "null" : s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    /** Writes a binary STL cube with the given edge length. */
    private static void writeCube(Path path, float a) throws IOException {
        float[][] v = {{0,0,0},{a,0,0},{a,a,0},{0,a,0},{0,0,a},{a,0,a},{a,a,a},{0,a,a}};
        int[][] f = {{0,2,1},{0,3,2},{4,5,6},{4,6,7},{0,1,5},{0,5,4},{1,2,6},{1,6,5},{2,3,7},{2,7,6},{3,0,4},{3,4,7}};
        ByteBuffer b = ByteBuffer.allocate(84 + f.length * 50).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[80]);
        b.putInt(f.length);
        for (int[] t : f) {
            b.putFloat(0).putFloat(0).putFloat(0);
            for (int i : t) b.putFloat(v[i][0]).putFloat(v[i][1]).putFloat(v[i][2]);
            b.putShort((short) 0);
        }
        Files.write(path, b.array());
    }
}
