package io.github.w00fey.orcaslicer.render

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders the plate's objects into a small image (e.g. the 80x60 thumbnail of
 * a Flashforge .gx file) with a z-buffered software rasterizer: an angled
 * orthographic view, the model shaded in amber on black like FlashPrint.
 */
object ThumbnailRenderer {
    /** One placed mesh: interleaved [x,y,z,nx,ny,nz] per vertex (object space) and its column-major object-to-world matrix. */
    class Part(val mesh: FloatArray, val matrix: FloatArray)

    fun render(parts: List<Part>, width: Int, height: Int, background: Int = 0x000000, color: Int = 0xE6A000): IntArray {
        val pixels = IntArray(width * height) { background }
        // View rotation: 45° around Z, then tilted 35° so the top and two sides are visible.
        val yaw = Math.toRadians(-45.0)
        val pitch = Math.toRadians(55.0)
        val cy = cos(yaw).toFloat(); val sy = sin(yaw).toFloat()
        val cp = cos(pitch).toFloat(); val sp = sin(pitch).toFloat()

        // Transform all vertices to view space once.
        val tris = ArrayList<FloatArray>() // per triangle: 3 * (sx, sy, depth) + shade
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        val light = normalize(floatArrayOf(-0.4f, -0.6f, 0.9f))
        for (part in parts) {
            val m = part.matrix
            val d = part.mesh
            var i = 0
            while (i + 18 <= d.size) {
                val t = FloatArray(10)
                for (v in 0..2) {
                    val o = i + v * 6
                    val x = m[0] * d[o] + m[4] * d[o + 1] + m[8] * d[o + 2] + m[12]
                    val y = m[1] * d[o] + m[5] * d[o + 1] + m[9] * d[o + 2] + m[13]
                    val z = m[2] * d[o] + m[6] * d[o + 1] + m[10] * d[o + 2] + m[14]
                    // Rotate around Z (yaw), then around the view X axis (pitch).
                    val rx = cy * x - sy * y
                    val ry = sy * x + cy * y
                    val vx = rx
                    val vy = cp * ry + sp * z      // screen up
                    val vz = -sp * ry + cp * z     // towards the viewer
                    t[v * 3] = vx; t[v * 3 + 1] = vy; t[v * 3 + 2] = vz
                    minX = min(minX, vx); maxX = max(maxX, vx); minY = min(minY, vy); maxY = max(maxY, vy)
                }
                val nx = m[0] * d[i + 3] + m[4] * d[i + 4] + m[8] * d[i + 5]
                val ny = m[1] * d[i + 3] + m[5] * d[i + 4] + m[9] * d[i + 5]
                val nz = m[2] * d[i + 3] + m[6] * d[i + 4] + m[10] * d[i + 5]
                val n = normalize(floatArrayOf(nx, ny, nz))
                t[9] = 0.35f + 0.65f * abs(n[0] * light[0] + n[1] * light[1] + n[2] * light[2])
                tris += t
                i += 18
            }
        }
        if (tris.isEmpty()) return pixels

        // Fit the projected model into the image with a small margin, keeping aspect ratio.
        val margin = 0.08f
        val scale = min(width * (1 - 2 * margin) / max(maxX - minX, 1e-3f), height * (1 - 2 * margin) / max(maxY - minY, 1e-3f))
        val ox = width / 2f - (minX + maxX) / 2f * scale
        val oy = height / 2f + (minY + maxY) / 2f * scale
        val depth = FloatArray(width * height) { -Float.MAX_VALUE }
        val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF

        for (t in tris) {
            val x0 = ox + t[0] * scale; val y0 = oy - t[1] * scale
            val x1 = ox + t[3] * scale; val y1 = oy - t[4] * scale
            val x2 = ox + t[6] * scale; val y2 = oy - t[7] * scale
            val area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)
            if (abs(area) < 1e-9f) continue
            val shade = t[9]
            val px = (min(255f, r * shade).toInt() shl 16) or (min(255f, g * shade).toInt() shl 8) or min(255f, b * shade).toInt()
            val xa = max(0, kotlin.math.floor(min(x0, min(x1, x2))).toInt())
            val xb = min(width - 1, kotlin.math.ceil(max(x0, max(x1, x2))).toInt())
            val ya = max(0, kotlin.math.floor(min(y0, min(y1, y2))).toInt())
            val yb = min(height - 1, kotlin.math.ceil(max(y0, max(y1, y2))).toInt())
            for (py in ya..yb) {
                val fy = py + 0.5f
                for (pxl in xa..xb) {
                    val fx = pxl + 0.5f
                    val w0 = ((x1 - fx) * (y2 - fy) - (x2 - fx) * (y1 - fy)) / area
                    val w1 = ((x2 - fx) * (y0 - fy) - (x0 - fx) * (y2 - fy)) / area
                    val w2 = 1f - w0 - w1
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue
                    val z = w0 * t[2] + w1 * t[5] + w2 * t[8]
                    val idx = py * width + pxl
                    if (z > depth[idx]) {
                        depth[idx] = z
                        pixels[idx] = px
                    }
                }
            }
        }
        return pixels
    }

    private fun normalize(v: FloatArray): FloatArray {
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (l > 0) floatArrayOf(v[0] / l, v[1] / l, v[2] / l) else v
    }
}
