package app.wallcapture.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.wallcapture.core.model.NOTE_COLOURS
import app.wallcapture.storage.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private val NOTE_SWATCH = mapOf(
    "yellow" to Color(0xFFFFEB3B), "pink" to Color(0xFFFF80AB), "orange" to Color(0xFFFF9800), "green" to Color(0xFF8BC34A),
    "blue" to Color(0xFF4FC3F7), "purple" to Color(0xFFBA68C8), "white" to Color(0xFFFAFAFA),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: AppViewModel, target: Screen.Viewer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by produceState<app.wallcapture.storage.SessionEntity?>(null, target.sessionId) { value = vm.dao.get(target.sessionId) }
    val notes by vm.dao.observeNotes(target.sessionId).collectAsState(emptyList())
    val reader = remember(target.sessionId) { vm.tileReader(target.sessionId) }
    val exporter = remember { ExportActions(ctx, vm.store) }

    var showNotes by remember { mutableStateOf(true) }
    var showText by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NoteEntity?>(null) }
    var shareMenu by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf("Position") }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(0f) } // screen px per level-0 mosaic px
    var offset by remember { mutableStateOf(Offset.Zero) } // screen position of the mosaic's top-left
    val images = remember(reader) { mutableStateMapOf<String, ImageBitmap>() }
    var exporting by remember { mutableStateOf(false) }

    val meta = reader?.meta
    val minScale = if (meta != null && viewSize.width > 0) min(viewSize.width.toFloat() / meta.widthPx, viewSize.height.toFloat() / meta.heightPx) else 1f
    val maxScale = 4f

    LaunchedEffect(viewSize, meta) {
        if (meta != null && scale == 0f && viewSize.width > 0) {
            scale = minScale
            offset = Offset((viewSize.width - meta.widthPx * scale) / 2, (viewSize.height - meta.heightPx * scale) / 2)
        }
    }

    fun focus(n: NoteEntity) {
        val m = meta ?: return
        val ppm = m.pxPerMm
        val cx = ((n.x + n.w / 2) * ppm).toFloat(); val cy = (m.heightPx - (n.y + n.h / 2) * ppm).toFloat()
        scale = (viewSize.width / 3f / (n.w * ppm).toFloat()).coerceIn(minScale, maxScale)
        offset = Offset(viewSize.width / 2f - cx * scale, viewSize.height / 2f - cy * scale)
    }
    LaunchedEffect(target.focusNoteId, notes, viewSize, meta) {
        val id = target.focusNoteId ?: return@LaunchedEffect
        notes.firstOrNull { it.noteId == id }?.let { if (viewSize.width > 0) focus(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(session?.title ?: "") },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Library }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { shareMenu = true }, enabled = !exporting) { Icon(Icons.Default.Share, stringResource(R.string.share)) }
                        DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                            for (kind in ExportKind.entries) DropdownMenuItem(
                                text = { Text(stringResource(kind.label)) },
                                onClick = {
                                    shareMenu = false
                                    val s = session ?: return@DropdownMenuItem
                                    exporting = true
                                    scope.launch {
                                        val file = withContext(Dispatchers.IO) { runCatching { exporter.build(kind, s, notes, reader) }.getOrNull() }
                                        exporting = false
                                        if (file != null) exporter.share(kind, file)
                                    }
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(showNotes, { showNotes = !showNotes }, { Text(stringResource(R.string.layer_notes)) })
                FilterChip(showText, { showText = !showText }, { Text(stringResource(R.string.layer_text)) })
                FilterChip(showList, { showList = !showList }, { Text(stringResource(R.string.notes_list)) })
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF202020)).onSizeChanged { viewSize = it }) {
                if (reader == null || meta == null) {
                    Text("Mosaic not ready", Modifier.align(Alignment.Center), color = Color.White)
                } else {
                    val ppm = meta.pxPerMm
                    // Choose the pyramid level whose pixels are closest to one screen pixel.
                    val level = if (scale <= 0f) 0 else floor(ln(1f / scale) / ln(2f)).toInt().coerceIn(0, meta.levels - 1)
                    val z = 1 shl level
                    val tp = meta.tilePx
                    val visX0 = max(0f, -offset.x / scale); val visY0 = max(0f, -offset.y / scale)
                    val visX1 = min(meta.widthPx.toFloat(), (viewSize.width - offset.x) / scale); val visY1 = min(meta.heightPx.toFloat(), (viewSize.height - offset.y) / scale)
                    val wanted = remember(level, visX0.toInt() / (tp * z), visY0.toInt() / (tp * z), visX1.toInt() / (tp * z), visY1.toInt() / (tp * z)) {
                        val out = ArrayList<Triple<Int, Int, Int>>()
                        if (scale > 0f && visX1 > visX0) for (ty in (visY0 / (tp * z)).toInt()..(visY1 / (tp * z)).toInt().coerceAtMost(meta.tilesY(level) - 1))
                            for (tx in (visX0 / (tp * z)).toInt()..(visX1 / (tp * z)).toInt().coerceAtMost(meta.tilesX(level) - 1)) out.add(Triple(level, tx, ty))
                        out + Triple(meta.levels - 1, 0, 0)
                    }
                    LaunchedEffect(wanted) {
                        for ((l, x, y) in wanted) {
                            val key = "$l/$x/$y"
                            if (images.containsKey(key)) continue
                            val bmp = withContext(Dispatchers.IO) { reader.load(l, x, y) } ?: continue
                            images[key] = bmp.asImageBitmap()
                        }
                    }

                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(meta) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val ns = (scale * zoom).coerceIn(minScale, maxScale)
                                    val k = ns / scale
                                    offset = (offset - centroid) * k + centroid + pan
                                    scale = ns
                                }
                            }
                            .pointerInput(notes, meta) {
                                detectTapGestures { tap ->
                                    val px = (tap.x - offset.x) / scale; val py = (tap.y - offset.y) / scale
                                    val mmX = px / ppm; val mmY = (meta.heightPx - py) / ppm
                                    selected = notes.firstOrNull { abs(mmX - (it.x + it.w / 2)) <= it.w / 2 && abs(mmY - (it.y + it.h / 2)) <= it.h / 2 }
                                }
                            },
                    ) {
                        // Whole-wall background first, then sharper tiles on top.
                        images["${meta.levels - 1}/0/0"]?.let { img ->
                            val top = meta.levels - 1
                            val zs = (1 shl top).toFloat()
                            drawImage(
                                img, IntOffset.Zero, IntSize(img.width, img.height),
                                IntOffset(offset.x.toInt(), offset.y.toInt()),
                                IntSize((img.width * zs * scale).toInt().coerceAtLeast(1), (img.height * zs * scale).toInt().coerceAtLeast(1)),
                                filterQuality = FilterQuality.Low,
                            )
                        }
                        for ((l, x, y) in wanted) {
                            val img = images["$l/$x/$y"] ?: continue
                            val zl = (1 shl l).toFloat()
                            drawImage(
                                img, IntOffset.Zero, IntSize(img.width, img.height),
                                IntOffset((offset.x + x * tp * zl * scale).toInt(), (offset.y + y * tp * zl * scale).toInt()),
                                IntSize((img.width * zl * scale).toInt().coerceAtLeast(1), (img.height * zl * scale).toInt().coerceAtLeast(1)),
                                filterQuality = FilterQuality.Low,
                            )
                        }
                        if (showNotes || showText) for (n in notes) {
                            val w = (n.w * ppm * scale).toFloat(); val h = (n.h * ppm * scale).toFloat()
                            val left = (offset.x + n.x * ppm * scale).toFloat()
                            val top = (offset.y + (meta.heightPx - (n.y + n.h) * ppm) * scale).toFloat()
                            if (left > size.width || top > size.height || left + w < 0 || top + h < 0) continue
                            if (showNotes) rotate(-n.rotation.toFloat(), Offset(left + w / 2, top + h / 2)) {
                                drawRect(NOTE_SWATCH[n.userColour ?: n.colour] ?: Color.White, Offset(left, top), Size(w, h), style = Stroke(3f))
                            }
                            if (showText) {
                                val text = n.userText ?: n.ocrText
                                drawIntoCanvas { c ->
                                    val p = android.graphics.Paint().apply { color = android.graphics.Color.BLACK; textSize = max(10f, h / 8); setShadowLayer(3f, 0f, 0f, android.graphics.Color.WHITE) }
                                    text.lines().forEachIndexed { i, line -> c.nativeCanvas.drawText(line, left + 4, top + p.textSize * (i + 1), p) }
                                }
                            }
                        }
                    }
                }
            }
            if (showList) {
                val sorted = when (sort) {
                    "Colour" -> notes.sortedBy { it.userColour ?: it.colour }
                    "Cluster" -> notes.sortedBy { it.clusterId }
                    else -> notes.sortedWith(compareBy({ -(it.y / 150).toInt() }, { it.x }))
                }
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in listOf("Position", "Colour", "Cluster")) FilterChip(sort == s, { sort = s }, { Text(s) })
                }
                LazyColumn(Modifier.height(220.dp)) {
                    items(sorted, key = { it.uid }) { n ->
                        Row(Modifier.fillMaxWidth().clickable { focus(n); selected = n }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(16.dp).background(NOTE_SWATCH[n.userColour ?: n.colour] ?: Color.White))
                            Text((n.userText ?: n.ocrText).replace('\n', ' ').ifBlank { "(no text)" }, Modifier.padding(start = 10.dp), maxLines = 2)
                        }
                    }
                }
            }
        }
    }

    selected?.let { n -> NoteCard(vm, target.sessionId, n, onClose = { selected = null }) }
}

@Composable
private fun NoteCard(vm: AppViewModel, sessionId: String, n: NoteEntity, onClose: () -> Unit) {
    var text by remember(n.uid) { mutableStateOf(n.userText ?: n.ocrText) }
    var colour by remember(n.uid) { mutableStateOf(n.userColour ?: n.colour) }
    val crop = remember(n.uid) { vm.cropFile(sessionId, n)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Note ${n.noteId}  (cluster ${n.clusterId + 1})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (crop != null) Image(crop, null, Modifier.fillMaxWidth().height(140.dp))
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Text (confidence ${(n.ocrConfidence * 100).toInt()}%)") })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (c in NOTE_COLOURS) Box(
                        Modifier.size(32.dp).background(NOTE_SWATCH.getValue(c)).clickable { colour = c }
                            .then(if (c == colour) Modifier.padding(0.dp) else Modifier),
                    ) { if (c == colour) Text("✓", Modifier.align(Alignment.Center)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.editNote(n, text, colour); onClose() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
