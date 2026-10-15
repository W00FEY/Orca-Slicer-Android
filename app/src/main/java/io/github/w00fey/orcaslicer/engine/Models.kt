package io.github.w00fey.orcaslicer.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PrinterPreset(
    val name: String,
    val vendor: String = "",
    val model: String = "",
    val nozzle: String = "",
    val system: Boolean = true,
)

@Serializable
data class PresetRef(val name: String, val vendor: String = "", val system: Boolean = true)

@Serializable
data class Bed(
    val shape: List<List<Double>> = emptyList(),
    @SerialName("max_height") val maxHeight: Double = 250.0,
) {
    val minX get() = shape.minOfOrNull { it[0] } ?: 0.0
    val maxX get() = shape.maxOfOrNull { it[0] } ?: 220.0
    val minY get() = shape.minOfOrNull { it[1] } ?: 0.0
    val maxY get() = shape.maxOfOrNull { it[1] } ?: 220.0
    val centerX get() = (minX + maxX) / 2
    val centerY get() = (minY + maxY) / 2
}

@Serializable
data class Selection(
    val printer: String = "",
    val process: String = "",
    val filament: String = "",
    val processes: List<PresetRef> = emptyList(),
    val filaments: List<PresetRef> = emptyList(),
    val bed: Bed = Bed(),
)

@Serializable
data class ModelInstanceInfo(
    val offset: List<Double>,
    @SerialName("rotation_deg") val rotationDeg: List<Double>,
    val scale: List<Double>,
    val size: List<Double>,
    @SerialName("bbox_min") val bboxMin: List<Double> = listOf(0.0, 0.0, 0.0),
    @SerialName("bbox_max") val bboxMax: List<Double> = listOf(0.0, 0.0, 0.0),
    /** Column-major 4x4 object-to-world matrix. */
    val matrix: List<Double>,
)

@Serializable
data class ModelObjectInfo(
    val name: String,
    val volumes: Int = 1,
    val triangles: Int = 0,
    val instances: List<ModelInstanceInfo> = emptyList(),
)

/** Mirrors libslic3r's ConfigOptionType. */
object OptionType {
    const val VECTOR = 0x4000
    const val FLOAT = 1
    const val INT = 2
    const val STRING = 3
    const val PERCENT = 4
    const val FLOAT_OR_PERCENT = 5
    const val POINT = 6
    const val BOOL = 8
    const val ENUM = 9
}

@Serializable
data class OptionDef(
    val key: String,
    val label: String = "",
    val category: String = "",
    val tooltip: String = "",
    val sidetext: String = "",
    val type: Int = 0,
    val readonly: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    @SerialName("enum_values") val enumValues: List<String> = emptyList(),
    @SerialName("enum_labels") val enumLabels: List<String> = emptyList(),
    val value: String = "",
    val overridden: Boolean = false,
) {
    val baseType get() = type and (OptionType.VECTOR - 1)
    val isVector get() = (type and OptionType.VECTOR) != 0

    /** For per-extruder options the first value is shown and edited. */
    val displayValue: String
        get() = if (isVector && baseType != OptionType.STRING) value.substringBefore(',') else value
}

@Serializable
data class OptionKeys(
    val process: List<String> = emptyList(),
    val filament: List<String> = emptyList(),
    val printer: List<String> = emptyList(),
)

@Serializable
data class SliceResult(
    @SerialName("gcode_path") val gcodePath: String,
    @SerialName("print_time_s") val printTimeS: Double = 0.0,
    @SerialName("filament_mm") val filamentMm: Double = 0.0,
    @SerialName("filament_g") val filamentG: Double = 0.0,
    @SerialName("filament_cost") val filamentCost: Double = 0.0,
    val layers: Int = 0,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class SliceProgress(val percent: Int = 0, val text: String = "")

class EngineException(message: String) : Exception(message)
