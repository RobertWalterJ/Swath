package app.wallcapture.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.view.WindowManager
import app.wallcapture.core.CaptureConfig
import app.wallcapture.core.capture.CaptureTrigger
import app.wallcapture.core.capture.CoverageGrid
import app.wallcapture.core.capture.CoverageSnapshot
import app.wallcapture.core.capture.FrameState
import app.wallcapture.core.capture.Hint
import app.wallcapture.core.geometry.Intrinsics
import app.wallcapture.core.geometry.Mat4
import app.wallcapture.core.geometry.PlaneHomography
import app.wallcapture.core.geometry.Vec3
import app.wallcapture.core.geometry.WallFrame
import app.wallcapture.core.image.Gray
import app.wallcapture.core.image.Sharpness
import app.wallcapture.core.session.StillSidecar
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sqrt

enum class Phase { Starting, FindingWall, WallLocked, Sweeping, Paused, Unsupported }

sealed interface CaptureEvent {
    data object StillKept : CaptureEvent
    data object SlowDown : CaptureEvent
    data object Blurry : CaptureEvent
}

/** Everything the capture screen draws. Footprint and coverage are in wall mm. */
data class CaptureUi(
    val phase: Phase = Phase.Starting,
    val hint: Hint = Hint.None,
    val message: String? = null,
    val distanceM: Double = 0.0,
    val rollDeg: Double = 0.0,
    val footprint: List<DoubleArray> = emptyList(),
    val coverage: CoverageSnapshot? = null,
    val stillCount: Int = 0,
    val gapCells: Int = 0,
    val tracking: Boolean = false,
)

/**
 * Owns the ARCore session. Triggering, gating and coverage come from :core so they behave the same
 * on a phone as in the replay CLI. Stills are the highest-resolution CPU image ARCore offers
 * (see DECISIONS.md: Shared Camera / Camera2 stills are a later step).
 */
