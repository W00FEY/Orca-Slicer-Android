// JNI bridge between the Android app and OrcaSlicer's libslic3r.
//
// The engine keeps one PresetBundle, one Model and one Print alive for the
// lifetime of the process. All entry points are serialised by `g_mutex`, except
// progress polling and cancellation which must work while a slice is running.
// Structured results are returned to Kotlin as JSON strings.

#include "JniUtils.hpp"

#include <jni.h>

#ifdef __ANDROID__
#include <android/log.h>
#else
// Host builds (tests/host) log to stderr instead of logcat.
#include <cstdio>
#define ANDROID_LOG_ERROR 6
#define __android_log_print(prio, tag, ...) (std::fprintf(stderr, "%s: ", tag), std::fprintf(stderr, __VA_ARGS__), std::fprintf(stderr, "\n"))
#endif

#include <atomic>
#include <cmath>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include <boost/filesystem.hpp>
#include <boost/log/core.hpp>
#include <boost/log/expressions.hpp>
#include <boost/log/trivial.hpp>

#include "nlohmann/json.hpp"

#include "libslic3r/libslic3r.h"
#include "libslic3r/Exception.hpp"
#include "libslic3r/AppConfig.hpp"
#include "libslic3r/Format/bbs_3mf.hpp"
#include "libslic3r/GCode/GCodeProcessor.hpp"
#include "libslic3r/Model.hpp"
#include "libslic3r/ModelArrange.hpp"
#include "libslic3r/Preset.hpp"
#include "libslic3r/PresetBundle.hpp"
#include "libslic3r/Print.hpp"
#include "libslic3r/PrintConfig.hpp"
#include "libslic3r/Utils.hpp"
#include "libslic3r_version.h"

using json = nlohmann::json;
using namespace Slic3r;
using orca_android::to_jstring;
using orca_android::to_std_string;

namespace {

constexpr const char *TAG = "OrcaEngine";

struct Progress
{
    std::mutex  mutex;
    int         percent = 0;
    std::string text;
    std::vector<std::string> warnings;
};

struct Engine
{
    bool                          initialized = false;
    AppConfig                     app_config;
    std::unique_ptr<PresetBundle> bundle;
    Model                         model;
    std::unique_ptr<Print>        print;
    GCodeProcessorResult          gcode_result;
    // Per-session overrides on top of the selected presets (key -> serialized value).
    DynamicPrintConfig            overrides;
};

std::mutex          g_mutex;
Progress            g_progress;

// Created on first use rather than as a global: AppConfig's constructor reads
// other libslic3r statics, which may not be initialised yet while the shared
// library is being loaded.
Engine &engine()
{
    static Engine instance;
    return instance;
}
std::atomic<Print*> g_running_print{nullptr};

jstring error_json(JNIEnv *env, const std::string &msg)
{
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", msg.c_str());
    return to_jstring(env, json{{"ok", false}, {"error", msg}}.dump());
}

jstring ok_json(JNIEnv *env, json j = json::object())
{
    j["ok"] = true;
    return to_jstring(env, j.dump());
}

// Runs `fn` under the engine lock and converts exceptions to {"ok":false}.
template<class Fn> jstring guarded(JNIEnv *env, Fn &&fn)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        if (!engine().initialized)
            return error_json(env, "Engine not initialised");
        return fn();
    } catch (const SlicingErrors &ex) {
        // G-code export collects per-object errors and throws them together.
        std::string msg;
        for (const SlicingError &e : ex.errors_)
            msg += (msg.empty() ? "" : "\n") + std::string(e.what());
        return error_json(env, msg.empty() ? "Slicing failed" : msg);
    } catch (const std::exception &ex) {
        return error_json(env, ex.what());
    } catch (...) {
        return error_json(env, "Unknown native error");
    }
}

DynamicPrintConfig current_config()
{
    DynamicPrintConfig cfg = engine().bundle->full_config();
    cfg.apply(engine().overrides, true);
    return cfg;
}

json bed_json(const DynamicPrintConfig &cfg)
{
    json shape = json::array();
    if (auto *area = cfg.option<ConfigOptionPoints>("printable_area"))
        for (const Vec2d &p : area->values)
            shape.push_back({p.x(), p.y()});
    double height = cfg.has("printable_height") ? cfg.opt_float("printable_height") : 250.;
    return {{"shape", shape}, {"max_height", height}};
}

