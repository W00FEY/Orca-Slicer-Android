# Orca Slicer for Android

An unofficial Android port of [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer). It runs
OrcaSlicer's slicing engine on the phone, so the G-code is the same as the desktop app makes
with the same profiles. The touch UI is new and built for phones like the Pixel 9 Pro XL.

> Status: early. The engine, profiles and slicing pipeline come from OrcaSlicer itself; the
> Android UI covers the everyday workflow, not every desktop feature (see [Limitations](#limitations)).

## What it does

- **Printer profiles**: the same printer, filament and process presets as OrcaSlicer
  (Bambu Lab, Prusa, Creality, Voron, Elegoo, Anycubic, Qidi, Sovol and about 60 more brands).
  On first launch you pick your brands, then your printer.
- **Import** STL, 3MF, OBJ, STEP and AMF files from the file picker, or open or share them from
  other apps (Files, Drive, browser downloads, Printables/Thingiverse).
- **Arrange the plate**: 3D view with orbit, zoom and pan. Tap to select a model, long-press and
  drag to move it. Type exact position, rotation and scale, rotate 90° about X, Y or Z, add
  copies, auto-arrange.
- **Settings**: the common settings in tabs (quality, strength, speed, support, adhesion,
  filament, printer), plus a searchable list of every OrcaSlicer setting with its tooltip.
  Changes are kept on top of the selected presets and can be reset.
- **Slice** with progress and cancel, then **preview** the toolpaths coloured by feature with a
  layer slider, estimated time, and filament use and cost.
- **Output**: save the G-code anywhere (SD card, Drive, USB drive), share it, or send it
  straight to **OctoPrint**, **Klipper/Moonraker** (Mainsail, Fluidd), **PrusaLink** or a
  **Flashforge Adventurer 3 / 3 Pro / 4** (finds the printer on your Wi-Fi), and optionally
  start the print.

## Installing on your phone

1. Get the APK from this repository's **Actions** tab (latest "Build APK" run → Artifacts),
   or from **Releases** when there is a tagged build.
2. On the phone, open the APK and allow your browser or file manager to install unknown apps
   when Android asks.
3. Open **Orca Slicer**, pick your printer brand(s) and printer, then import a model, or tap
   **Try a 3DBenchy** on the empty plate.

The APK targets 64-bit ARM (`arm64-v8a`), which covers the Pixel 9 family and practically every
current Android phone. It needs Android 10 or newer.

## How it works

```
app/                    Kotlin + Jetpack Compose UI, OpenGL ES 3 plate/toolpath renderer
native/engine/          CMake project: builds OrcaSlicer's libslic3r (no wxWidgets GUI)
  jni/OrcaEngine.cpp    JNI bridge: presets, model import/transform, arrange, slice, toolpaths
native/deps/            CMake superbuild that cross-compiles libslic3r's dependencies with the NDK
native/patches/orca/    Small patches applied to the OrcaSlicer sources for Android
scripts/                fetch_orca.sh (pinned source checkout), build_deps.sh
ORCA_VERSION            OrcaSlicer release the app is built from
```

The desktop OrcaSlicer is a wxWidgets application, and wxWidgets has no Android port. The port
therefore keeps OrcaSlicer's C++ slicing engine (`libslic3r`) unchanged and replaces only the
GUI. The engine is compiled for Android together with its dependencies (Boost, oneTBB, CGAL,
GMP/MPFR, Eigen, OpenCASCADE for STEP, OpenCV, OpenVDB, …). Printer profiles are bundled in the
APK and unpacked on first launch.

## Building from source

Requirements: Linux or macOS, JDK 17+, the Android SDK with NDK `27.2.12479018` and CMake
`3.31.6` (`sdkmanager "ndk;27.2.12479018" "cmake;3.31.6"`), plus `ninja`, `git`, `make` and
`perl`.

```bash
export ANDROID_HOME=~/Android/Sdk
scripts/fetch_orca.sh            # clone OrcaSlicer at ORCA_VERSION and apply native/patches/orca
scripts/build_deps.sh arm64-v8a  # cross-compile dependencies (1–2 hours the first time)
./gradlew assembleDebug          # builds libslic3r + the app
adb install app/build/outputs/apk/debug/app-debug.apk
```

To also run it on an x86_64 emulator, build the dependencies for that ABI as well and pass
`-PorcaAbis=arm64-v8a,x86_64` to Gradle.

The GitHub Actions workflow does the same steps and caches the dependency build, so only the
first run is slow.

### Testing the engine without a phone

`tests/host/run.sh` builds the same engine and JNI bridge for Linux and drives
it from Java through the app's JNI entry points: it loads the Prusa and Creality
profiles, imports and transforms a model, slices it and checks the G-code. Pass
a printer name to try another profile, e.g.
`tests/host/run.sh "Flashforge Adventurer 3 Series 0.4 Nozzle"`.

`./gradlew testDebugUnitTest` runs the app's JVM tests, including the Flashforge
uploader against a simulated Adventurer 3.

### Updating OrcaSlicer

Change `ORCA_VERSION` to a newer release tag and run `scripts/fetch_orca.sh`. If a patch in
`native/patches/orca` no longer applies, refresh it against the new sources.

## Limitations

- FFF (filament) printers only; SLA/resin is not exposed.
- One plate. 3MF projects import their models, but plate layouts and per-object settings stored
  in a project are not applied.
- No painting (supports, seams, multi-colour), cutting, text/emboss or modifiers yet.
- Single-filament workflow; multi-material printers slice with one filament for all objects.
- Bambu Lab printers use a proprietary network protocol. Slicing for them works; save the G-code
  and send it with Bambu Handy or an SD card.
- No calibration wizards and no online preset sync.

## License

OrcaSlicer is licensed under the GNU AGPL v3, and so is this port. The OrcaSlicer name and
profiles belong to their respective authors; this project is not affiliated with the OrcaSlicer
team.
