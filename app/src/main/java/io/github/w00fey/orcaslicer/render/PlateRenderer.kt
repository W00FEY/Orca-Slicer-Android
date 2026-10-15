package io.github.w00fey.orcaslicer.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** One placed copy of a model object, in world coordinates. */
data class SceneInstance(
    val objectIndex: Int,
    val instanceIndex: Int,
    val matrix: FloatArray,
    val bboxMin: FloatArray,
    val bboxMax: FloatArray,
)

/** Toolpaths of a sliced plate: 8 floats per segment and the end index of every layer. */
class Toolpaths(val segments: FloatArray, val layerEnds: IntArray, val layerZ: FloatArray) {
    val segmentCount get() = segments.size / FLOATS_PER_SEGMENT

    companion object {
        const val FLOATS_PER_SEGMENT = 8

        fun fromSegments(data: FloatArray): Toolpaths {
            val ends = ArrayList<Int>()
            val zs = ArrayList<Float>()
            val n = data.size / FLOATS_PER_SEGMENT
            var lastZ = Float.NaN
            for (i in 0 until n) {
                val z = data[i * FLOATS_PER_SEGMENT + 5]
                if (i > 0 && kotlin.math.abs(z - lastZ) > 1e-4f) ends.add(i)
                if (i == 0 || kotlin.math.abs(z - lastZ) > 1e-4f) zs.add(z)
                lastZ = z
            }
            if (n > 0) ends.add(n)
            return Toolpaths(data, ends.toIntArray(), zs.toFloatArray())
        }
    }
}

/**
 * Draws the build plate with either the model objects (plate mode) or the
 * sliced toolpaths (preview mode). State is updated from the UI thread; GPU
 * uploads are deferred to the GL thread.
 */
class PlateRenderer : GLSurfaceView.Renderer {
    val camera = Camera()

    @Volatile var previewMode = false
    @Volatile var visibleLayers = Int.MAX_VALUE
    @Volatile var selected: Pair<Int, Int>? = null
    @Volatile var dragOffset = floatArrayOf(0f, 0f)
    @Volatile var darkTheme = true

    // CPU-side scene, kept so it can be re-uploaded after the GL context is lost.
    @Volatile private var bedShape: List<Pair<Float, Float>> = emptyList()
    @Volatile private var instances: List<SceneInstance> = emptyList()
    private val meshes = HashMap<Int, FloatArray>()
    @Volatile private var toolpaths: Toolpaths? = null

    private val gpuMeshes = HashMap<Int, Pair<Int, Int>>() // object index -> (vbo, vertex count)
    private var meshesDirty = true
    private var bedDirty = true
    private var pathsDirty = true
    private val lock = Any()

    private var meshProgram = 0
    private var flatProgram = 0
    private var pathProgram = 0
    private var bedFillVbo = 0
    private var bedFillCount = 0
    private var bedGridVbo = 0
    private var bedGridCount = 0
    private var pathVbo = 0
    private var pathVao = 0
    private var pathCount = 0

    fun setBed(shape: List<Pair<Float, Float>>) = synchronized(lock) {
        bedShape = shape
        bedDirty = true
    }

    fun setMeshes(newMeshes: Map<Int, FloatArray>) = synchronized(lock) {
        meshes.clear()
        meshes.putAll(newMeshes)
        meshesDirty = true
    }

    fun setInstances(list: List<SceneInstance>) {
        instances = list
    }

    fun instances() = instances