std::string preset_vendor(const Preset &p)
{
    return p.vendor ? p.vendor->name : (p.is_user() ? "User" : "");
}

json preset_list(const PresetCollection &collection, bool only_compatible)
{
    json out = json::array();
    for (auto it = collection.begin(); it != collection.end(); ++it) {
        const Preset &p = *it;
        if (!p.is_visible || (only_compatible && !p.is_compatible))
            continue;
        out.push_back({{"name", p.name}, {"vendor", preset_vendor(p)}, {"system", p.is_system}});
    }
    return out;
}

json selection_json()
{
    PresetBundle &b   = *engine().bundle;
    const auto   cfg  = current_config();
    json         j;
    j["printer"]    = b.printers.get_selected_preset_name();
    j["process"]    = b.prints.get_selected_preset_name();
    j["filament"]   = b.filament_presets.empty() ? "" : b.filament_presets.front();
    j["processes"]  = preset_list(b.prints, true);
    j["filaments"]  = preset_list(b.filaments, true);
    j["bed"]        = bed_json(cfg);
    return j;
}

// Selects `wanted` if it is a usable preset, otherwise keeps the current one
// unless that is the generic default or incompatible, in which case the first
// compatible system preset is used.
std::string pick_preset(PresetCollection &col, const std::string &wanted)
{
    if (const Preset *p = wanted.empty() ? nullptr : col.find_preset(wanted); p && p->is_compatible && p->is_visible)
        return p->name;
    const Preset &cur = col.get_selected_preset();
    if (!cur.is_default && cur.is_compatible)
        return cur.name;
    for (auto it = col.begin(); it != col.end(); ++it)
        if (it->is_compatible && it->is_visible && !it->is_default)
            return it->name;
    return cur.name;
}

// After a printer change, switch to the process/filament the printer profile
// recommends, like the desktop app does.
void select_printer_defaults(PresetBundle &b)
{
    const DynamicPrintConfig &printer = b.printers.get_selected_preset().config;
    b.prints.select_preset_by_name(pick_preset(b.prints, printer.opt_string("default_print_profile")), true);
    std::string filament;
    if (auto *opt = printer.option<ConfigOptionStrings>("default_filament_profile"); opt && !opt->values.empty())
        filament = opt->values.front();
    filament = pick_preset(b.filaments, filament);
    b.filaments.select_preset_by_name(filament, true);
    for (size_t i = 0; i < b.filament_presets.size(); ++i)
        b.set_filament_preset(i, filament);
    b.update_compatible(PresetSelectCompatibleType::OnlyIfWasCompatible);
}

json option_json(const std::string &key, const DynamicPrintConfig &cfg)
{
    const ConfigOptionDef *def = print_config_def.get(key);
    if (def == nullptr)
        return nullptr;
    json j{{"key", key},
           {"label", def->full_label.empty() ? def->label : def->full_label},
           {"category", def->category},
           {"tooltip", def->tooltip},
           {"sidetext", def->sidetext},
           {"type", int(def->type)},
           {"readonly", def->readonly}};
    if (def->min > -FLT_MAX)
        j["min"] = def->min;
    if (def->max < FLT_MAX)
        j["max"] = def->max;
    if (!def->enum_values.empty()) {
        j["enum_values"] = def->enum_values;
        j["enum_labels"] = def->enum_labels.empty() ? def->enum_values : def->enum_labels;
    }
    const ConfigOption *opt = cfg.option(key);
    j["value"]              = opt ? opt->serialize() : std::string();
    j["overridden"]         = engine().overrides.has(key);
    return j;
}

