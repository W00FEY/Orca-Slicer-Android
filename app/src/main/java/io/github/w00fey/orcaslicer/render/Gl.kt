package io.github.w00fey.orcaslicer.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

internal object Gl {
    fun program(vertex: String, fragment: String): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Program link failed: " + GLES30.glGetProgramInfoLog(p) }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Shader compile failed: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data)
            position(0)
        }

    fun createBuffer(data: FloatArray): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, floatBuffer(data), GLES30.GL_STATIC_DRAW)
        return ids[0]
    }

    fun deleteBuffer(id: Int) {
        if (id != 0) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
    }

    const val MESH_VS = """#version 300 es
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        uniform mat4 uViewProj;
        uniform mat4 uModel;
        out vec3 vNormal;
        void main() {
            vNormal = normalize(mat3(uModel) * aNormal);
            gl_Position = uViewProj * uModel * vec4(aPos, 1.0);
        }"""

    const val MESH_FS = """#version 300 es
        precision mediump float;
        in vec3 vNormal;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 l1 = normalize(vec3(-0.4, -0.6, 0.9));
            vec3 l2 = normalize(vec3(0.6, 0.5, 0.3));
            float d = 0.35 + 0.55 * abs(dot(n, l1)) + 0.2 * max(dot(n, l2), 0.0);
            fragColor = vec4(uColor.rgb * d, uColor.a);
        }"""

    const val FLAT_VS = """#version 300 es
        layout(location = 0) in vec3 aPos;
        uniform mat4 uViewProj;
        void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }"""

    const val FLAT_FS = """#version 300 es
        precision mediump float;
        uniform vec4 uColor;
        out vec4 fragColor;
        void main() { fragColor = uColor; }"""

    // One instance per extrusion segment; the four strip vertices are generated
    // from gl_VertexID and form a flat ribbon as wide as the extrusion.
    const val PATH_VS = """#version 300 es
        layout(location = 0) in vec3 aStart;
        layout(location = 1) in vec3 aEnd;
        layout(location = 2) in vec2 aWidthRole;
        uniform mat4 uViewProj;
        uniform vec4 uRoleColors[20];
        uniform int uHighlightFrom;
        uniform int uOffset;
        out vec4 vColor;
        out float vAcross;
        void main() {
            vec2 dir = aEnd.xy - aStart.xy;
            float len = length(dir);
            dir = len > 1e-6 ? dir / len : vec2(1.0, 0.0);
            vec2 side = vec2(-dir.y, dir.x) * (aWidthRole.x * 0.5);
            int corner = gl_VertexID;
            vec3 base = (corner < 2) ? aStart : aEnd;
            // Extend ends by half a width so consecutive segments overlap at corners.
            base.xy += dir * ((corner < 2) ? -0.5 : 0.5) * aWidthRole.x * 0.9;
            float s = (corner == 0 || corner == 2) ? -1.0 : 1.0;
            vAcross = s;
            vec3 p = base + vec3(side * s, 0.0);
            int role = int(aWidthRole.y + 0.5);
            vec4 c = uRoleColors[clamp(role, 0, 19)];
            bool current = (gl_InstanceID + uOffset) >= uHighlightFrom;
            vColor = current ? c : vec4(c.rgb * 0.55, c.a);
            gl_Position = uViewProj * vec4(p, 1.0);
        }"""

    const val PATH_FS = """#version 300 es
        precision mediump float;
        in vec4 vColor;
        in float vAcross;
        out vec4 fragColor;
        void main() {
            float shade = 1.0 - 0.45 * vAcross * vAcross;
            fragColor = vec4(vColor.rgb * shade, 1.0);
        }"""
}