    fun setToolpaths(paths: Toolpaths?) = synchronized(lock) {
        toolpaths = paths
        pathsDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        meshProgram = Gl.program(Gl.MESH_VS, Gl.MESH_FS)
        flatProgram = Gl.program(Gl.FLAT_VS, Gl.FLAT_FS)
        pathProgram = Gl.program(Gl.PATH_VS, Gl.PATH_FS)
        // Everything must be re-created in a new context.
        gpuMeshes.clear()
        bedFillVbo = 0; bedGridVbo = 0; pathVbo = 0; pathVao = 0
        meshesDirty = true; bedDirty = true; pathsDirty = true
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.resize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        synchronized(lock) { uploadPending() }
        if (darkTheme) GLES30.glClearColor(0.11f, 0.12f, 0.13f, 1f) else GLES30.glClearColor(0.93f, 0.94f, 0.95f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        camera.update()
        drawBed()
        if (previewMode) drawToolpaths() else drawMeshes()
    }

    private fun uploadPending() {
        if (bedDirty) {
            Gl.deleteBuffer(bedFillVbo); Gl.deleteBuffer(bedGridVbo)
            val (fill, grid) = buildBed(bedShape)
            bedFillVbo = Gl.createBuffer(fill); bedFillCount = fill.size / 3
            bedGridVbo = Gl.createBuffer(grid); bedGridCount = grid.size / 3
            bedDirty = false
        }
        if (meshesDirty) {
            gpuMeshes.values.forEach { Gl.deleteBuffer(it.first) }
            gpuMeshes.clear()
            for ((idx, data) in meshes) gpuMeshes[idx] = Gl.createBuffer(data) to data.size / 6
            meshesDirty = false
        }
        if (pathsDirty) {
            Gl.deleteBuffer(pathVbo); pathVbo = 0
            if (pathVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(pathVao), 0)
            pathVao = 0
            val paths = toolpaths
            pathCount = paths?.segmentCount ?: 0
            if (paths != null && pathCount > 0) {
                val vao = IntArray(1)
                GLES30.glGenVertexArrays(1, vao, 0)
                pathVao = vao[0]
                GLES30.glBindVertexArray(pathVao)
                pathVbo = Gl.createBuffer(paths.segments)
                val stride = Toolpaths.FLOATS_PER_SEGMENT * 4
                GLES30.glEnableVertexAttribArray(0)
                GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
                GLES30.glVertexAttribDivisor(0, 1)
                GLES30.glEnableVertexAttribArray(1)
                GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
                GLES30.glVertexAttribDivisor(1, 1)
                GLES30.glEnableVertexAttribArray(2)
                GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
                GLES30.glVertexAttribDivisor(2, 1)
                GLES30.glBindVertexArray(0)
            }
            pathsDirty = false
        }
    }

    private fun buildBed(shape: List<Pair<Float, Float>>): Pair<FloatArray, FloatArray> {
        val pts = shape.ifEmpty { listOf(0f to 0f, 220f to 0f, 220f to 220f, 0f to 220f) }
        val fill = ArrayList<Float>()
        // Triangle fan around the centroid (bed shapes are convex: rectangles or circles).
        val cx = pts.map { it.first }.average().toFloat()
        val cy = pts.map { it.second }.average().toFloat()
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            fill.addAll(listOf(cx, cy, -0.05f, a.first, a.second, -0.05f, b.first, b.second, -0.05f))
        }
        val grid = ArrayList<Float>()
        val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
        val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
        var x = (kotlin.math.ceil(minX / 10f) * 10f)
        while (x <= maxX) { grid.addAll(listOf(x, minY, 0f, x, maxY, 0f)); x += 10f }
        var y = (kotlin.math.ceil(minY / 10f) * 10f)
        while (y <= maxY) { grid.addAll(listOf(minX, y, 0f, maxX, y, 0f)); y += 10f }
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            grid.addAll(listOf(a.first, a.second, 0.02f, b.first, b.second, 0.02f))
        }
        return fill.toFloatArray() to grid.toFloatArray()
    }

    private fun drawBed() {
        GLES30.glUseProgram(flatProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(flatProgram, "uViewProj"), 1, false, camera.viewProjection, 0)
        val color = GLES30.glGetUniformLocation(flatProgram, "uColor")
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bedFillVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        if (darkTheme) GLES30.glUniform4f(color, 0.22f, 0.23f, 0.25f, 1f) else GLES30.glUniform4f(color, 0.80f, 0.81f, 0.83f, 1f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, bedFillCount)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bedGridVbo)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        if (darkTheme) GLES30.glUniform4f(color, 0.36f, 0.37f, 0.40f, 1f) else GLES30.glUniform4f(color, 0.62f, 0.63f, 0.66f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, bedGridCount)
    }

    private val tmpModel = FloatArray(16)

    private fun drawMeshes() {
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(meshProgram, "uViewProj"), 1, false, camera.viewProjection, 0)
        val uModel = GLES30.glGetUniformLocation(meshProgram, "uModel")
        val uColor = GLES30.glGetUniformLocation(meshProgram, "uColor")
        GLES30.glBindVertexArray(0)
        val sel = selected
        for (inst in instances) {
            val mesh = gpuMeshes[inst.objectIndex] ?: continue
            System.arraycopy(inst.matrix, 0, tmpModel, 0, 16)
            val isSelected = sel != null && sel.first == inst.objectIndex && sel.second == inst.instanceIndex
            if (isSelected) {
                val d = dragOffset
                tmpModel[12] += d[0]; tmpModel[13] += d[1]
            }
            GLES30.glUniformMatrix4fv(uModel, 1, false, tmpModel, 0)
            if (isSelected) GLES30.glUniform4f(uColor, 0.27f, 0.75f, 0.47f, 1f)
            else GLES30.glUniform4f(uColor, 0.0f, 0.59f, 0.53f, 1f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, mesh.first)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, mesh.second)
        }
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun drawToolpaths() {
        val paths = toolpaths ?: return
        if (pathVao == 0 || paths.layerEnds.isEmpty()) return
        val layer = visibleLayers.coerceIn(1, paths.layerEnds.size)
        val end = paths.layerEnds[layer - 1]
        val currentStart = if (layer >= 2) paths.layerEnds[layer - 2] else 0
        GLES30.glUseProgram(pathProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(pathProgram, "uViewProj"), 1, false, camera.viewProjection, 0)
        GLES30.glUniform4fv(GLES30.glGetUniformLocation(pathProgram, "uRoleColors"), ROLE_COLORS.size / 4, ROLE_COLORS, 0)
        // Dim everything below the current layer only while scrubbing through layers.
        val highlightFrom = if (layer < paths.layerEnds.size) currentStart else 0
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pathProgram, "uHighlightFrom"), highlightFrom)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(pathProgram, "uOffset"), 0)
        GLES30.glBindVertexArray(pathVao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, end)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        /** Feature colours, indexed by libslic3r's ExtrusionRole (matches the desktop preview). */
        val ROLE_COLORS = floatArrayOf(
            0.90f, 0.70f, 0.70f, 1f, // none
            1.00f, 0.90f, 0.30f, 1f, // inner wall
            1.00f, 0.49f, 0.22f, 1f, // outer wall
            0.12f, 0.12f, 1.00f, 1f, // overhang wall
            0.69f, 0.19f, 0.16f, 1f, // sparse infill
            0.59f, 0.33f, 0.80f, 1f, // internal solid infill
            0.94f, 0.25f, 0.25f, 1f, // top surface
            0.40f, 0.36f, 0.78f, 1f, // bottom surface
            1.00f, 0.55f, 0.41f, 1f, // ironing
            0.30f, 0.50f, 0.73f, 1f, // bridge
            0.30f, 0.40f, 0.63f, 1f, // internal bridge
            1.00f, 1.00f, 1.00f, 1f, // gap infill
            0.00f, 0.53f, 0.43f, 1f, // skirt
            0.00f, 0.23f, 0.43f, 1f, // brim
            0.00f, 1.00f, 0.00f, 1f, // support
            0.00f, 0.50f, 0.00f, 1f, // support interface
            0.00f, 0.25f, 0.00f, 1f, // support transition
            0.70f, 0.89f, 0.67f, 1f, // prime tower
            0.37f, 0.82f, 0.58f, 1f, // custom
            0.37f, 0.82f, 0.58f, 1f, // mixed
        )

        val ROLE_NAMES = listOf(
            "Other", "Inner wall", "Outer wall", "Overhang wall", "Sparse infill", "Internal solid infill",
            "Top surface", "Bottom surface", "Ironing", "Bridge", "Internal bridge", "Gap infill",
            "Skirt", "Brim", "Support", "Support interface", "Support transition", "Prime tower", "Custom", "Mixed",
        )

        fun matrixFrom(values: List<Double>): FloatArray =
            FloatArray(16) { i -> values.getOrElse(i) { if (i % 5 == 0) 1.0 else 0.0 }.toFloat() }

        fun identity() = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    }
}
