package io.github.w00fey.orcaslicer.data

import io.github.w00fey.orcaslicer.render.ThumbnailRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GxWriterTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Bytes 16..57 and the 54-byte BMP header of a .gx file FlashPrint 5 wrote for an
    // Adventurer 3 Series (633 s, 1222 mm, 0.3 mm layers, 2 shells, 80 mm/s, 50/210 °C).
    private val flashPrintHeader = hex("3a000000b0380000b038000079020000c60400000000000005002c010000020050003200d200d20001ff")
    private val flashPrintBmpHeader = hex("424d76380000000000003600000028000000500000003c000000010018000000000040380000c40e0000c40e00000000000000000000")

    @Test
    fun headerMatchesFlashPrintLayout() {
        val gcode = File.createTempFile("box", ".gcode").apply { writeText(";test\nG28\nG1 X10 Y10\n") }
        val out = File.createTempFile("box", ".gx")
        val info = GxWriter.Info(printTimeS = 633, filamentMm = 1222, layerHeightUm = 300, perimeterShells = 2,
            printSpeed = 80, bedTemp = 50, nozzleTemp = 210)
        GxWriter.write(gcode, out, info, IntArray(80 * 60))

        val bytes = out.readBytes()
        assertArrayEquals("xgcode 1.0\n".toByteArray().copyOf(16), bytes.copyOfRange(0, 16))
        assertArrayEquals(flashPrintHeader, bytes.copyOfRange(16, 58))
        assertArrayEquals(flashPrintBmpHeader, bytes.copyOfRange(58, 112))
        assertEquals(";test\nG28\nG1 X10 Y10\n", String(bytes.copyOfRange(14512, bytes.size)))
        gcode.delete(); out.delete()
    }

    @Test
    fun bmpRowsAreBottomUpBgr() {
        val rgb = IntArray(80 * 60)
        rgb[0] = 0x112233            // top-left pixel
        rgb[59 * 80 + 79] = 0xAABBCC // bottom-right pixel
        val bmp = GxWriter.bmp(rgb, 80, 60)
        val firstRow = 54                    // file starts with the bottom row
        val lastRow = 54 + 59 * 240
        assertArrayEquals(byteArrayOf(0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte()), bmp.copyOfRange(firstRow + 79 * 3, firstRow + 80 * 3))
        assertArrayEquals(byteArrayOf(0x33, 0x22, 0x11), bmp.copyOfRange(lastRow, lastRow + 3))
    }

    @Test
    fun readsStatisticsFromOrcaGcode() {
        val gcode = File.createTempFile("orca", ".gcode").apply {
            writeText(
                """
                ; HEADER_BLOCK_START
                M140 S55 T0
                M104 S210 T0
                M104 S0 T1
                G28
                G1 X1 Y1 E1
                ; CONFIG_BLOCK_START
                ; layer_height = 0.2
                ; outer_wall_speed = 50
                ; wall_loops = 3
                ; nozzle_temperature = 210,210
                ; CONFIG_BLOCK_END
                """.trimIndent(),
            )
        }
        val info = GxWriter.infoFromGcode(gcode, 6474.9, 5024.6)
        assertEquals(GxWriter.Info(6475, 5025, 200, 3, 50, 55, 210), info)
        gcode.delete()
    }

    @Test
    fun thumbnailShowsTheModel() {
        // A 20 mm cube: 12 triangles with flat normals, placed on the bed.
        val v = arrayOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(20f, 0f, 0f), floatArrayOf(20f, 20f, 0f), floatArrayOf(0f, 20f, 0f),
            floatArrayOf(0f, 0f, 20f), floatArrayOf(20f, 0f, 20f), floatArrayOf(20f, 20f, 20f), floatArrayOf(0f, 20f, 20f))
        val faces = arrayOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),
            intArrayOf(0, 1, 5), intArrayOf(0, 5, 4), intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),
            intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))
        val mesh = ArrayList<Float>()
        for (f in faces) {
            val a = v[f[0]]; val b = v[f[1]]; val c = v[f[2]]
            val n = floatArrayOf(
                (b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]),
                (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
                (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]),
            )
            for (p in listOf(a, b, c)) mesh.addAll(p.toList() + n.toList())
        }
        val identity = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }
        val px = ThumbnailRenderer.render(listOf(ThumbnailRenderer.Part(mesh.toFloatArray(), identity)), 80, 60)

        val covered = px.count { it != 0 }
        assertTrue("cube should fill a good part of the thumbnail, covered=$covered", covered in 800..4000)
        assertEquals(0, px[0])                // corners stay background
        assertTrue(px[30 * 80 + 40] != 0)     // centre shows the model
        assertTrue(px.toSet().size >= 3)      // three visible faces, shaded differently

        // Keep a viewable copy in the build directory.
        File("build/test-thumbnail.bmp").writeBytes(GxWriter.bmp(px, 80, 60))
    }
}
