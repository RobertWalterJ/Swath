package app.wallcapture.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.wallcapture.capture.ArCaptureEngine
import app.wallcapture.capture.CaptureEvent
import app.wallcapture.capture.Phase
import app.wallcapture.core.capture.CellState
import app.wallcapture.core.capture.Hint
import app.wallcapture.core.export.Json
import app.wallcapture.core.session.SessionIO
import app.wallcapture.storage.StillEntity
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

@Composable
fun CaptureScreen(vm: AppViewModel, sessionId: String) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.camera_permission))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 16.dp)) {
                Text(stringResource(R.string.grant))
            }
        }
        return
    }
    CaptureContent(vm, sessionId)
}

@Composable
private fun CaptureContent(vm: AppViewModel, sessionId: String) {
    val ctx = LocalContext.current
    val dir = remember(sessionId) { vm.store.dir(sessionId) }

    val engine = remember(sessionId) {
        ArCaptureEngine(
            context = ctx,
            onStill = { sidecar, jpeg ->
                SessionIO.writeStill(dir, sidecar, jpeg)
                val k = sidecar.intrinsics
                runBlocking {
                    vm.dao.upsertStill(
                        StillEntity(
                            "$sessionId/${sidecar.id}", sessionId, "${sidecar.id}.jpg", sidecar.timestampMs,
                            Json.write(listOf(k.fx, k.fy, k.cx, k.cy, k.width, k.height), false), Json.write(sidecar.poseRaw.toList(), false),
                            null, sidecar.sharpness, sidecar.angleDeg, sidecar.distanceM,
                        ),
                    )
                }
            },
            onWallLocked = { frame ->
                val title = runBlocking { vm.dao.get(sessionId)?.title } ?: sessionId
                SessionIO.writeSessionHeader(dir, title, frame, Build.MODEL)
            },
        )
    }
    var arError by remember { mutableStateOf<String?>(null) }
    var created by remember { mutableStateOf(false) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var showGapPrompt by remember { mutableStateOf(false) }
    var onboarding by remember { mutableStateOf(!vm.seenOnboarding) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val activity = ctx as Activity
        val ok = try {
            ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED
        } catch (e: Exception) { arError = e.message; false }
        if (ok) {
            if (!created) { arError = engine.create(); created = arError == null }
            if (created) { engine.resume(); glView?.onResume() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        glView?.onPause()
        if (created) engine.pause()
    }
    DisposableEffect(engine) { onDispose { engine.destroy() } }

    // Haptics: a light tick per kept still, a double tap for "slow down".
    LaunchedEffect(engine) {
        val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        var lastSlow = 0L
        engine.events.collect { e ->
            when (e) {
                CaptureEvent.StillKept -> vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                CaptureEvent.SlowDown -> if (System.currentTimeMillis() - lastSlow > 1500) {
                    lastSlow = System.currentTimeMillis()
                    vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
                }
                CaptureEvent.Blurry -> Unit
            }
        }
    }

    val ui by engine.ui.collectAsState()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                GLSurfaceView(c).apply {
                    preserveEGLContextOnPause = true
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    setRenderer(engine)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    setOnTouchListener { v, ev ->
                        if (ev.action == MotionEvent.ACTION_UP) { engine.onTap(ev.x, ev.y); v.performClick() }
                        true
                    }
                    glView = this
                }
            },
            update = { engine.onViewportChanged() },
        )

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            CoverageStrip(ui, Modifier.fillMaxWidth().height(64.dp))
            Box(Modifier.weight(1f))
            val hintText = hintText(ui.hint) ?: ui.message ?: arError
            if (hintText != null) {
                Text(
                    hintText,
                    Modifier.align(Alignment.CenterHorizontally).background(Color(0xCC000000)).padding(horizontal = 14.dp, vertical = 8.dp)
                        .semantics { contentDescription = hintText },
                    color = Color.White,
                )
            }
            BottomBar(
                ui.phase, ui.distanceM, ui.rollDeg, ui.stillCount,
                onStart = { engine.start() }, onPause = { engine.pauseSweep() },
                onDone = {
                    if (ui.stillCount == 0) { vm.delete(sessionId); vm.screen = Screen.Library }
                    else if (ui.gapCells > 0) showGapPrompt = true
                    else engine.finish { vm.finishCapture(sessionId) }
                },
            )
        }
    }

    if (showGapPrompt) {
        AlertDialog(
            onDismissRequest = { showGapPrompt = false },
            title = { Text(stringResource(R.string.gaps_prompt)) },
            confirmButton = { TextButton(onClick = { showGapPrompt = false; engine.start() }) { Text(stringResource(R.string.fill_gaps)) } },
            dismissButton = { TextButton(onClick = { showGapPrompt = false; engine.finish { vm.finishCapture(sessionId) } }) { Text(stringResource(R.string.finish_anyway)) } },
        )
    }
    if (onboarding) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.onboarding_title)) },
            text = { Text(stringResource(R.string.onboarding_body)) },
            confirmButton = { TextButton(onClick = { onboarding = false; vm.seenOnboarding = true }) { Text(stringResource(R.string.got_it)) } },
        )
    }
}

