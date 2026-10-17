package app.wallcapture.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.wallcapture.app.ui.Copy
import app.wallcapture.app.ui.NoteColours
import app.wallcapture.app.ui.PrimaryButton
import app.wallcapture.app.ui.RoundIconButton
import app.wallcapture.app.ui.Space
import app.wallcapture.storage.SessionEntity
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val sessions by vm.sessions.collectAsState()
    var manage by remember { mutableStateOf<SessionEntity?>(null) }
    var settings by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(pad),
            contentPadding = PaddingValues(start = Space.edge, end = Space.edge, top = Space.lg, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Copy.HOME_TITLE, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                    RoundIconButton(Icons.Rounded.Settings, Copy.SETTINGS, { settings = true }, container = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            item { SearchField(onClick = { vm.screen = Screen.Search }) }
            if (sessions.isEmpty()) {
                item { EmptyHome(onNew = { vm.newSession() }) }
            } else {
                item { PrimaryButton(Copy.NEW_WALL, onClick = { vm.newSession() }, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Add) }
                items(sessions, key = { it.id }) { s -> WallCard(vm, s, onManage = { manage = s }) }
            }
        }
    }

    manage?.let { s -> ManageSheet(vm, s, onClose = { manage = null }) }
    if (settings) SettingsSheet(vm, onClose = { settings = false })
}

@Composable
private fun SearchField(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick).padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(Copy.SEARCH_HINT, Modifier.padding(start = Space.md), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyHome(onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.xl), horizontalAlignment = Alignment.Start, verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.7f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
            val pad = size.width * 0.08f
            drawRoundRect(Color(0xFFD9C7A6), Offset(pad, pad), Size(size.width - 2 * pad, size.height - 2 * pad), CornerRadius(18f))
            val names = listOf("yellow", "pink", "green", "blue", "orange", "purple", "yellow", "green", "pink", "blue")
            val cols = 5
            val cell = (size.width - 2 * pad) / cols
            for (i in names.indices) {
                val r = i / cols; val c = i % cols
                val s = cell * 0.52f
                drawRoundRect(NoteColours.of(names[i]), Offset(pad + c * cell + (cell - s) / 2, pad + cell * 0.25f + r * cell * 0.95f), Size(s, s), CornerRadius(5f))
            }
        }
        Text(Copy.EMPTY_TITLE, style = MaterialTheme.typography.headlineMedium)
        Text(Copy.EMPTY_BODY, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PrimaryButton(Copy.NEW_WALL, onClick = onNew, modifier = Modifier.fillMaxWidth(), icon = Icons.Rounded.Add)
    }
}

@Composable
private fun WallCard(vm: AppViewModel, s: SessionEntity, onManage: () -> Unit) {
    val busy = vm.progress[s.id]
    val notes by produceState(0, s.id, s.status) { value = vm.noteCount(s.id) }
    val thumb = remember(s.id, s.status) {
        File(vm.store.mosaic(s.id), "preview.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
    val ready = s.status == "ready" && busy == null
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
            .clickable {
                when {
                    ready -> vm.screen = Screen.Viewer(s.id)
                    busy != null || s.status == "processing" -> vm.screen = Screen.Processing(s.id)
                }
            },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2.3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.TopEnd).padding(Space.sm)) {
                RoundIconButton(Icons.Rounded.MoreHoriz, Copy.MANAGE, onManage)
            }
        }
        Column(Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium)
            when {
                busy != null || s.status == "processing" -> {
                    Text(Copy.STATUS_WORKING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Space.xs))
                }
                s.status == "failed" -> {
                    Text(Copy.STATUS_FAILED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.process(s.id) }, modifier = Modifier.padding(0.dp)) { Text(Copy.TRY_AGAIN) }
                }
                s.status == "capturing" -> {
                    TextButton(onClick = { vm.process(s.id) }) { Text(Copy.MAKE_WALL) }
                }
                else -> Text(
                    "%.1f m wide  ·  %d notes  ·  %s".format(s.wallWidthMm / 1000.0, notes, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(s.createdAt))),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Sheets --------------------------------------------------------------------------------------------------------

@Composable
private fun SheetRow(icon: ImageVector, title: String, help: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = Space.edge, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Column(Modifier.padding(start = Space.lg)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            if (help != null) Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManageSheet(vm: AppViewModel, s: SessionEntity, onClose: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = Space.xxl)) {
            Text(s.title, Modifier.padding(horizontal = Space.edge, vertical = Space.sm), style = MaterialTheme.typography.titleLarge)
            SheetRow(Icons.Rounded.Edit, Copy.RENAME) { renaming = true }
            SheetRow(Icons.Rounded.ContentCopy, Copy.COPY) { vm.duplicate(s.id); onClose() }
            SheetRow(Icons.Rounded.Inventory2, Copy.FREE_SPACE, Copy.FREE_SPACE_HELP) { vm.freeUp(s.id); onClose() }
            HorizontalDivider(Modifier.padding(vertical = Space.sm), color = MaterialTheme.colorScheme.outlineVariant)
            SheetRow(Icons.Rounded.Delete, Copy.DELETE, Copy.DELETE_HELP, danger = true) { vm.delete(s.id); onClose() }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(Copy.RENAME) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { vm.rename(s.id, text); renaming = false; onClose() }) { Text(Copy.SAVE) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(Copy.CANCEL) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(vm: AppViewModel, onClose: () -> Unit) {
    val deleted by vm.deleted.collectAsState()
    var level by remember { mutableStateOf(vm.pxPerMm) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = Space.xxl)) {
            Text(Copy.SETTINGS, Modifier.padding(horizontal = Space.edge, vertical = Space.sm), style = MaterialTheme.typography.titleLarge)
            Text(Copy.DETAIL_TITLE, Modifier.padding(horizontal = Space.edge, vertical = Space.sm), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(
                Triple(3.0, Copy.DETAIL_STANDARD, Copy.DETAIL_STANDARD_HELP),
                Triple(4.0, Copy.DETAIL_HIGH, Copy.DETAIL_HIGH_HELP),
                Triple(6.0, Copy.DETAIL_MAX, Copy.DETAIL_MAX_HELP),
            ).forEach { (v, name, help) ->
                Row(Modifier.fillMaxWidth().clickable { level = v; vm.pxPerMm = v }.padding(horizontal = Space.edge, vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (level == v) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                        if (level == v) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Column(Modifier.padding(start = Space.lg)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = Space.sm), color = MaterialTheme.colorScheme.outlineVariant)
            SheetRow(Icons.Rounded.HelpOutline, Copy.HOW_IT_WORKS) { vm.showHowTo = true; onClose() }
            if (deleted.isNotEmpty()) {
                Text(Copy.DELETED_TITLE, Modifier.padding(horizontal = Space.edge, vertical = Space.sm).semantics { contentDescription = Copy.DELETED_TITLE }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                deleted.forEach { d -> SheetRow(Icons.Rounded.Restore, d.title, Copy.RESTORE) { vm.restore(d.id) } }
            }
        }
    }
}
