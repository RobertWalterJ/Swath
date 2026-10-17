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
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.wallcapture.app.ui.Copy
import app.wallcapture.app.ui.PrimaryButton
import app.wallcapture.app.ui.QuietButton
import app.wallcapture.app.ui.RoundIconButton
import app.wallcapture.app.ui.Space
import app.wallcapture.app.ui.SpeakerButton
import app.wallcapture.app.ui.StepRail
import app.wallcapture.capture.ArCaptureEngine
import app.wallcapture.capture.CaptureEvent
import app.wallcapture.capture.CaptureUi
import app.wallcapture.capture.Phase
import app.wallcapture.core.capture.CellState
import app.wallcapture.core.capture.Hint
import app.wallcapture.core.export.Json
import app.wallcapture.core.geometry.Polygon
import app.wallcapture.core.session.SessionIO
import app.wallcapture.storage.StillEntity
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

private val STAGES = listOf(Copy.STAGE_AIM, Copy.STAGE_READY, Copy.STAGE_WALK, Copy.STAGE_CHECK)

@Composable
fun CaptureScreen(vm: AppViewModel, sessionId: String) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    LaunchedEffect(Unit) { if (!vm.seenOnboarding) vm.showHowTo = true }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Space.xl),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start,
        ) {
            Text(Copy.NO_CAMERA, style = MaterialTheme.typography.headlineMedium)
            Box(Modifier.size(Space.xl))
            PrimaryButton(Copy.ALLOW_CAMERA, onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.fillMaxWidth())
            Box(Modifier.size(Space.md))
            QuietButton(Copy.CANCEL, onClick = { vm.delete(sessionId); vm.screen = Screen.Library }, modifier = Modifier.fillMaxWidth())
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
    var review by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val activity = ctx as Activity
        val ok = try {
            ArCoreApk.getInstance().requestInstall(activity, true) == ArCoreApk.InstallStatus.INSTALLED
        } catch (e: Exception) { arError = Copy.NO_AR; false }
        if (ok) {
            if (!created) { arError = engine.create()?.let { Copy.NO_AR }; created = arError == null }
            if (created) { engine.resume(); glView?.onResume() }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        glView?.onPause()
        if (created) engine.pause()
    }
    DisposableEffect(engine) { onDispose { engine.destroy() } }

    // Haptics: a light tick for each kept still, a double tap for "slow down".
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
    val stage = when {
        review -> 3
        ui.phase == Phase.Sweeping -> 2
        ui.phase == Phase.Paused && ui.stillCount > 0 -> 2
        ui.phase == Phase.Paused || ui.phase == Phase.WallLocked -> 1
        else -> 0
    }
    val makeWall = { engine.finish { vm.finishCapture(sessionId) } }

    BackHandler { if (ui.stillCount > 0 || stage > 0) leaving = true else { vm.delete(sessionId); vm.screen = Screen.Library } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
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
        // Soft scrims keep text readable on any wall.
        Box(Modifier.fillMaxWidth().height(190.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))))

        if (stage == 0 && !review) AimBrackets(Modifier.align(Alignment.Center))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.lg, vertical = Space.md)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Close, "Leave", { if (ui.stillCount > 0 || stage > 0) leaving = true else { vm.delete(sessionId); vm.screen = Screen.Library } })
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { StepRail(STAGES, stage, onDark = true) }
                Box(Modifier.size(48.dp))
            }
            if (stage >= 2) {
                Box(Modifier.size(Space.md))
                CoverageStrip(ui, Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(14.dp)))
            }
            Box(Modifier.weight(1f))
            InstructionCard(
                stage = stage, ui = ui, review = review,
                onStart = { engine.start() },
                onPause = { engine.pauseSweep() },
                onFinish = { engine.pauseSweep(); review = true },
                onKeepWalking = { review = false; engine.start() },
                onMake = { makeWall() },
                arError = arError,
            )
        }
    }

    if (leaving) {
        AlertDialog(
            onDismissRequest = { leaving = false },
            title = { Text(Copy.LEAVE_TITLE) },
            text = { Text(Copy.LEAVE_BODY) },
            confirmButton = {
                if (ui.stillCount > 0) TextButton(onClick = { leaving = false; makeWall() }) { Text(Copy.MAKE_WALL) }
                else TextButton(onClick = { leaving = false }) { Text(Copy.CANCEL) }
            },
            dismissButton = { TextButton(onClick = { leaving = false; vm.delete(sessionId); vm.screen = Screen.Library }) { Text(Copy.DELETE, color = MaterialTheme.colorScheme.error) } },
        )
    }
}

