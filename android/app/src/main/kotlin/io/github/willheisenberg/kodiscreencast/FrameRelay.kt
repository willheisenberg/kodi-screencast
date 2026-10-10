package io.github.willheisenberg.kodiscreencast

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch

/**
 * Reicht das Bildschirmbild im festen Takt an den Encoder weiter.
 *
 * Android liefert nur dann ein neues Bild, wenn sich auf dem Bildschirm
 * etwas ändert. Kodi soll aber gleichmäßig versorgt werden; deshalb landet
 * der Bildschirm in einer Textur, und ein Zeitgeber zeichnet deren letzten
 * Stand im festen Takt auf die Eingabefläche des Encoders.
 */
class FrameRelay(output: Surface, width: Int, height: Int, fps: Int) {
    private val thread = HandlerThread("screencast.relay").apply { start() }
    private val handler = Handler(thread.looper)
    private val intervalMs = 1000.0 / fps

    /** Hierauf spiegelt Android den Bildschirm. */
    lateinit var surface: Surface
        private set

    // Alles Weitere gehört dem Thread des Relays.
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE
    private lateinit var texture: SurfaceTexture
    private var matrixLocation = 0
    private val matrix = FloatArray(16)
    private var pending = false
    private var hasFrame = false
    private var released = false
    private var due = 0.0

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            val now = SystemClock.uptimeMillis()
            // Nach einem Hänger nicht aufholen, sondern neu ansetzen.
            due = if (due < now - intervalMs) now + intervalMs else due + intervalMs
            handler.postAtTime(this, due.toLong())
            draw()
        }
    }

    init {
        val ready = CountDownLatch(1)
        var failure: RuntimeException? = null
        handler.post {
            try {
                setUp(output, width, height)
            } catch (e: RuntimeException) {
                failure = e
            }
            ready.countDown()
        }
        ready.await()
        failure?.let {
            release()
            throw KodiException("Die Bildübergabe an den Encoder lässt sich nicht einrichten (${it.message}).")
        }
        handler.post {
            due = SystemClock.uptimeMillis().toDouble()
            tick.run()
        }
    }

    fun release() {
        handler.post {
            released = true
            handler.removeCallbacks(tick)
            if (::surface.isInitialized) surface.release()
            if (::texture.isInitialized) texture.release()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglReleaseThread()
            }
        }
        thread.quitSafely()
        thread.join(2000)
    }

    private fun setUp(output: Surface, width: Int, height: Int) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize" }
        val configAttributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(
            EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) &&
                count[0] > 0
        ) { "eglChooseConfig" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
        window = EGL14.eglCreateWindowSurface(
            display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
        check(EGL14.eglMakeCurrent(display, window, window, context)) { "eglMakeCurrent" }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glUseProgram(program)
        matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix")
        attribute(program, "aPosition", floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        attribute(program, "aTexCoord", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, textures[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glViewport(0, 0, width, height)

        texture = SurfaceTexture(textures[0])
        texture.setDefaultBufferSize(width, height)
        texture.setOnFrameAvailableListener({ pending = true }, handler)
        surface = Surface(texture)
    }

    private fun draw() {
        if (pending) {
            pending = false
            texture.updateTexImage()
            texture.getTransformMatrix(matrix)
            hasFrame = true
        }
        // Vor dem ersten Bild gäbe es nur Schwarz zu kodieren.
        if (!hasFrame) return
        GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(display, window, System.nanoTime())
        EGL14.eglSwapBuffers(display, window)
    }

    private fun shader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        check(compiled[0] != 0) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun attribute(program: Int, name: String, values: FloatArray) {
        val buffer = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().put(values)
        buffer.position(0)
        val location = GLES20.glGetAttribLocation(program, name)
        GLES20.glEnableVertexAttribArray(location)
        GLES20.glVertexAttribPointer(location, 2, GLES20.GL_FLOAT, false, 0, buffer)
    }

    private companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * aTexCoord).xy;
            }
        """
        const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}
