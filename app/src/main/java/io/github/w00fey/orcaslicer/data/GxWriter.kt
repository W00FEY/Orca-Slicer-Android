package io.github.w00fey.orcaslicer.data

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Writes Flashforge ".gx" files (FlashPrint's "xgcode 1.0" format): a small
 * binary header with print statistics, an 80x60 BMP thumbnail, then the
 * unchanged G-code. Adventurer 3 / 4 and Finder printers show the thumbnail,
 * print time and filament use from the header on their screen.
 *
 * Layout (little endian), matching files produced by FlashPrint:
 *   0   magic "xgcode 1.0\n" padded to 16 bytes
 *   16  u32 thumbnail offset (58)
 *   20  u32 G-code offset, 24 u32 G-code offset again
 *   28  u32 print time [s], 32 u32 filament right [mm], 36 u32 filament left [mm]
 *   40  u16 multi-extruder type (5 = right extruder only), 42 u16 layer height [µm]
 *   44  u16 reserved (0), 46 u16 perimeter shells, 48 u16 print speed [mm/s]
 *   50  u16 bed temp, 52 u16 right nozzle temp, 54 u16 left nozzle temp, 56 u16 0xFF01
 *   58  BMP (24-bit, 80x60, bottom-up), then G-code
 */
object GxWriter {
    const val THUMB_WIDTH = 80
    const val THUMB_HEIGHT = 60
    private const val HEADER_SIZE = 58
    private val MAGIC = "xgcode 1.0\n".toByteArray(Charsets.US_ASCII).copyOf(16)

    data class Info(
        val printTimeS: Int,
        val filamentMm: Int,
        val layerHeightUm: Int,
        val perimeterShells: Int,
        val printSpeed: Int,
        val bedTemp: Int,
        val nozzleTemp: Int,
    )

    /** Writes [gcode] as a .gx file to [out]. [thumbnailRgb] holds 80x60 0xRRGGBB pixels, top row first. */
    fun write(gcode: File, out: File, info: Info, thumbnailRgb: IntArray) {
        val bmp = bmp(thumbnailRgb, THUMB_WIDTH, THUMB_HEIGHT)
        val gcodeOffset = HEADER_SIZE + bmp.size
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC)
            putInt(HEADER_SIZE)
            putInt(gcodeOffset)
            putInt(gcodeOffset)
            putInt(info.printTimeS.coerceAtLeast(0))
            putInt(info.filamentMm.coerceAtLeast(0))
            putInt(0)
            putShort(5)
            putShort(u16(info.layerHeightUm))
            putShort(0)
            putShort(u16(info.perimeterShells))
            putShort(u16(info.printSpeed))
            putShort(u16(info.bedTemp))
            putShort(u16(info.nozzleTemp))
            putShort(u16(info.nozzleTemp))
            putShort(0xFF01.toShort())
        }
        out.outputStream().buffered().use { o ->
            o.write(header.array())
            o.write(bmp)
            gcode.inputStream().use { it.copyTo(o) }
        }
    }

    private fun u16(v: Int) = v.coerceIn(0, 0xFFFF).toShort()

    /** 24-bit uncompressed BMP, rows stored bottom-up in BGR order. */
    fun bmp(rgb: IntArray, width: Int, height: Int): ByteArray {
        require(rgb.size == width * height)
        val rowSize = (width * 3 + 3) / 4 * 4
        val pixelBytes = rowSize * height
        val buf = ByteBuffer.allocate(54 + pixelBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte())
        buf.putInt(54 + pixelBytes).putShort(0).putShort(0).putInt(54)
        buf.putInt(40).putInt(width).putInt(height).putShort(1).putShort(24)
        buf.putInt(0).putInt(pixelBytes).putInt(3780).putInt(3780).putInt(0).putInt(0)
        for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                val c = rgb[y * width + x]
                buf.put((c and 0xFF).toByte()).put(((c shr 8) and 0xFF).toByte()).put(((c shr 16) and 0xFF).toByte())
            }
            repeat(rowSize - width * 3) { buf.put(0) }
        }
        return buf.array()
    }

    /**
     * Reads the statistics for the header from an OrcaSlicer G-code file: the
     * settings block Orca appends at the end, and the first M140/M104 temperatures.
     */
    fun infoFromGcode(gcode: File, printTimeS: Double, filamentMm: Double): Info {
        val settings = HashMap<String, String>()
        RandomAccessFile(gcode, "r").use { f ->
            val tail = minOf(f.length(), 256L * 1024)
            f.seek(f.length() - tail)
            val bytes = ByteArray(tail.toInt())
            f.readFully(bytes)
            for (line in String(bytes, Charsets.UTF_8).lineSequence()) {
                if (!line.startsWith("; ")) continue
                val eq = line.indexOf(" = ")
                if (eq > 2) settings[line.substring(2, eq)] = line.substring(eq + 3)
            }
        }
        var bed: Int? = null
        var nozzle: Int? = null
        gcode.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (bed == null && line.startsWith("M140 ")) bed = sValue(line)
                if (nozzle == null && (line.startsWith("M104 ") || line.startsWith("M109 "))) nozzle = sValue(line)?.takeIf { it > 0 }
                if (bed != null && nozzle != null) break
            }
        }
        fun num(key: String) = settings[key]?.substringBefore(',')?.trim()?.removeSuffix("%")?.toDoubleOrNull()
        return Info(
            printTimeS = printTimeS.roundToInt(),
            filamentMm = filamentMm.roundToInt(),
            layerHeightUm = ((num("layer_height") ?: 0.2) * 1000).roundToInt(),
            perimeterShells = (num("wall_loops") ?: 2.0).roundToInt(),
            printSpeed = (num("outer_wall_speed") ?: num("inner_wall_speed") ?: 60.0).roundToInt(),
            bedTemp = bed ?: (num("hot_plate_temp") ?: 0.0).roundToInt(),
            nozzleTemp = nozzle ?: (num("nozzle_temperature") ?: 0.0).roundToInt(),
        )
    }

    private fun sValue(line: String): Int? =
        Regex("""\bS(\d+(?:\.\d+)?)""").find(line.substringBefore(';'))?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()
}