@Composable
private fun AimBrackets(modifier: Modifier) {
    Canvas(modifier.size(220.dp)) {
        val k = 44f; val w = 5f; val c = Color.White
        val l = 0f; val t = 0f; val r = size.width; val b = size.height
        drawLine(c, Offset(l, t + k), Offset(l, t), w, StrokeCap.Round); drawLine(c, Offset(l, t), Offset(l + k, t), w, StrokeCap.Round)
        drawLine(c, Offset(r - k, t), Offset(r, t), w, StrokeCap.Round); drawLine(c, Offset(r, t), Offset(r, t + k), w, StrokeCap.Round)
        drawLine(c, Offset(l, b - k), Offset(l, b), w, StrokeCap.Round); drawLine(c, Offset(l, b), Offset(l + k, b), w, StrokeCap.Round)
        drawLine(c, Offset(r - k, b), Offset(r, b), w, StrokeCap.Round); drawLine(c, Offset(r, b - k), Offset(r, b), w, StrokeCap.Round)
    }
}

/** The one card at the bottom: one sentence, one main button. Nothing here fades or counts down. */
@Composable
private fun InstructionCard(
    stage: Int, ui: CaptureUi, review: Boolean,
    onStart: () -> Unit, onPause: () -> Unit, onFinish: () -> Unit, onKeepWalking: () -> Unit, onMake: () -> Unit,
    arError: String?,
) {
    val hint = when (ui.hint) {
        Hint.None -> null
        Hint.SlowDown -> Copy.HINT_SLOW
        Hint.MoveCloser -> Copy.HINT_CLOSER
        Hint.MoveBack -> Copy.HINT_BACK
        Hint.FaceWall -> Copy.HINT_FACE
        Hint.LevelPhone -> Copy.HINT_LEVEL
        Hint.TrackingLost -> Copy.HINT_LOST
    }
    val gaps = ui.gapCells > 0
    val title: String?
    val body: String
    when {
        arError != null -> { title = null; body = arError }
        review -> { title = if (gaps) Copy.CHECK_GAPS_TITLE else Copy.CHECK_GOOD_TITLE; body = if (gaps) Copy.CHECK_GAPS_BODY else Copy.CHECK_GOOD_BODY }
        hint != null -> { title = null; body = hint }
        stage == 0 -> { title = null; body = if (ui.message?.startsWith("Tap") == true) Copy.AIM_FOUND else Copy.AIM_SEARCHING }
        stage == 1 -> { title = null; body = Copy.READY_BODY }
        else -> { title = null; body = Copy.WALK_BODY }
    }

    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.surface).padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = Space.xs)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
                Text(body, style = MaterialTheme.typography.bodyLarge, color = if (title != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
            SpeakerButton((title?.let { "$it. " } ?: "") + body, label = Copy.LISTEN)
        }
        if (review) {
            if (ui.coverage != null) Legend()
            if (gaps) {
                PrimaryButton(Copy.GO_BACK, onClick = onKeepWalking, modifier = Modifier.fillMaxWidth())
                QuietButton(Copy.MAKE_WALL, onClick = onMake, modifier = Modifier.fillMaxWidth())
            } else {
                PrimaryButton(Copy.MAKE_WALL, onClick = onMake, modifier = Modifier.fillMaxWidth())
                QuietButton(Copy.GO_BACK, onClick = onKeepWalking, modifier = Modifier.fillMaxWidth())
            }
        } else when (stage) {
            0 -> Unit
            1 -> PrimaryButton(Copy.START_WALKING, onClick = onStart, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.PlayArrow)
            else -> Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                val running = ui.phase == Phase.Sweeping
                QuietButton(if (running) Copy.PAUSE else Copy.START_WALKING, onClick = if (running) onPause else onStart, modifier = Modifier.weight(1f), icon = if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow)
                PrimaryButton(Copy.FINISH, onClick = onFinish, modifier = Modifier.weight(1f), enabled = ui.stillCount > 0)
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.lg), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(CellState.Good, Copy.LEGEND_GOOD)
        LegendItem(CellState.Weak, Copy.LEGEND_WEAK)
        LegendItem(CellState.NotCaptured, Copy.LEGEND_MISSING)
    }
}

