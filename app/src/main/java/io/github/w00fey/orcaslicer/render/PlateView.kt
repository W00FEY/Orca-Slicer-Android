package io.github.w00fey.orcaslicer.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector

/**
 * 3D plate view.
 *  - one finger: orbit
 *  - two fingers: pinch to zoom, drag to pan
 *  - tap: select an object
 *  - long-press on an object, then drag: move it on the bed
 */
@SuppressLint("ViewConstructor")
class PlateView(
    context: Context,
    val renderer: PlateRenderer,
) : GLSurfaceView(context) {

    /** Called with (object, instance) on tap, or null when tapping empty space. */
    var onSelect: (Pair<Int, Int>?) -> Unit = {}
    /** Called when a drag-move of an instance finishes, with the world XY delta. */
    var onMoved: (objectIndex: Int, instanceIndex: Int, dx: Float, dy: Float) -> Unit = { _, _, _, _ -> }

    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var multiTouch = false
    private var dragging: SceneInstance? = null
    private var dragStart: Pair<Float, Float>? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            renderer.camera.zoom(detector.scaleFactor)
            requestRender()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val hit = pick(e.x, e.y)
            onSelect(hit?.let { it.objectIndex to it.instanceIndex })
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (renderer.previewMode || multiTouch) return
            val hit = pick(e.x, e.y) ?: return
            onSelect(hit.objectIndex to hit.instanceIndex)
            renderer.selected = hit.objectIndex to hit.instanceIndex
            dragging = hit
            dragStart = Camera.hitPlaneZ(renderer.camera.ray(e.x, e.y), 0f)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            requestRender()
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            frameScene()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    var bedBounds = floatArrayOf(0f, 0f, 220f, 220f, 250f)

    fun frameScene() {
        renderer.camera.frame(bedBounds[0], bedBounds[1], bedBounds[2], bedBounds[3], bedBounds[4])
        requestRender()
    }

    private fun pick(x: Float, y: Float): SceneInstance? {
        if (renderer.previewMode) return null
        val ray = renderer.camera.ray(x, y)
        return renderer.instances()
            .mapNotNull { inst -> Camera.hitBox(ray, inst.bboxMin, inst.bboxMax)?.let { it to inst } }
            .minByOrNull { it.first }?.second
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                lastFocusX = focusX(event); lastFocusY = focusY(event)
                cancelDrag()
            }
            MotionEvent.ACTION_MOVE -> {
                val drag = dragging
                if (drag != null && !multiTouch) {
                    val start = dragStart
                    val now = Camera.hitPlaneZ(renderer.camera.ray(event.x, event.y), 0f)
                    if (start != null && now != null) {
                        renderer.dragOffset = floatArrayOf(now.first - start.first, now.second - start.second)
                    }
                } else if (event.pointerCount >= 2) {
                    val fx = focusX(event); val fy = focusY(event)
                    renderer.camera.pan(fx - lastFocusX, fy - lastFocusY)
                    lastFocusX = fx; lastFocusY = fy
                } else if (!multiTouch) {
                    renderer.camera.orbit(event.x - lastX, event.y - lastY)
                }
                lastX = event.x; lastY = event.y
                requestRender()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor so the remaining finger doesn't cause a jump.
                val remaining = if (event.actionIndex == 0) 1 else 0
                lastX = event.getX(remaining); lastY = event.getY(remaining)
                if (event.pointerCount - 1 >= 2) {
                    lastFocusX = focusX(event); lastFocusY = focusY(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val drag = dragging
                if (drag != null && event.actionMasked == MotionEvent.ACTION_UP) {
                    val d = renderer.dragOffset
                    if (d[0] != 0f || d[1] != 0f) onMoved(drag.objectIndex, drag.instanceIndex, d[0], d[1])
                    else renderer.dragOffset = floatArrayOf(0f, 0f)
                } else {
                    renderer.dragOffset = floatArrayOf(0f, 0f)
                }
                dragging = null
                dragStart = null
                requestRender()
            }
        }
        return true
    }

    private fun cancelDrag() {
        dragging = null
        dragStart = null
        renderer.dragOffset = floatArrayOf(0f, 0f)
    }

    private fun focusX(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getX(i)
        return s / e.pointerCount
    }

    private fun focusY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }
}