class ArCaptureEngine(
    private val context: Context,
    private val cfg: CaptureConfig = CaptureConfig(),
    /** Called on a background thread for every kept still. */
    private val onStill: (StillSidecar, ByteArray) -> Unit,
    /** Called once when the wall is locked. */
    private val onWallLocked: (WallFrame) -> Unit,
) : GLSurfaceView.Renderer, SensorEventListener {

    private val _ui = MutableStateFlow(CaptureUi())
    val ui: StateFlow<CaptureUi> = _ui
    val events = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 8)

    private var session: Session? = null
    private val background = BackgroundRenderer()
    private val taps = ConcurrentLinkedQueue<FloatArray>()
    private val io = Executors.newSingleThreadExecutor()
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var width = 0
    private var height = 0
    private var geometryDirty = true
    @Volatile private var angularVel = 0.0
    @Volatile private var wantSweep = false
    @Volatile private var finished = false

    private var phase = Phase.Starting
    private var frameCounter = 0
    private var wall: WallFrame? = null
    private var trigger: CaptureTrigger? = null
    private var grid = CoverageGrid(cfg)
    private var stillCount = 0
    private var sessionStartNs = 0L

    private class Candidate(val nv21: ByteArray, val w: Int, val h: Int, val sharpness: Double, val pose: Mat4, val intrinsics: Intrinsics, val state: FrameState)
    private val burst = ArrayList<Candidate>()
    private var bursting = false

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    /** Creates the ARCore session. Returns a message when ARCore is unavailable, null on success. */
    fun create(): String? {
        return try {
            val s = Session(context)
            val config = Config(s).apply {
                planeFindingMode = Config.PlaneFindingMode.VERTICAL
                updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                focusMode = Config.FocusMode.AUTO
            }
            s.configure(config)
            // Highest-resolution CPU image at 30 fps.
            val filter = CameraConfigFilter(s).setTargetFps(EnumSet.of(CameraConfig.TargetFps.TARGET_FPS_30))
            s.getSupportedCameraConfigs(filter).maxByOrNull { it.imageSize.width * it.imageSize.height }?.let { s.cameraConfig = it }
            session = s
            null
        } catch (e: Exception) {
            phase = Phase.Unsupported
            _ui.value = CaptureUi(phase = Phase.Unsupported, message = e.message ?: e.javaClass.simpleName)
            e.message ?: "ARCore is not available on this device"
        }
    }

    fun resume() {
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            _ui.value = _ui.value.copy(message = "Camera not available")
        }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (phase == Phase.Starting) phase = Phase.FindingWall
    }

    fun pause() {
        sensors.unregisterListener(this)
        session?.pause()
    }

    fun destroy() {
        session?.close()
        session = null
        io.shutdown()
    }

    // ---- UI commands -----------------------------------------------------------------------------------------------

    fun onTap(x: Float, y: Float) { taps.add(floatArrayOf(x, y)) }
    fun start() { wantSweep = true }
    fun pauseSweep() { wantSweep = false }
    /** Stops capturing, then calls [onFlushed] on the writer thread once every kept still has been saved. */
    fun finish(onFlushed: () -> Unit) {
        finished = true
        wantSweep = false
        io.execute { onFlushed() }
    }
    fun onViewportChanged() { geometryDirty = true }

    // ---- sensors ---------------------------------------------------------------------------------------------------

    override fun onSensorChanged(e: SensorEvent) {
        angularVel = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---- GL --------------------------------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.create()
        session?.setCameraTextureName(background.textureId)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        GLES20.glViewport(0, 0, w, h)
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (geometryDirty && width > 0) {
            val rotation = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            s.setDisplayGeometry(rotation, width, height)
            geometryDirty = false
        }
        s.setCameraTextureName(background.textureId)
        val frame = try { s.update() } catch (e: CameraNotAvailableException) { return } catch (e: Exception) { return }
        background.draw(frame)
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        if (sessionStartNs == 0L) sessionStartNs = frame.timestamp

        when (phase) {
            Phase.FindingWall -> findWall(s, frame, tracking)
            Phase.WallLocked, Phase.Paused -> {
                if (wantSweep) { phase = Phase.Sweeping }
                publish(frame, tracking, Hint.None)
            }
            Phase.Sweeping -> sweep(frame, tracking)
            else -> Unit
        }
    }

    // ---- phases ----------------------------------------------------------------------------------------------------

    private fun findWall(s: Session, frame: Frame, tracking: Boolean) {
        val tap = taps.poll()
        if (tap != null && tracking) {
            for (hit in frame.hitTest(tap[0], tap[1])) {
                val plane = hit.trackable as? Plane ?: continue
                if (plane.type != Plane.Type.VERTICAL || !plane.isPoseInPolygon(hit.hitPose)) continue
                val n = hit.hitPose.getTransformedAxis(1, 1f)
                val t = hit.hitPose.translation
                val cam = frame.camera.pose.translation
                val f = WallFrame.fromPlane(
                    Vec3(t[0].toDouble(), t[1].toDouble(), t[2].toDouble()),
                    Vec3(n[0].toDouble(), n[1].toDouble(), n[2].toDouble()),
                    Vec3(cam[0].toDouble(), cam[1].toDouble(), cam[2].toDouble()),
                )
                wall = f
                grid = CoverageGrid(cfg)
                intrinsicsFor(frame)?.let { trigger = CaptureTrigger(cfg, f, it, grid) }
                io.execute { onWallLocked(f) }
                phase = Phase.WallLocked
                break
            }
        }
        val planes = s.getAllTrackables(Plane::class.java).count { it.type == Plane.Type.VERTICAL && it.trackingState == TrackingState.TRACKING }
        _ui.value = _ui.value.copy(
            phase = phase, tracking = tracking, message = if (planes == 0) "Move slowly to find the wall" else "Tap the wall to lock on",
            hint = if (tracking) Hint.None else Hint.TrackingLost,
        )
    }

    private fun intrinsicsFor(frame: Frame): Intrinsics? = try {
        val i = frame.camera.imageIntrinsics
        Intrinsics(
            i.focalLength[0].toDouble(), i.focalLength[1].toDouble(),
            i.principalPoint[0].toDouble(), i.principalPoint[1].toDouble(),
            i.imageDimensions[0], i.imageDimensions[1],
        )
    } catch (e: Exception) { null }

    private fun poseOf(frame: Frame): Mat4 {
        val c = FloatArray(16)
        frame.camera.pose.toMatrix(c, 0) // column-major
        return Mat4(DoubleArray(16) { i -> c[(i % 4) * 4 + (i / 4)].toDouble() })
    }

    private fun sweep(frame: Frame, tracking: Boolean) {
        val f = wall ?: return
        val tr = trigger ?: return
        if (finished || !wantSweep) {
            phase = Phase.Paused
            publish(frame, tracking, Hint.None)
            return
        }
        val intr = intrinsicsFor(frame) ?: return
        val state = FrameState((frame.timestamp - sessionStartNs) / 1e9, poseOf(frame), angularVel, tracking)
        val decision = tr.onFrame(state)
        if (decision.hint == Hint.SlowDown) events.tryEmit(CaptureEvent.SlowDown)

        if (bursting) {
            if (tracking) collect(frame, state, intr)
            if (burst.size >= cfg.burstSize) finishBurst(f, tr, intr)
        } else if (decision.capture) {
            bursting = true
            burst.clear()
            collect(frame, state, intr)
        }
        publish(frame, tracking, decision.hint, PlaneHomography(f, state.pose, intr))
    }

    private fun collect(frame: Frame, state: FrameState, intr: Intrinsics) {
        try {
            frame.acquireCameraImage().use { img ->
                val nv21 = Yuv.toNv21(img)
                val gray = Yuv.lumaGray(img, 512)
                burst.add(Candidate(nv21, img.width, img.height, Sharpness.varianceOfLaplacian(gray), state.pose, intr, state))
            }
        } catch (e: NotYetAvailableException) {
            // Try again on the next frame.
        }
    }

    private fun finishBurst(f: WallFrame, tr: CaptureTrigger, intr: Intrinsics) {
        bursting = false
        val best = burst.maxByOrNull { it.sharpness } ?: return
        burst.clear()
        if (best.sharpness < cfg.minSharpness) {
            events.tryEmit(CaptureEvent.Blurry)
            return
        }
        val h = PlaneHomography(f, best.pose, best.intrinsics)
        tr.markKept(best.state)
        grid.addStill(h, sharp = true)
        stillCount++
        events.tryEmit(CaptureEvent.StillKept)
        val id = "s%03d".format(stillCount)
        val sidecar = StillSidecar(id, System.currentTimeMillis(), best.intrinsics, best.pose, best.sharpness, h.angleDeg, h.distanceM, android.os.Build.MODEL)
        io.execute {
            val bos = ByteArrayOutputStream(1 shl 20)
            YuvImage(best.nv21, ImageFormat.NV21, best.w, best.h, null).compressToJpeg(Rect(0, 0, best.w, best.h), 95, bos)
            onStill(sidecar, bos.toByteArray())
        }
    }

    private fun publish(frame: Frame, tracking: Boolean, hint: Hint, h: PlaneHomography? = null) {
        if (++frameCounter % 3 != 0 && h == null) return
        val snap = if (frameCounter % 6 == 0 || _ui.value.coverage == null) grid.snapshot() else _ui.value.coverage
        _ui.value = CaptureUi(
            phase = phase,
            hint = if (!tracking) Hint.TrackingLost else hint,
            distanceM = h?.distanceM ?: _ui.value.distanceM,
            rollDeg = h?.rollDeg ?: 0.0,
            footprint = h?.footprint() ?: _ui.value.footprint,
            coverage = snap,
            stillCount = stillCount,
            gapCells = grid.gapCellsInsideBounds(),
            tracking = tracking,
            message = if (phase == Phase.WallLocked) "Wall locked. Tap Start and walk." else null,
        )
    }
}