json objects_json()
{
    json out = json::array();
    for (const ModelObject *obj : engine().model.objects) {
        json instances = json::array();
        for (size_t i = 0; i < obj->instances.size(); ++i) {
            const ModelInstance *inst = obj->instances[i];
            const Vec3d          off  = inst->get_offset();
            const Vec3d          rot  = inst->get_rotation();
            const Vec3d          scl  = inst->get_scaling_factor();
            const BoundingBoxf3  bb   = obj->instance_bounding_box(i);
            const Transform3d   &m    = inst->get_matrix();
            std::vector<double>  mat(m.data(), m.data() + 16); // column-major, like OpenGL
            instances.push_back({{"offset", {off.x(), off.y(), off.z()}},
                                 {"rotation_deg", {Geometry::rad2deg(rot.x()), Geometry::rad2deg(rot.y()), Geometry::rad2deg(rot.z())}},
                                 {"scale", {scl.x(), scl.y(), scl.z()}},
                                 {"size", {bb.size().x(), bb.size().y(), bb.size().z()}},
                                 {"bbox_min", {bb.min.x(), bb.min.y(), bb.min.z()}},
                                 {"bbox_max", {bb.max.x(), bb.max.y(), bb.max.z()}},
                                 {"matrix", mat}});
        }
        out.push_back({{"name", obj->name},
                       {"volumes", obj->volumes.size()},
                       {"triangles", obj->facets_count()},
                       {"instances", instances}});
    }
    return out;
}

// Puts every instance of the object on the bed. ModelObject::ensure_on_bed()
// shifts all instances together, which leaves a copy with a different scale or
// rotation floating or sunk.
void drop_to_bed(ModelObject *obj)
{
    for (size_t i = 0; i < obj->instances.size(); ++i) {
        const double min_z = obj->instance_bounding_box(i).min.z();
        if (std::abs(min_z) > EPSILON) {
            ModelInstance *inst = obj->instances[i];
            inst->set_offset(Z, inst->get_offset(Z) - min_z);
        }
    }
}

ModelInstance *instance_at(int obj_idx, int inst_idx)
{
    if (obj_idx < 0 || size_t(obj_idx) >= engine().model.objects.size())
        throw Slic3r::RuntimeError("Invalid object index");
    ModelObject *obj = engine().model.objects[obj_idx];
    if (inst_idx < 0 || size_t(inst_idx) >= obj->instances.size())
        throw Slic3r::RuntimeError("Invalid instance index");
    return obj->instances[inst_idx];
}

void arrange_model(const DynamicPrintConfig &cfg)
{
    if (engine().model.objects.empty())
        return;
    arrangement::ArrangeParams params;
    params.min_obj_distance = scaled(6.);
    params.allow_rotations  = false;
    Points bed = get_bed_shape(cfg);
    arrange_objects(engine().model, bed, params, [](arrangement::ArrangePolygon &) {});
    for (ModelObject *o : engine().model.objects)
        drop_to_bed(o);
}

// Loads system and user presets. The desktop app only shows printers whose
// models were ticked in its setup wizard (stored in AppConfig); the Android app
// installs just the vendors the user picked, so every model they contain is
// marked as installed here.
std::unique_ptr<PresetBundle> load_bundle(AppConfig &app_config)
{
    auto bundle = std::make_unique<PresetBundle>();
    bundle->load_presets(app_config, ForwardCompatibilitySubstitutionRule::EnableSystemSilent);

    AppConfig::VendorMap installed;
    for (const Preset &p : bundle->printers) {
        if (p.vendor == nullptr)
            continue;
        const std::string &model   = p.config.opt_string("printer_model");
        const std::string &variant = p.config.opt_string("printer_variant");
        if (!model.empty() && !variant.empty())
            installed[p.vendor->id][model].insert(variant);
    }
    app_config.set_vendors(std::move(installed));
    bundle->load_installed_printers(app_config);
    bundle->set_default_suppressed(true);
    return bundle;
}

void setup_logging()
{
    // libslic3r logs a lot at debug level; keep Android's logcat readable.
    boost::log::core::get()->set_filter(boost::log::trivial::severity >= boost::log::trivial::warning);
}

} // namespace

