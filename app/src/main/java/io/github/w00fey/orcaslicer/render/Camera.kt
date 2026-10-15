package io.github.w00fey.orcaslicer.render

import android.opengl.Matrix
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Orbit camera around a target point; Z is up, like the printer's coordinate system. */
class Camera {
    var targetX = 0f
    var targetY = 0f
    var targetZ = 0f
    var distance = 400f
    var yawDeg = -45f
    var pitchDeg = 35f
    private var width = 1
    private var height = 1

    val view = FloatArray(16)
    val projection = FloatArray(16)
    val viewProjection = FloatArray(16)
    private val inverse = FloatArray(16)

    fun resize(w: Int, h: Int) {
        width = max(1, w)
        height = max(1, h)
    }

    fun frame(minX: Float, minY: Float, maxX: Float, maxY: Float, maxZ: Float) {
        targetX = (minX + maxX) / 2
        targetY = (minY + maxY) / 2
        targetZ = 0f
        val extent = max(max(maxX - minX, maxY - minY), maxZ * 0.6f)
        distance = extent * 1.6f
        yawDeg = -45f
        pitchDeg = 35f
    }

    fun orbit(dxPx: Float, dyPx: Float) {
        yawDeg -= dxPx * 0.3f
        pitchDeg = (pitchDeg + dyPx * 0.3f).coerceIn(-89f, 89f)
    }

    fun zoom(factor: Float) {
        distance = (distance / factor).coerceIn(10f, 3000f)
    }

    /** Pans in the view plane; pixel deltas are converted to world units at the target distance. */
    fun pan(dxPx: Float, dyPx: Float) {
        val scale = distance * 1.1f / height
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        // Camera right and up vectors.
        val rx = -sin(yaw).toFloat()
        val ry = cos(yaw).toFloat()
        val ux = (-cos(yaw) * sin(pitch)).toFloat()
        val uy = (-sin(yaw) * sin(pitch)).toFloat()
        val uz = cos(pitch).toFloat()
        targetX += (-dxPx * rx + dyPx * ux) * scale
        targetY += (-dxPx * ry + dyPx * uy) * scale
        targetZ += dyPx * uz * scale
    }

    fun update() {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val ex = targetX + (distance * cos(pitch) * cos(yaw)).toFloat()
        val ey = targetY + (distance * cos(pitch) * sin(yaw)).toFloat()
        val ez = targetZ + (distance * sin(pitch)).toFloat()
        Matrix.setLookAtM(view, 0, ex, ey, ez, targetX, targetY, targetZ, 0f, 0f, 1f)
        val aspect = width.toFloat() / height
        Matrix.perspectiveM(projection, 0, 40f, aspect, max(1f, distance / 100f), distance * 10f + 1000f)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, view, 0)
    }

    /** Returns a world-space ray (origin xyz, direction xyz) through a screen pixel. */
    fun ray(px: Float, py: Float): FloatArray {
        update()
        Matrix.invertM(inverse, 0, viewProjection, 0)
        val nx = 2f * px / width - 1f
        val ny = 1f - 2f * py / height
        val near = unproject(nx, ny, -1f)
        val far = unproject(nx, ny, 1f)
        return floatArrayOf(near[0], near[1], near[2], far[0] - near[0], far[1] - near[1], far[2] - near[2])
    }

    private fun unproject(x: Float, y: Float, z: Float): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, inverse, 0, floatArrayOf(x, y, z, 1f), 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    companion object {
        /** Intersection of a ray with the plane z = [z], or null when parallel/behind. */
        fun hitPlaneZ(ray: FloatArray, z: Float): Pair<Float, Float>? {
            if (kotlin.math.abs(ray[5]) < 1e-6f) return null
            val t = (z - ray[2]) / ray[5]
            if (t < 0) return null
            return (ray[0] + ray[3] * t) to (ray[1] + ray[4] * t)
        }

        /** Distance along the ray to an axis-aligned box, or null if missed. */
        fun hitBox(ray: FloatArray, minB: FloatArray, maxB: FloatArray): Float? {
            var tMin = 0f
            var tMax = Float.MAX_VALUE
            for (i in 0..2) {
                val o = ray[i]
                val d = ray[3 + i]
                if (kotlin.math.abs(d) < 1e-9f) {
                    if (o < minB[i] || o > maxB[i]) return null
                } else {
                    var t1 = (minB[i] - o) / d
                    var t2 = (maxB[i] - o) / d
                    if (t1 > t2) t1 = t2.also { t2 = t1 }
                    tMin = max(tMin, t1)
                    tMax = min(tMax, t2)
                    if (tMin > tMax) return null
                }
            }
            return tMin
        }
    }
}