@Composable
private fun hintText(h: Hint): String? = when (h) {
    Hint.None -> null
    Hint.SlowDown -> stringResource(R.string.slow_down)
    Hint.MoveCloser -> stringResource(R.string.move_closer)
    Hint.MoveBack -> stringResource(R.string.move_back)
    Hint.FaceWall -> stringResource(R.string.face_wall)
    Hint.LevelPhone -> stringResource(R.string.level_phone)
    Hint.TrackingLost -> stringResource(R.string.tracking_lost)
}

/** The wall as a horizontal band that fills in as you sweep, with the camera footprint as a moving rectangle. */
@Composable
private fun CoverageStrip(ui: app.wallcapture.capture.CaptureUi, modifier: Modifier) {
    val snap = ui.coverage
    val bitmap = remember(snap) {
        snap?.let { s ->
            val px = IntArray(s.widthCells * s.heightCells)
            for (iy in 0 until s.heightCells) for (ix in 0 until s.widthCells) {
                val state = CellState.entries[s.states[iy * s.widthCells + ix].toInt()]
                // Bitmap row 0 is the top of the wall, so flip y.
                px[(s.heightCells - 1 - iy) * s.widthCells + ix] = when (state) {
                    CellState.NotCaptured -> 0xFF202020.toInt()
                    CellState.Good -> 0xFF3FA34D.toInt()
                    CellState.Weak -> 0xFFE0A100.toInt()
                    CellState.Glare -> 0xFFF5F5F5.toInt()
                }
            }
            Bitmap.createBitmap(px, s.widthCells, s.heightCells, Bitmap.Config.ARGB_8888)
        }
    }
    Canvas(modifier.semantics { contentDescription = "Coverage map of the wall captured so far" }) {
        drawRect(Color(0xCC000000))
        val cell = snap?.cellMm ?: 20.0
        // World extent shown: the captured cells plus the current footprint.
        var x0 = (snap?.minIx ?: 0) * cell; var x1 = x0 + (snap?.widthCells ?: 0) * cell
        var y0 = (snap?.minIy ?: 0) * cell; var y1 = y0 + (snap?.heightCells ?: 0) * cell
        for (p in ui.footprint) { x0 = min(x0, p[0]); x1 = max(x1, p[0]); y0 = min(y0, p[1]); y1 = max(y1, p[1]) }
        if (x1 <= x0 || y1 <= y0) return@Canvas
        x1 = max(x1, x0 + 2000.0) // never zoom in tighter than 2 m
        val scale = min(size.width / (x1 - x0), size.height / (y1 - y0)).toFloat()
        fun sx(x: Double) = ((x - x0) * scale).toFloat()
        fun sy(y: Double) = ((y1 - y) * scale).toFloat()
        if (bitmap != null && snap != null) {
            val left = sx(snap.minIx * cell); val top = sy((snap.minIy + snap.heightCells) * cell)
            drawImage(
                bitmap.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize((snap.widthCells * cell * scale).toInt().coerceAtLeast(1), (snap.heightCells * cell * scale).toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None,
            )
        }
        if (ui.footprint.size >= 4) {
            val b = app.wallcapture.core.geometry.Polygon.bounds(ui.footprint)
            drawRect(Color.White, Offset(sx(b[0]), sy(b[3])), Size(((b[2] - b[0]) * scale).toFloat(), ((b[3] - b[1]) * scale).toFloat()), style = Stroke(3f))
        }
    }
}

@Composable
private fun BottomBar(
    phase: Phase, distanceM: Double, rollDeg: Double, stills: Int,
    onStart: () -> Unit, onPause: () -> Unit, onDone: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xCC000000)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        val sweeping = phase == Phase.Sweeping
        if (sweeping) OutlinedButton(onClick = onPause) { Text(stringResource(R.string.pause), color = Color.White) }
        else Button(onClick = onStart, enabled = phase == Phase.WallLocked || phase == Phase.Paused) { Text(stringResource(R.string.start)) }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (distanceM > 0) "%.1f m".format(distanceM) else "-- m", color = Color.White)
            Text("$stills", color = Color(0xFFBBBBBB))
        }
        Canvas(Modifier.size(36.dp).semantics { contentDescription = "Level indicator" }) {
            drawCircle(Color(0x55FFFFFF))
            rotate(-rollDeg.toFloat()) { drawLine(if (kotlin.math.abs(rollDeg) < 5) Color(0xFF3FA34D) else Color(0xFFE0A100), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 4f) }
        }
        Button(onClick = onDone) { Text(stringResource(R.string.done)) }
    }
}
