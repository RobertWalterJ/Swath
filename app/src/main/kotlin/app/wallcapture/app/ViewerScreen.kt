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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.wallcapture.app.ui.Copy
import app.wallcapture.app.ui.NoteColours
import app.wallcapture.app.ui.NoteSwatch
import app.wallcapture.app.ui.PrimaryButton
import app.wallcapture.app.ui.QuietButton
import app.wallcapture.app.ui.RoundIconButton
import app.wallcapture.app.ui.Space
import app.wallcapture.app.ui.SpeakerButton
import app.wallcapture.storage.NoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

private sealed interface NoteListItem {
    data class Header(val title: String) : NoteListItem
    data class Row(val note: NoteEntity) : NoteListItem
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: AppViewModel, target: Screen.Viewer) {
    val session by produceState<app.wallcapture.storage.SessionEntity?>(null, target.sessionId) { value = vm.dao.get(target.sessionId) }
    val notes by vm.dao.observeNotes(target.sessionId).collectAsState(emptyList())
    val reader = remember(target.sessionId) { vm.tileReader(target.sessionId) }

    var notesMode by remember { mutableStateOf(false) }
    var showOutlines by remember { mutableStateOf(true) }
    var showText by remember { mutableStateOf(false) }
    var layersMenu by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<NoteEntity?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf(0) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(0f) } // screen px per level-0 mosaic px
    var offset by remember { mutableStateOf(Offset.Zero) } // screen position of the mosaic's top-left
    val images = remember(reader) { mutableStateMapOf<String, ImageBitmap>() }

    val meta = reader?.meta
    val minScale = if (meta != null && viewSize.width > 0) min(viewSize.width.toFloat() / meta.widthPx, viewSize.height.toFloat() / meta.heightPx) else 1f
    val maxScale = 4f

    LaunchedEffect(viewSize, meta) {
        if (meta != null && scale == 0f && viewSize.width > 0 && viewSize.height > 0) {
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
        notes.firstOrNull { it.noteId == id }?.let { if (viewSize.width > 0 && viewSize.height > 0) focus(it) }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        // Top bar: back, title, one Share button.
        Row(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(Icons.Rounded.ArrowBack, "Back", { vm.screen = Screen.Library }, container = MaterialTheme.colorScheme.surfaceVariant)
            Text(session?.title ?: "", Modifier.weight(1f).padding(horizontal = Space.md), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            PrimaryButton(Copy.SHARE, onClick = { sharing = true }, icon = Icons.Rounded.Share, enabled = reader != null)
        }

        // The wall.
        Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).onSizeChanged { viewSize = it }) {
            if (reader == null || meta == null) {
                Text(Copy.NOT_READY, Modifier.align(Alignment.Center), style = MaterialTheme.typography.bodyLarge)
            } else {
                val ppm = meta.pxPerMm
                // Choose the pyramid level whose pixels are closest to one screen pixel.
                val level = if (scale <= 0f) 0 else floor(ln(1f / scale) / ln(2f)).toInt().coerceIn(0, meta.levels - 1)
                val z = 1 shl level
                val tp = meta.tilePx
                val visX0 = max(0f, -offset.x / max(scale, 1e-6f)); val visY0 = max(0f, -offset.y / max(scale, 1e-6f))
                val visX1 = min(meta.widthPx.toFloat(), (viewSize.width - offset.x) / max(scale, 1e-6f)); val visY1 = min(meta.heightPx.toFloat(), (viewSize.height - offset.y) / max(scale, 1e-6f))
                val wanted = remember(level, visX0.toInt() / (tp * z), visY0.toInt() / (tp * z), visX1.toInt() / (tp * z), visY1.toInt() / (tp * z)) {
                    val out = ArrayList<Triple<Int, Int, Int>>()
                    if (scale > 0f && visX1 > visX0 && visY1 > visY0) for (ty in (visY0 / (tp * z)).toInt()..(visY1 / (tp * z)).toInt().coerceAtMost(meta.tilesY(level) - 1))
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
                                if (scale <= 0f) return@detectTransformGestures
                                val ns = (scale * zoom).coerceIn(minScale, maxScale)
                                val k = ns / scale
                                offset = (offset - centroid) * k + centroid + pan
                                scale = ns
                            }
                        }
                        .pointerInput(notes, meta) {
                            detectTapGestures { tap ->
                                if (scale <= 0f) return@detectTapGestures
                                val px = (tap.x - offset.x) / scale; val py = (tap.y - offset.y) / scale
                                val mmX = px / ppm; val mmY = (meta.heightPx - py) / ppm
                                selected = notes.firstOrNull { abs(mmX - (it.x + it.w / 2)) <= it.w / 2 && abs(mmY - (it.y + it.h / 2)) <= it.h / 2 }
                            }
                        },
                ) {
                    // Whole-wall background first, then sharper tiles on top.
                    images["${meta.levels - 1}/0/0"]?.let { img ->
                        val zs = (1 shl (meta.levels - 1)).toFloat()
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
                    if (showOutlines || showText) for (n in notes) {
                        val w = (n.w * ppm * scale).toFloat(); val h = (n.h * ppm * scale).toFloat()
                        val left = (offset.x + n.x * ppm * scale).toFloat()
                        val top = (offset.y + (meta.heightPx - (n.y + n.h) * ppm) * scale).toFloat()
                        if (left > size.width || top > size.height || left + w < 0 || top + h < 0) continue
                        if (showOutlines) rotate(-n.rotation.toFloat(), Offset(left + w / 2, top + h / 2)) {
                            drawRect(Color.White, Offset(left - 1.5f, top - 1.5f), Size(w + 3f, h + 3f), style = Stroke(6f))
                            drawRect(Color(0xFF3441D6), Offset(left, top), Size(w, h), style = Stroke(3f))
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

                // Scale bar, bottom left. It always shows a round length.
                if (scale > 0f) {
                    val mmOnScreen = scale * ppm
                    val options = listOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0)
                    val pick = options.lastOrNull { it * 1000 * mmOnScreen <= viewSize.width * 0.3 } ?: options.first()
                    val barDp = with(LocalDensity.current) { (pick * 1000 * mmOnScreen).toFloat().toDp() }
                    Column(Modifier.align(Alignment.BottomStart).padding(Space.lg).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)).padding(horizontal = Space.md, vertical = Space.sm)) {
                        Text(if (pick < 1) "${(pick * 100).toInt()} cm" else "${pick.toInt()} m", style = MaterialTheme.typography.labelSmall)
                        Box(Modifier.width(barDp.coerceAtLeast(8.dp)).height(4.dp).background(MaterialTheme.colorScheme.onSurface))
                    }
                }

                // Layers: one small button, two switches.
                Box(Modifier.align(Alignment.BottomEnd).padding(Space.lg)) {
                    RoundIconButton(Icons.Rounded.Layers, Copy.LAYERS, { layersMenu = true })
                    DropdownMenu(expanded = layersMenu, onDismissRequest = { layersMenu = false }) {
                        DropdownMenuItem(text = { Text(Copy.LAYER_OUTLINES) }, onClick = { showOutlines = !showOutlines }, trailingIcon = { Switch(showOutlines, { showOutlines = it }) })
                        DropdownMenuItem(text = { Text(Copy.LAYER_TEXT) }, onClick = { showText = !showText }, trailingIcon = { Switch(showText, { showText = it }) })
                    }
                }
            }
        }

        // Mode switch, and the notes panel when it is open.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md)) {
            SegmentedButton(!notesMode, { notesMode = false }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(Copy.MODE_WALL) }
            SegmentedButton(notesMode, { notesMode = true }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("${Copy.MODE_NOTES}  ${notes.size}") }
        }
        if (notesMode) {
            Column(Modifier.fillMaxWidth().height(300.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = Space.lg)) {
                    listOf(Copy.GROUP_THEME, Copy.GROUP_COLOUR, Copy.GROUP_POSITION).forEachIndexed { i, label ->
                        SegmentedButton(group == i, { group = i }, SegmentedButtonDefaults.itemShape(i, 3)) { Text(label) }
                    }
                }
                val listItems = remember(notes, group) { buildItems(notes, group) }
                if (notes.isEmpty()) Text(Copy.NO_NOTES, Modifier.padding(Space.lg), style = MaterialTheme.typography.bodyLarge)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = Space.lg)) {
                    items(listItems) { item ->
                        when (item) {
                            is NoteListItem.Header -> Text(item.title, Modifier.padding(horizontal = Space.lg, vertical = Space.sm).padding(top = Space.sm), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            is NoteListItem.Row -> NoteRow(item.note) { focus(item.note); selected = item.note }
                        }
                    }
                }
            }
        }
    }

    selected?.let { n -> NoteSheet(vm, target.sessionId, n, onClose = { selected = null }) }
    if (sharing) session?.let { s -> ShareSheet(vm, s, notes, reader, onClose = { sharing = false }) }
}