extern "C" {

// ------------------------------------------------------------------ lifecycle

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeInit(JNIEnv *env, jclass, jstring jresources, jstring jdata, jstring jtemp)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        const std::string resources = to_std_string(env, jresources);
        const std::string data      = to_std_string(env, jdata);
        const std::string temp      = to_std_string(env, jtemp);
        setup_logging();
        set_resources_dir(resources);
        set_var_dir((boost::filesystem::path(resources) / "images").string());
        set_local_dir((boost::filesystem::path(resources) / "i18n").string());
        set_sys_shapes_dir((boost::filesystem::path(resources) / "shape").string());
        set_custom_gcodes_dir((boost::filesystem::path(resources) / "custom_gcodes").string());
        set_data_dir(data);
        set_temporary_dir(temp);
        boost::filesystem::create_directories(boost::filesystem::path(data) / PRESET_SYSTEM_DIR);
        boost::filesystem::create_directories(boost::filesystem::path(data) / "user" / "default");

        engine().bundle = load_bundle(engine().app_config);
        engine().print       = std::make_unique<Print>();
        engine().initialized = true;
        return ok_json(env, {{"version", SoftFever_VERSION}});
    } catch (const std::exception &ex) {
        return error_json(env, std::string("Initialisation failed: ") + ex.what());
    }
}

// Reloads system/user presets, e.g. after the app installed more vendor profiles.
JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeReloadPresets(JNIEnv *env, jclass)
{
    return guarded(env, [&] {
        std::string printer = engine().bundle->printers.get_selected_preset_name();
        engine().bundle     = load_bundle(engine().app_config);
        engine().bundle->printers.select_preset_by_name(printer, false);
        engine().bundle->update_compatible(PresetSelectCompatibleType::Always);
        return ok_json(env);
    });
}