@Composable
private fun LegendItem(state: CellState, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))) {
            drawRect(cellColour(state))
            if (state == CellState.Weak) {
                var x = -size.height
                while (x < size.width) { drawLine(Color(0xFF5A4300), Offset(x, size.height), Offset(x + size.height, 0f), 3f); x += 7f }
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun cellColour(s: CellState) = when (s) {
    CellState.NotCaptured -> Color(0xFF202020)
    CellState.Good -> Color(0xFF3FA34D)
    CellState.Weak -> Color(0xFFE0A100)
    CellState.Glare -> Color(0xFFF5F5F5)
}

/**
 * The wall as a band that fills in as you walk. Clear areas are solid green, blurry areas are amber with stripes,
 * missing areas are dark. The stripes mean the map still works without colour vision.
 */
@Composable
private fun CoverageStrip(ui: CaptureUi, modifier: Modifier) {
    val snap = ui.coverage
    val px = 3 // each cell is drawn 3x3 pixels so a stripe pattern fits
    val bitmap = remember(snap) {
        snap?.let { s ->
            val w = s.widthCells * px; val h = s.heightCells * px
            val out = IntArray(w * h)
            for (iy in 0 until s.heightCells) for (ix in 0 until s.widthCells) {
                val state = CellState.entries[s.states[iy * s.widthCells + ix].toInt()]
                val base = cellColour(state).toArgbInt()
                val stripe = 0xFF5A4300.toInt()
                for (dy in 0 until px) for (dx in 0 until px) {
                    val x = ix * px + dx
                    val y = (s.heightCells - 1 - iy) * px + dy // bitmap row 0 is the top of the wall
                    out[y * w + x] = if (state == CellState.Weak && (dx + dy) % px == 0) stripe else base
                }
            }
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        }
    }
    Canvas(modifier.background(Color(0xCC000000)).semantics { contentDescription = Copy.MAP_LABEL }) {
        val cell = snap?.cellMm ?: 20.0
        var x0 = (snap?.minIx ?: 0) * cell; var x1 = x0 + (snap?.widthCells ?: 0) * cell
        var y0 = (snap?.minIy ?: 0) * cell; var y1 = y0 + (snap?.heightCells ?: 0) * cell
        for (p in ui.footprint) { x0 = min(x0, p[0]); x1 = max(x1, p[0]); y0 = min(y0, p[1]); y1 = max(y1, p[1]) }
        if (x1 <= x0 || y1 <= y0) return@Canvas
        x1 = max(x1, x0 + 2000.0) // never zoom in tighter than 2 m
        val inset = 6f
        val scale = min((size.width - 2 * inset) / (x1 - x0), (size.height - 2 * inset) / (y1 - y0)).toFloat()
        fun sx(x: Double) = inset + ((x - x0) * scale).toFloat()
        fun sy(y: Double) = inset + ((y1 - y) * scale).toFloat()
        if (bitmap != null && snap != null) {
            drawImage(
                bitmap.asImageBitmap(), srcOffset = IntOffset.Zero, srcSize = IntSize(bitmap.width, bitmap.height),
                dstOffset = IntOffset(sx(snap.minIx * cell).toInt(), sy((snap.minIy + snap.heightCells) * cell).toInt()),
                dstSize = IntSize((snap.widthCells * cell * scale).toInt().coerceAtLeast(1), (snap.heightCells * cell * scale).toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None,
            )
        }
        if (ui.footprint.size >= 4) {
            val b = Polygon.bounds(ui.footprint)
            drawRect(Color.White, Offset(sx(b[0]), sy(b[3])), Size(((b[2] - b[0]) * scale).toFloat(), ((b[3] - b[1]) * scale).toFloat()), style = Stroke(3f))
        }
    }
}

private fun Color.toArgbInt(): Int =
    ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