private fun buildItems(notes: List<NoteEntity>, group: Int): List<NoteListItem> = when (group) {
    0 -> notes.groupBy { it.clusterId }.entries.sortedByDescending { it.value.size }.flatMapIndexed { i, (_, list) ->
        listOf<NoteListItem>(NoteListItem.Header("Theme ${i + 1}  ·  ${list.size} notes")) + list.map { NoteListItem.Row(it) }
    }
    1 -> notes.groupBy { it.userColour ?: it.colour }.entries.sortedByDescending { it.value.size }.flatMap { (c, list) ->
        listOf<NoteListItem>(NoteListItem.Header("${NoteColours.label(c)}  ·  ${list.size} notes")) + list.map { NoteListItem.Row(it) }
    }
    else -> notes.sortedWith(compareBy({ -(it.y / 150).toInt() }, { it.x })).map { NoteListItem.Row(it) }
}

@Composable
private fun NoteRow(n: NoteEntity, onClick: () -> Unit) {
    val colour = n.userColour ?: n.colour
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.lg, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
        NoteSwatch(colour)
        Column(Modifier.padding(start = Space.md)) {
            Text((n.userText ?: n.ocrText).replace('\n', ' ').ifBlank { Copy.NO_TEXT }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text(NoteColours.label(colour), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteSheet(vm: AppViewModel, sessionId: String, n: NoteEntity, onClose: () -> Unit) {
    var text by remember(n.uid) { mutableStateOf(n.userText ?: n.ocrText) }
    var colour by remember(n.uid) { mutableStateOf(n.userColour ?: n.colour) }
    val crop = remember(n.uid) { vm.cropFile(sessionId, n)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = Space.edge).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            if (crop != null) Image(crop, null, Modifier.fillMaxWidth().height(170.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant))
            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(text, { text = it }, Modifier.weight(1f), label = { Text(Copy.NOTE_TEXT) }, minLines = 2)
                SpeakerButton(text, Modifier.padding(top = Space.sm), label = Copy.LISTEN)
            }
            Text(Copy.NOTE_COLOUR, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(NoteColours.all) { c ->
                    FilterChip(colour == c, { colour = c }, { Text(NoteColours.label(c)) }, leadingIcon = { NoteSwatch(c, size = 22) })
                }
            }
            PrimaryButton(Copy.SAVE, onClick = { vm.editNote(n, text, colour); onClose() }, modifier = Modifier.fillMaxWidth())
            QuietButton(Copy.CANCEL, onClick = onClose, modifier = Modifier.fillMaxWidth())
        }
    }
}