// ------------------------------------------------------------------ presets

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeListPrinters(JNIEnv *env, jclass)
{
    return guarded(env, [&] {
        json printers = json::array();
        for (auto it = engine().bundle->printers.begin(); it != engine().bundle->printers.end(); ++it) {
            const Preset &p = *it;
            if (!p.is_visible)
                continue;
            std::string nozzle;
            if (auto *opt = p.config.option<ConfigOptionFloats>("nozzle_diameter"); opt && !opt->values.empty())
                nozzle = float_to_string_decimal_point(opt->values.front());
            printers.push_back({{"name", p.name},
                                {"vendor", preset_vendor(p)},
                                {"model", p.config.opt_string("printer_model")},
                                {"nozzle", nozzle},
                                {"system", p.is_system}});
        }
        return ok_json(env, {{"printers", printers}, {"selection", selection_json()}});
    });
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeSelectPresets(JNIEnv *env, jclass, jstring jprinter, jstring jfilament, jstring jprocess)
{
    return guarded(env, [&] {
        PresetBundle     &b        = *engine().bundle;
        const std::string printer  = to_std_string(env, jprinter);
        const std::string filament = to_std_string(env, jfilament);
        const std::string process  = to_std_string(env, jprocess);
        if (!printer.empty() && printer != b.printers.get_selected_preset_name()) {
            if (b.printers.find_preset(printer) == nullptr)
                throw Slic3r::RuntimeError("Unknown printer preset: " + printer);
            b.printers.select_preset_by_name(printer, true);
            b.update_multi_material_filament_presets();
            b.update_compatible(PresetSelectCompatibleType::Always);
            select_printer_defaults(b);
        }
        if (!process.empty())
            b.prints.select_preset_by_name(process, true);
        if (!filament.empty()) {
            b.filaments.select_preset_by_name(filament, true);
            for (size_t i = 0; i < b.filament_presets.size(); ++i)
                b.set_filament_preset(i, filament);
        }
        b.update_compatible(PresetSelectCompatibleType::OnlyIfWasCompatible);
        return ok_json(env, {{"selection", selection_json()}});
    });
}

// keys: JSON array of option keys. Returns definitions + effective values.
JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeGetOptions(JNIEnv *env, jclass, jstring jkeys)
{
    return guarded(env, [&] {
        const json               keys = json::parse(to_std_string(env, jkeys));
        const DynamicPrintConfig cfg  = current_config();
        json                     out  = json::array();
        for (const auto &k : keys) {
            json o = option_json(k.get<std::string>(), cfg);
            if (!o.is_null())
                out.push_back(std::move(o));
        }
        return ok_json(env, {{"options", out}});
    });
}

// All option keys grouped by the preset type they belong to.
JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeListOptionKeys(JNIEnv *env, jclass)
{
    return guarded(env, [&] {
        return ok_json(env, {{"process", Preset::print_options()},
                             {"filament", Preset::filament_options()},
                             {"printer", Preset::printer_options()}});
    });
}

// values: JSON object key -> serialized value. A null value removes the override.
JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeSetOverrides(JNIEnv *env, jclass, jstring jvalues)
{
    return guarded(env, [&] {
        const json                values = json::parse(to_std_string(env, jvalues));
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::Disable);
        json                      errors = json::object();
        for (auto it = values.begin(); it != values.end(); ++it) {
            if (it.value().is_null()) {
                engine().overrides.erase(it.key());
                continue;
            }
            const std::string value = it.value().is_string() ? it.value().get<std::string>() : it.value().dump();
            try {
                // Vector options (per extruder) are set from a scalar by repeating it.
                const ConfigOptionDef *def = print_config_def.get(it.key());
                if (def == nullptr)
                    throw Slic3r::RuntimeError("Unknown option");
                std::string v = value;
                if ((def->type & coVectorType) && def->type != coPoints) {
                    const auto *base = current_config().option(it.key());
                    size_t      n    = base ? static_cast<const ConfigOptionVectorBase *>(base)->size() : 1;
                    if (n > 1 && v.find(',') == std::string::npos && def->type != coStrings) {
                        std::string joined;
                        for (size_t i = 0; i < n; ++i)
                            joined += (i ? "," : "") + v;
                        v = joined;
                    }
                }
                engine().overrides.set_deserialize_strict(it.key(), v);
            } catch (const std::exception &ex) {
                errors[it.key()] = ex.what();
            }
        }
        return ok_json(env, {{"errors", errors}, {"selection", selection_json()}});
    });
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeClearOverrides(JNIEnv *env, jclass)
{
    return guarded(env, [&] {
        engine().overrides.clear();
        return ok_json(env);
    });
}

// ------------------------------------------------------------------ model

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeLoadModel(JNIEnv *env, jclass, jstring jpath)
{
    return guarded(env, [&] {
        const std::string  path = to_std_string(env, jpath);
        DynamicPrintConfig file_config;
        ConfigSubstitutionContext ctx(ForwardCompatibilitySubstitutionRule::EnableSilent);
        Model loaded = Model::read_from_file(path, &file_config, &ctx, LoadStrategy::LoadModel | LoadStrategy::AddDefaultInstances);
        if (loaded.objects.empty())
            throw Slic3r::RuntimeError("The file does not contain any printable objects");

        const DynamicPrintConfig cfg = current_config();
        for (ModelObject *src : loaded.objects) {
            ModelObject *obj = engine().model.add_object(*src);
            if (obj->instances.empty())
                obj->add_instance();
            obj->center_around_origin();
            drop_to_bed(obj);
        }
        arrange_model(cfg);
        return ok_json(env, {{"objects", objects_json()}});
    });
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeGetObjects(JNIEnv *env, jclass)
{
    return guarded(env, [&] { return ok_json(env, {{"objects", objects_json()}}); });
}

// Returns the object's mesh in object coordinates as interleaved
// [x, y, z, nx, ny, nz] per vertex, three vertices per triangle.
JNIEXPORT jfloatArray JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeGetObjectMesh(JNIEnv *env, jclass, jint obj_idx)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    try {
        if (obj_idx < 0 || size_t(obj_idx) >= engine().model.objects.size())
            return nullptr;
        const TriangleMesh mesh = engine().model.objects[obj_idx]->raw_mesh();
        const auto        &its  = mesh.its;
        std::vector<float> data;
        data.reserve(its.indices.size() * 18);
        for (const auto &f : its.indices) {
            const Vec3f &a = its.vertices[f[0]], &b = its.vertices[f[1]], &c = its.vertices[f[2]];
            Vec3f        n = (b - a).cross(c - a);
            float        l = n.norm();
            if (l > 0)
                n /= l;
            for (const Vec3f *v : {&a, &b, &c})
                data.insert(data.end(), {v->x(), v->y(), v->z(), n.x(), n.y(), n.z()});
        }
        jfloatArray arr = env->NewFloatArray(jsize(data.size()));
        if (arr)
            env->SetFloatArrayRegion(arr, 0, jsize(data.size()), data.data());
        return arr;
    } catch (const std::exception &ex) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "getObjectMesh: %s", ex.what());
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeSetInstanceTransform(JNIEnv *env, jclass, jint obj_idx, jint inst_idx,
                                                                              jdouble x, jdouble y, jdouble rot_z_deg, jdouble scale)
{
    return guarded(env, [&] {
        if (!(scale > 0.0) || !std::isfinite(scale))
            throw Slic3r::RuntimeError("Scale must be positive");
        ModelInstance *inst = instance_at(obj_idx, inst_idx);
        Vec3d          rot  = inst->get_rotation();
        inst->set_rotation(Vec3d(rot.x(), rot.y(), Geometry::deg2rad(rot_z_deg)));
        inst->set_scaling_factor(Vec3d(scale, scale, scale));
        inst->set_offset(Vec3d(x, y, inst->get_offset().z()));
        drop_to_bed(engine().model.objects[obj_idx]);
        return ok_json(env, {{"objects", objects_json()}});
    });
}

// Rotates one instance about a world axis (0 = X, 1 = Y, 2 = Z) through its
// bounding-box centre, then drops the object back onto the bed.
JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeRotateInstance(JNIEnv *env, jclass, jint obj_idx, jint inst_idx, jint axis, jdouble deg)
{
    return guarded(env, [&] {
        if (axis < 0 || axis > 2)
            throw Slic3r::RuntimeError("Invalid axis");
        ModelInstance *inst   = instance_at(obj_idx, inst_idx);
        const Vec3d    center = engine().model.objects[obj_idx]->instance_bounding_box(size_t(inst_idx)).center();
        const Transform3d rot = Geometry::translation_transform(center) *
                                Transform3d(Eigen::AngleAxisd(Geometry::deg2rad(double(deg)), Vec3d::Unit(axis))) *
                                Geometry::translation_transform(-center);
        inst->set_transformation(Geometry::Transformation(rot * inst->get_matrix()));
        drop_to_bed(engine().model.objects[obj_idx]);
        return ok_json(env, {{"objects", objects_json()}});
    });
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeAddInstance(JNIEnv *env, jclass, jint obj_idx)
{
    return guarded(env, [&] {
        ModelInstance *src = instance_at(obj_idx, 0);
        engine().model.objects[obj_idx]->add_instance(*src);
        arrange_model(current_config());
        return ok_json(env, {{"objects", objects_json()}});
    });
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeRemoveObject(JNIEnv *env, jclass, jint obj_idx)
{
    return guarded(env, [&] {
        if (obj_idx < 0 || size_t(obj_idx) >= engine().model.objects.size())
            throw Slic3r::RuntimeError("Invalid object index");
        ModelObject *obj = engine().model.objects[obj_idx];
        if (obj->instances.size() > 1)
            obj->delete_instance(obj->instances.size() - 1);
        else
            engine().model.delete_object(size_t(obj_idx));
        return ok_json(env, {{"objects", objects_json()}});
    });
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeClearModel(JNIEnv *env, jclass)
{
    return guarded(env, [&] {
        engine().model.clear_objects();
        engine().print->clear();
        return ok_json(env, {{"objects", json::array()}});
    });
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeArrange(JNIEnv *env, jclass)
{
    return guarded(env, [&] {
        arrange_model(current_config());
        return ok_json(env, {{"objects", objects_json()}});
    });
}

// ------------------------------------------------------------------ slicing

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeSlice(JNIEnv *env, jclass, jstring joutput)
{
    return guarded(env, [&] {
        const std::string output = to_std_string(env, joutput);
        if (engine().model.objects.empty())
            throw Slic3r::RuntimeError("Add a model before slicing");
        {
            std::lock_guard<std::mutex> plock(g_progress.mutex);
            g_progress.percent = 0;
            g_progress.text    = "Preparing";
            g_progress.warnings.clear();
        }

        DynamicPrintConfig cfg = current_config();
        if (Preset::printer_technology(cfg) != ptFFF)
            throw Slic3r::RuntimeError("Only FFF (filament) printers are supported");

        Print &print = *engine().print;
        print.set_status_callback([](const PrintBase::SlicingStatus &s) {
            std::lock_guard<std::mutex> plock(g_progress.mutex);
            if (s.percent >= 0)
                g_progress.percent = s.percent;
            if (!s.text.empty()) {
                if (s.flags & (PrintBase::SlicingStatus::UPDATE_PRINT_STEP_WARNINGS | PrintBase::SlicingStatus::UPDATE_PRINT_OBJECT_STEP_WARNINGS))
                    g_progress.warnings.push_back(s.text);
                else
                    g_progress.text = s.text;
            }
        });

        // Make sure every object has an instance and sits on the bed.
        for (ModelObject *o : engine().model.objects) {
            if (o->instances.empty())
                o->add_instance();
            drop_to_bed(o);
        }

        const size_t filaments = std::max<size_t>(1, cfg.option<ConfigOptionFloats>("nozzle_diameter")->values.size());
        Model::setExtruderParams(cfg, int(filaments));
        print.apply(engine().model, cfg);
        Model::setPrintSpeedTable(cfg, print.config());

        StringObjectException warning;
        StringObjectException err = print.validate(&warning);
        if (!err.string.empty())
            throw Slic3r::RuntimeError(err.string);
        if (!warning.string.empty()) {
            std::lock_guard<std::mutex> plock(g_progress.mutex);
            g_progress.warnings.push_back(warning.string);
        }
        if (print.empty())
            throw Slic3r::RuntimeError("Nothing to slice: no object is fully inside the print volume");

        g_running_print = &print;
        struct Reset { ~Reset() { g_running_print = nullptr; } } reset;
        try {
            print.process();
            engine().gcode_result.reset();
            std::string path = print.export_gcode(output, &engine().gcode_result, nullptr);

            const auto &ps   = print.print_statistics();
            const auto &est  = engine().gcode_result.print_statistics;
            json        warns;
            {
                std::lock_guard<std::mutex> plock(g_progress.mutex);
                g_progress.percent = 100;
                g_progress.text    = "Done";
                warns              = g_progress.warnings;
            }
            return ok_json(env, {{"gcode_path", path},
                                 {"print_time_s", est.modes[0].time},
                                 {"filament_mm", ps.total_used_filament},
                                 {"filament_g", ps.total_weight},
                                 {"filament_cost", ps.total_cost},
                                 {"layers", engine().gcode_result.moves.empty() ? 0 : engine().gcode_result.moves.back().layer_id + 1},
                                 {"warnings", warns}});
        } catch (const CanceledException &) {
            return error_json(env, "Slicing cancelled");
        }
    });
}

JNIEXPORT void JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeCancel(JNIEnv *, jclass)
{
    if (Print *p = g_running_print.load())
        p->cancel();
}

JNIEXPORT jstring JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeGetProgress(JNIEnv *env, jclass)
{
    std::lock_guard<std::mutex> plock(g_progress.mutex);
    return to_jstring(env, json{{"percent", g_progress.percent}, {"text", g_progress.text}}.dump());
}

// Toolpaths of the last slice, for the preview: 8 floats per extrusion segment
// [x0, y0, z0, x1, y1, z1, width, role]. Layer boundaries are where z changes.
JNIEXPORT jfloatArray JNICALL
Java_io_github_w00fey_orcaslicer_engine_NativeEngine_nativeGetToolpaths(JNIEnv *env, jclass)
{
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto        &moves = engine().gcode_result.moves;
    std::vector<float> data;
    data.reserve(moves.size() * 4);
    for (size_t i = 1; i < moves.size(); ++i) {
        const auto &m = moves[i];
        if (m.type != EMoveType::Extrude || m.width <= 0.f)
            continue;
        const Vec3f &a = moves[i - 1].position;
        const Vec3f &b = m.position;
        data.insert(data.end(), {a.x(), a.y(), b.z(), b.x(), b.y(), b.z(), m.width, float(m.extrusion_role)});
    }
    jfloatArray arr = env->NewFloatArray(jsize(data.size()));
    if (arr)
        env->SetFloatArrayRegion(arr, 0, jsize(data.size()), data.data());
    return arr;
}

} // extern "C"