internal inline fun <R> Image.use(block: (Image) -> R): R = try { block(this) } finally { close() }

internal object Yuv {
    /** Converts an Android YUV_420_888 image to NV21, honouring row and pixel strides. */
    fun toNv21(img: Image): ByteArray {
        val w = img.width; val h = img.height
        val out = ByteArray(w * h * 3 / 2)
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        var pos = 0
        val yb = y.buffer
        for (row in 0 until h) {
            val rowStart = row * y.rowStride
            for (col in 0 until w) out[pos++] = yb.get(rowStart + col * y.pixelStride)
        }
        val ub = u.buffer; val vb = v.buffer
        for (row in 0 until h / 2) for (col in 0 until w / 2) {
            out[pos++] = vb.get(row * v.rowStride + col * v.pixelStride)
            out[pos++] = ub.get(row * u.rowStride + col * u.pixelStride)
        }
        return out
    }

    /** Luma plane as a [Gray] no larger than [maxDim] on its long side, by sampling. */
    fun lumaGray(img: Image, maxDim: Int): Gray {
        val plane = img.planes[0]
        val step = maxOf(1, maxOf(img.width, img.height) / maxDim)
        val w = img.width / step; val h = img.height / step
        val g = Gray(w, h)
        val b = plane.buffer
        for (y in 0 until h) for (x in 0 until w) {
            g.data[y * w + x] = (b.get(y * step * plane.rowStride + x * step * plane.pixelStride).toInt() and 0xFF).toFloat()
        }
        return g
    }
}
