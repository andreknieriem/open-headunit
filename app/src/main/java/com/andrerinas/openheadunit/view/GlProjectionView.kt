package com.andrerinas.openheadunit.view

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import com.andrerinas.openheadunit.decoder.SoftwareYuvFrameSink
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class GlProjectionView(context: Context) : GLSurfaceView(context), IProjectionView, SoftwareYuvFrameSink {

    private val renderer: VideoRenderer
    private val callbacks = mutableListOf<IProjectionView.Callbacks>()

    init {
        setEGLContextClientVersion(2)
        renderer = VideoRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        preserveEGLContextOnPause = true // Keep context alive if possible
    }

    override fun addCallback(callback: IProjectionView.Callbacks) {
        callbacks.add(callback)
        renderer.getSurface()?.let {
            if (it.isValid) {
                callback.onSurfaceCreated(it)
                callback.onSurfaceChanged(it, width, height)
            }
        }
    }

    override fun removeCallback(callback: IProjectionView.Callbacks) {
        callbacks.remove(callback)
    }

    fun getSurface(): Surface? = renderer.getSurface()
    fun isSurfaceValid(): Boolean = renderer.getSurface()?.isValid == true

    override fun lastFrameDrawnMs(): Long = renderer.lastFrameDrawnMs

    override fun longFrameEvents(): Long = renderer.longFrameEvents

    override fun setVideoSize(width: Int, height: Int) {
        AppLog.i("GlProjectionView setVideoSize: ${width}x$height")
        renderer.updateBufferSize(width, height)
        // ProjectionViewScaler removed, we use setVideoScale via Matrix
    }

    override fun setVideoScale(scaleX: Float, scaleY: Float) {
        renderer.setScale(scaleX, scaleY)
    }

    fun setDesaturation(value: Float) {
        renderer.setDesaturation(value)
    }

    override fun renderYuv420Frame(
        width: Int,
        height: Int,
        yPlane: ByteBuffer,
        yStride: Int,
        uPlane: ByteBuffer,
        uStride: Int,
        vPlane: ByteBuffer,
        vStride: Int
    ): Boolean {
        if (renderer.renderYuv420FrameDirect(width, height, yPlane, yStride, uPlane, uStride, vPlane, vStride)) {
            return true
        }
        return renderer.queueYuv420Frame(width, height, yPlane, yStride, uPlane, uStride, vPlane, vStride)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        renderer.release()
    }

    private inner class VideoRenderer : Renderer, SurfaceTexture.OnFrameAvailableListener {
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        
        private var textureId: Int = 0
        private var program: Int = 0
        private var yuvProgram: Int = 0
        private val yuvTextureIds = IntArray(3)
        
        private var mVPMatrix = FloatArray(16)
        private var sSTMatrix = FloatArray(16)

        private var mScaleX = 1.0f
        private var mScaleY = 1.0f

        /**
         * Resizes the texture's default buffer, on the GL thread.
         *
         * Called from the main thread when the video dimensions change, and it used to touch the
         * SurfaceTexture straight from there while the GL thread was inside updateTexImage.
         */
        fun updateBufferSize(width: Int, height: Int) {
            queueEvent { surfaceTexture?.setDefaultBufferSize(width, height) }
        }

        fun setScale(x: Float, y: Float) {
            mScaleX = x
            mScaleY = y
        }

        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            uniform mat4 uMVPMatrix;
            uniform mat4 uSTMatrix;
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = (uSTMatrix * aTextureCoord).xy;
            }
        """

        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            uniform float uDesaturation;
            void main() {
                vec4 color = texture2D(sTexture, vTextureCoord);
                float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(mix(color.rgb, vec3(gray), uDesaturation), color.a);
            }
        """

        private val yuvFragmentShaderCode = """
            precision mediump float;
            varying vec2 vTextureCoord;
            uniform sampler2D yTexture;
            uniform sampler2D uTexture;
            uniform sampler2D vTexture;
            uniform float uDesaturation;
            uniform float yTextureScaleX;
            uniform float uTextureScaleX;
            uniform float vTextureScaleX;
            void main() {
                float y = 1.164383 * (texture2D(yTexture, vec2(vTextureCoord.x * yTextureScaleX, vTextureCoord.y)).r - 0.0625);
                float u = texture2D(uTexture, vec2(vTextureCoord.x * uTextureScaleX, vTextureCoord.y)).r - 0.5;
                float v = texture2D(vTexture, vec2(vTextureCoord.x * vTextureScaleX, vTextureCoord.y)).r - 0.5;
                vec3 rgb;
                rgb.r = y + 1.792741 * v;
                rgb.g = y - 0.213249 * u - 0.532909 * v;
                rgb.b = y + 2.112402 * u;
                rgb = clamp(rgb, 0.0, 1.0);
                float gray = dot(rgb, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(mix(rgb, vec3(gray), uDesaturation), 1.0);
            }
        """

        private var vertexBuffer: FloatBuffer? = null
        private val squareCoords = floatArrayOf(
            -1.0f, -1.0f, 0.0f, // bottom left
             1.0f, -1.0f, 0.0f, // bottom right
            -1.0f,  1.0f, 0.0f, // top left
             1.0f,  1.0f, 0.0f  // top right
        )
        
        private val textureCoords = floatArrayOf(
            0f, 0f, 
            1f, 0f, 
            0f, 1f, 
            1f, 1f
        )
        private var textureBuffer: FloatBuffer? = null
        private val yuvTextureCoords = floatArrayOf(
            0f, 1f,
            1f, 1f,
            0f, 0f,
            1f, 0f
        )
        private var yuvTextureBuffer: FloatBuffer? = null

        private var maPositionHandle = 0
        private var maTextureHandle = 0
        private var muMVPMatrixHandle = 0
        private var muSTMatrixHandle = 0
        private var muDesaturationHandle = 0
        private var yuvPositionHandle = 0
        private var yuvTextureHandle = 0
        private var yuvMVPMatrixHandle = 0
        private var yuvSTMatrixHandle = 0
        private var yuvDesaturationHandle = 0
        private var yTextureHandle = 0
        private var uTextureHandle = 0
        private var vTextureHandle = 0
        private var yTextureScaleHandle = 0
        private var uTextureScaleHandle = 0
        private var vTextureScaleHandle = 0

        @Volatile
        private var desaturation = 0.0f

        // elapsedRealtime() of the last frame the GL thread actually consumed and drew.
        // Read by the projection watchdog to detect a stalled GL consumer (issue #650).
        @Volatile
        var lastFrameDrawnMs: Long = 0L

        // Count of abnormally long gaps between consecutive draws (issue #650). On MediaTek the
        // GL thread collapses to a few fps with single frames taking up to ~2s rather than fully
        // freezing, which a time-gap check misses; this counts those slow frames instead.
        @Volatile
        var longFrameEvents: Long = 0L
        private var prevDrawMs: Long = 0L
        private val longFrameThresholdMs = 1200L

        private fun markFrameDrawn() {
            val now = SystemClock.elapsedRealtime()
            val prev = prevDrawMs
            if (prev != 0L && now - prev > longFrameThresholdMs) {
                longFrameEvents++
            }
            prevDrawMs = now
            lastFrameDrawnMs = now
        }

        fun setDesaturation(value: Float) {
            desaturation = value.coerceIn(0f, 1f)
        }

        private var updateSurface = false
        private var hasYuvFrame = false
        private var pendingYuvFrame = false

        /**
         * Which of the two frame sources produced something most recently.
         *
         * This renderer can be fed two ways: the decoder renders into [surfaceTexture] and the GL
         * thread samples it as an external texture, or the bundled software decoder hands over YUV
         * planes and the GL thread uploads them itself. [hasYuvFrame] used to latch true on the first
         * YUV frame and never clear, so once a single software-decoded frame had been drawn the
         * external-texture path was dead for the life of the renderer - and any later hardware output
         * went into the SurfaceTexture and was never sampled. Turning software decoding off without
         * reconnecting left a frozen or black picture.
         *
         * Comparing sequence numbers instead of latching a flag makes the choice self-healing: the
         * source that is actually producing frames wins, and a switch either way is corrected on the
         * next draw without anything having to tell the renderer it happened.
         */
        private var frameSequence = 0L
        private var lastOesFrameSeq = 0L
        private var lastYuvFrameSeq = 0L

        /**
         * Set once the direct upload has missed its deadline, after which only the staged path is used.
         *
         * The direct path hands the codec's own buffers to the GL thread and waits up to
         * [directUploadTimeoutMs] for the upload to finish, which saves a CPU copy of three planes when
         * the GL thread is keeping up. When it is not, the caller - the decoder thread - pays the full
         * timeout *and then does the staged copy anyway*, which is the worst of both and was happening
         * per frame. A GL thread slow enough to miss the deadline once is not going to start making it,
         * so stop asking: the cost becomes one missed deadline a session instead of one per frame.
         */
        @Volatile
        private var directUploadTimedOut = false
        private var yuvWidth = 0
        private var yuvHeight = 0
        private val uploadedPlaneWidths = IntArray(3)
        private val uploadedPlaneHeights = IntArray(3)
        private var yTextureScaleX = 1f
        private var uTextureScaleX = 1f
        private var vTextureScaleX = 1f
        private var yPlaneBuffer: ByteBuffer? = null
        private var uPlaneBuffer: ByteBuffer? = null
        private var vPlaneBuffer: ByteBuffer? = null
        private var loggedFirstYuvFrame = false
        private var loggedFirstDirectYuvFrame = false
        private var loggedFirstYuvUpload = false
        private var loggedFirstYuvDraw = false
        private val directPending = 0
        private val directRunning = 1
        private val directDone = 2
        private val directCancelled = 3
        private val directUploadTimeoutMs = 50L

        fun getSurface(): Surface? = surface

        fun queueYuv420Frame(
            width: Int,
            height: Int,
            yPlane: ByteBuffer,
            yStride: Int,
            uPlane: ByteBuffer,
            uStride: Int,
            vPlane: ByteBuffer,
            vStride: Int
        ): Boolean {
            if (width <= 0 || height <= 0) return false
            synchronized(this) {
                ensureYuvBuffers(width, height)
                copyPlane(yPlane, yStride, yPlaneBuffer!!, width, height)
                copyPlane(uPlane, uStride, uPlaneBuffer!!, width / 2, height / 2)
                copyPlane(vPlane, vStride, vPlaneBuffer!!, width / 2, height / 2)
                yuvWidth = width
                yuvHeight = height
                hasYuvFrame = true
                pendingYuvFrame = true
                lastYuvFrameSeq = ++frameSequence
                if (!loggedFirstYuvFrame) {
                    loggedFirstYuvFrame = true
                    AppLog.i("GlProjectionView: first YUV420 frame queued ${width}x$height strides=$yStride/$uStride/$vStride")
                }
            }
            requestRender()
            return true
        }

        fun renderYuv420FrameDirect(
            width: Int,
            height: Int,
            yPlane: ByteBuffer,
            yStride: Int,
            uPlane: ByteBuffer,
            uStride: Int,
            vPlane: ByteBuffer,
            vStride: Int
        ): Boolean {
            if (width <= 0 || height <= 0) return false
            if (directUploadTimedOut) return false
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            if (yStride < width || uStride < chromaWidth || vStride < chromaWidth) return false
            if (!hasPlaneCapacity(yPlane, yStride, height) ||
                !hasPlaneCapacity(uPlane, uStride, chromaHeight) ||
                !hasPlaneCapacity(vPlane, vStride, chromaHeight)) {
                return false
            }

            val state = AtomicInteger(directPending)
            val completed = CountDownLatch(1)
            val accepted = BooleanArray(1)
            val ySource = yPlane.duplicate()
            val uSource = uPlane.duplicate()
            val vSource = vPlane.duplicate()

            return try {
                queueEvent {
                    if (!state.compareAndSet(directPending, directRunning)) {
                        completed.countDown()
                        return@queueEvent
                    }
                    try {
                        accepted[0] = uploadDirectYuvFrame(
                            width,
                            height,
                            ySource,
                            yStride,
                            uSource,
                            uStride,
                            vSource,
                            vStride
                        )
                    } catch (e: Exception) {
                        AppLog.e("GlProjectionView: direct YUV upload failed", e)
                        accepted[0] = false
                    } finally {
                        state.set(directDone)
                        completed.countDown()
                    }
                }
                requestRender()

                if (!completed.await(directUploadTimeoutMs, TimeUnit.MILLISECONDS)) {
                    if (state.compareAndSet(directPending, directCancelled)) {
                        directUploadTimedOut = true
                        AppLog.w(
                            "GlProjectionView: direct YUV upload missed its ${directUploadTimeoutMs}ms deadline; " +
                                "using the staged copy for the rest of this session"
                        )
                        return false
                    }
                    completed.await()
                }
                accepted[0]
            } catch (e: Exception) {
                AppLog.w("GlProjectionView: direct YUV upload unavailable: ${e.message}")
                false
            }
        }

        private fun ensureYuvBuffers(width: Int, height: Int) {
            val ySize = width * height
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            val chromaSize = chromaWidth * chromaHeight
            if (yPlaneBuffer?.capacity() != ySize) {
                yPlaneBuffer = ByteBuffer.allocateDirect(ySize)
            }
            if (uPlaneBuffer?.capacity() != chromaSize) {
                uPlaneBuffer = ByteBuffer.allocateDirect(chromaSize)
            }
            if (vPlaneBuffer?.capacity() != chromaSize) {
                vPlaneBuffer = ByteBuffer.allocateDirect(chromaSize)
            }
        }

        private fun copyPlane(source: ByteBuffer, sourceStride: Int, dest: ByteBuffer, width: Int, height: Int) {
            dest.clear()
            val duplicate = source.duplicate()
            if (sourceStride == width) {
                duplicate.position(0)
                duplicate.limit(width * height)
                dest.put(duplicate)
                dest.position(0)
                return
            }
            for (y in 0 until height) {
                val rowStart = y * sourceStride
                duplicate.position(rowStart)
                duplicate.limit(rowStart + width)
                dest.put(duplicate)
            }
            dest.position(0)
        }

        private fun hasPlaneCapacity(buffer: ByteBuffer, stride: Int, height: Int): Boolean {
            return stride > 0 && height > 0 && buffer.capacity() >= stride * height
        }

        private fun uploadDirectYuvFrame(
            width: Int,
            height: Int,
            yPlane: ByteBuffer,
            yStride: Int,
            uPlane: ByteBuffer,
            uStride: Int,
            vPlane: ByteBuffer,
            vStride: Int
        ): Boolean {
            val chromaWidth = width / 2
            val chromaHeight = height / 2
            if (yuvProgram == 0 || yuvTextureIds.any { it == 0 }) return false

            yPlane.position(0)
            yPlane.limit(yStride * height)
            uPlane.position(0)
            uPlane.limit(uStride * chromaHeight)
            vPlane.position(0)
            vPlane.limit(vStride * chromaHeight)

            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            uploadYuvPlane(0, yuvTextureIds[0], yPlane, yStride, height)
            uploadYuvPlane(1, yuvTextureIds[1], uPlane, uStride, chromaHeight)
            uploadYuvPlane(2, yuvTextureIds[2], vPlane, vStride, chromaHeight)

            // Under the monitor: queueYuv420Frame and drawYuvFrame read exactly these fields under
            // it, and this path wrote them all bare. The GL uploads above stay outside, so the
            // decoder thread is not held for the length of a texture upload.
            synchronized(this) {
                yuvWidth = width
                yuvHeight = height
                yTextureScaleX = width.toFloat() / yStride.toFloat()
                uTextureScaleX = chromaWidth.toFloat() / uStride.toFloat()
                vTextureScaleX = chromaWidth.toFloat() / vStride.toFloat()
                hasYuvFrame = true
                pendingYuvFrame = false
                lastYuvFrameSeq = ++frameSequence
            }

            if (!loggedFirstDirectYuvFrame) {
                loggedFirstDirectYuvFrame = true
                AppLog.i("GlProjectionView: first direct YUV420 upload ${width}x$height strides=$yStride/$uStride/$vStride")
            }
            return true
        }

        /**
         * Tears the render target down, telling the decoder before the Surface is actually gone.
         *
         * The notification has to be posted to the main looper because that is where every other
         * surface callback is delivered from, and it used to be posted *and then* followed by two
         * synchronous release() calls - so for one looper turn the decoder still held a Surface that
         * had already been released. Releasing inside the same post closes that window; the fields are
         * cleared first so a second call cannot release twice.
         */
        fun release() {
            val doomedSurface = surface
            val doomedTexture = surfaceTexture
            surface = null
            surfaceTexture = null
            if (doomedSurface == null && doomedTexture == null) return
            Handler(Looper.getMainLooper()).post {
                doomedSurface?.let { s -> callbacks.forEach { it.onSurfaceDestroyed(s) } }
                doomedSurface?.release()
                doomedTexture?.release()
            }
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            AppLog.i("GlProjectionView: onSurfaceCreated (GL Context)")
            
            // Setup texture
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST.toFloat())
            GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            // Compile shaders
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
            
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            checkProgram(program, "OES")

            val yuvFragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, yuvFragmentShaderCode)
            yuvProgram = GLES20.glCreateProgram()
            GLES20.glAttachShader(yuvProgram, vertexShader)
            GLES20.glAttachShader(yuvProgram, yuvFragmentShader)
            GLES20.glLinkProgram(yuvProgram)
            checkProgram(yuvProgram, "YUV")
            
            // Get handles
            maPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            maTextureHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
            muMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            muSTMatrixHandle = GLES20.glGetUniformLocation(program, "uSTMatrix")
            muDesaturationHandle = GLES20.glGetUniformLocation(program, "uDesaturation")
            yuvPositionHandle = GLES20.glGetAttribLocation(yuvProgram, "aPosition")
            yuvTextureHandle = GLES20.glGetAttribLocation(yuvProgram, "aTextureCoord")
            yuvMVPMatrixHandle = GLES20.glGetUniformLocation(yuvProgram, "uMVPMatrix")
            yuvSTMatrixHandle = GLES20.glGetUniformLocation(yuvProgram, "uSTMatrix")
            yuvDesaturationHandle = GLES20.glGetUniformLocation(yuvProgram, "uDesaturation")
            yTextureHandle = GLES20.glGetUniformLocation(yuvProgram, "yTexture")
            uTextureHandle = GLES20.glGetUniformLocation(yuvProgram, "uTexture")
            vTextureHandle = GLES20.glGetUniformLocation(yuvProgram, "vTexture")
            yTextureScaleHandle = GLES20.glGetUniformLocation(yuvProgram, "yTextureScaleX")
            uTextureScaleHandle = GLES20.glGetUniformLocation(yuvProgram, "uTextureScaleX")
            vTextureScaleHandle = GLES20.glGetUniformLocation(yuvProgram, "vTextureScaleX")
            AppLog.i("GlProjectionView: YUV handles pos=$yuvPositionHandle tex=$yuvTextureHandle mvp=$yuvMVPMatrixHandle st=$yuvSTMatrixHandle y=$yTextureHandle u=$uTextureHandle v=$vTextureHandle")

            GLES20.glGenTextures(3, yuvTextureIds, 0)
            for (id in yuvTextureIds) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }

            // Buffers
            val bb = ByteBuffer.allocateDirect(squareCoords.size * 4)
            bb.order(ByteOrder.nativeOrder())
            vertexBuffer = bb.asFloatBuffer()
            vertexBuffer?.put(squareCoords)
            vertexBuffer?.position(0)

            val bbT = ByteBuffer.allocateDirect(textureCoords.size * 4)
            bbT.order(ByteOrder.nativeOrder())
            textureBuffer = bbT.asFloatBuffer()
            textureBuffer?.put(textureCoords)
            textureBuffer?.position(0)

            val bbYuvT = ByteBuffer.allocateDirect(yuvTextureCoords.size * 4)
            bbYuvT.order(ByteOrder.nativeOrder())
            yuvTextureBuffer = bbYuvT.asFloatBuffer()
            yuvTextureBuffer?.put(yuvTextureCoords)
            yuvTextureBuffer?.position(0)

            // Create Surface
            surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture!!.setOnFrameAvailableListener(this)
            surface = Surface(surfaceTexture)
            
            // Notify Activity on Main Thread
            Handler(Looper.getMainLooper()).post {
                AppLog.i("GlProjectionView: Reporting Surface Created")
                callbacks.forEach { it.onSurfaceCreated(surface!!) }
            }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            AppLog.i("GlProjectionView: onSurfaceChanged: ${width}x$height")
            GLES20.glViewport(0, 0, width, height)
            Handler(Looper.getMainLooper()).post {
                callbacks.forEach { it.onSurfaceChanged(surface!!, width, height) }
            }
        }

        override fun onDrawFrame(gl: GL10?) {
            synchronized(this) {
                if (updateSurface) {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(sSTMatrix)
                    updateSurface = false
                    markFrameDrawn()
                }
            }
            
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // Whichever source produced a frame more recently. See lastOesFrameSeq.
            val shouldDrawYuv = synchronized(this) { hasYuvFrame && lastYuvFrameSeq >= lastOesFrameSeq }
            if (shouldDrawYuv) {
                drawYuvFrame()
                return
            }
            
            GLES20.glUseProgram(program)
            
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            
            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(maPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(maPositionHandle)
            
            textureBuffer?.position(0)
            GLES20.glVertexAttribPointer(maTextureHandle, 2, GLES20.GL_FLOAT, false, 2 * 4, textureBuffer)
            GLES20.glEnableVertexAttribArray(maTextureHandle)
            
            Matrix.setIdentityM(mVPMatrix, 0)
            Matrix.scaleM(mVPMatrix, 0, mScaleX, mScaleY, 1f)
            GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mVPMatrix, 0)
            GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, sSTMatrix, 0)
            GLES20.glUniform1f(muDesaturationHandle, desaturation)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun drawYuvFrame() {
            val width: Int
            val height: Int
            synchronized(this) {
                width = yuvWidth
                height = yuvHeight
                if (pendingYuvFrame) {
                    GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    uploadYuvPlane(0, yuvTextureIds[0], yPlaneBuffer!!, width, height)
                    uploadYuvPlane(1, yuvTextureIds[1], uPlaneBuffer!!, width / 2, height / 2)
                    uploadYuvPlane(2, yuvTextureIds[2], vPlaneBuffer!!, width / 2, height / 2)
                    yTextureScaleX = 1f
                    uTextureScaleX = 1f
                    vTextureScaleX = 1f
                    pendingYuvFrame = false
                    markFrameDrawn()
                    if (!loggedFirstYuvUpload) {
                        loggedFirstYuvUpload = true
                        AppLog.i("GlProjectionView: first YUV420 frame uploaded ${width}x$height")
                    }
                }
            }

            GLES20.glUseProgram(yuvProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextureIds[0])
            GLES20.glUniform1i(yTextureHandle, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextureIds[1])
            GLES20.glUniform1i(uTextureHandle, 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextureIds[2])
            GLES20.glUniform1i(vTextureHandle, 2)

            vertexBuffer?.position(0)
            GLES20.glVertexAttribPointer(yuvPositionHandle, 3, GLES20.GL_FLOAT, false, 3 * 4, vertexBuffer)
            GLES20.glEnableVertexAttribArray(yuvPositionHandle)

            yuvTextureBuffer?.position(0)
            GLES20.glVertexAttribPointer(yuvTextureHandle, 2, GLES20.GL_FLOAT, false, 2 * 4, yuvTextureBuffer)
            GLES20.glEnableVertexAttribArray(yuvTextureHandle)

            Matrix.setIdentityM(mVPMatrix, 0)
            Matrix.scaleM(mVPMatrix, 0, mScaleX, mScaleY, 1f)
            GLES20.glUniformMatrix4fv(yuvMVPMatrixHandle, 1, false, mVPMatrix, 0)
            Matrix.setIdentityM(sSTMatrix, 0)
            GLES20.glUniformMatrix4fv(yuvSTMatrixHandle, 1, false, sSTMatrix, 0)
            GLES20.glUniform1f(yuvDesaturationHandle, desaturation)
            GLES20.glUniform1f(yTextureScaleHandle, yTextureScaleX)
            GLES20.glUniform1f(uTextureScaleHandle, uTextureScaleX)
            GLES20.glUniform1f(vTextureScaleHandle, vTextureScaleX)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            if (!loggedFirstYuvDraw) {
                loggedFirstYuvDraw = true
                AppLog.i("GlProjectionView: first YUV420 frame drawn")
            }
        }

        private fun uploadYuvPlane(index: Int, textureId: Int, buffer: ByteBuffer, width: Int, height: Int) {
            buffer.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
            if (uploadedPlaneWidths[index] != width || uploadedPlaneHeights[index] != height) {
                GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_LUMINANCE,
                    width,
                    height,
                    0,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    buffer
                )
                uploadedPlaneWidths[index] = width
                uploadedPlaneHeights[index] = height
            } else {
                GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    width,
                    height,
                    GLES20.GL_LUMINANCE,
                    GLES20.GL_UNSIGNED_BYTE,
                    buffer
                )
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            synchronized(this) {
                updateSurface = true
                lastOesFrameSeq = ++frameSequence
            }
            requestRender()
        }
        
        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                AppLog.e("GlProjectionView: shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun checkProgram(programId: Int, label: String) {
            val status = IntArray(1)
            GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                AppLog.e("GlProjectionView: $label program link failed: ${GLES20.glGetProgramInfoLog(programId)}")
            }
        }

    }
}
