package app.wallcapture.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wallcapture.app.ui.Copy
import app.wallcapture.app.ui.Space
import app.wallcapture.storage.NoteEntity
import app.wallcapture.storage.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Choice(val kind: ExportKind, val icon: ImageVector, val title: String, val help: String, val recommended: Boolean = false)

/** One sheet, three plain choices. File formats for specialists sit under "More ways to share". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(vm: AppViewModel, session: SessionEntity, notes: List<NoteEntity>, reader: TileReader?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val brand = stringResource(R.string.app_name)
    val exporter = remember { ExportActions(ctx, vm.store, brand) }
    val scope = rememberCoroutineScope()
    var building by remember { mutableStateOf<ExportKind?>(null) }
    var failed by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }

    val main = listOf(
        Choice(ExportKind.Report, Icons.Rounded.Description, Copy.SHARE_REPORT, Copy.SHARE_REPORT_HELP, recommended = true),
        Choice(ExportKind.Picture, Icons.Rounded.Image, Copy.SHARE_PICTURE, Copy.SHARE_PICTURE_HELP),
        Choice(ExportKind.Csv, Icons.Rounded.TableChart, Copy.SHARE_NOTES, Copy.SHARE_NOTES_HELP),
    )
    val extra = listOf(
        Choice(ExportKind.Png, Icons.Rounded.PhotoLibrary, Copy.SHARE_PNG, Copy.SHARE_PNG_HELP),
        Choice(ExportKind.Json, Icons.Rounded.DataObject, Copy.SHARE_JSON, Copy.SHARE_JSON_HELP),
        Choice(ExportKind.Zip, Icons.Rounded.Inventory2, Copy.SHARE_ZIP, Copy.SHARE_ZIP_HELP),
    )

    fun run(kind: ExportKind) {
        failed = false
        building = kind
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { exporter.build(kind, session, notes, reader) } }
            building = null
            result.onSuccess { exporter.share(kind, it); onClose() }.onFailure { failed = true }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (building == null) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = Space.edge).padding(bottom = Space.xxl), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            if (building != null) {
                Row(Modifier.fillMaxWidth().padding(vertical = Space.xxl), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(Copy.BUILDING, Modifier.padding(start = Space.lg), style = MaterialTheme.typography.titleMedium)
                }
            } else {
                Text(Copy.SHARE_TITLE, style = MaterialTheme.typography.titleLarge)
                if (failed) Text(Copy.BUILD_FAILED, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                main.forEach { ChoiceCard(it) { run(it.kind) } }
                TextButton(onClick = { more = !more }) {
                    Text(Copy.SHARE_MORE, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Rounded.ExpandMore, null, Modifier.padding(start = Space.xs))
                }
                if (more) extra.forEach { ChoiceCard(it) { run(it.kind) } }
            }
        }
    }
}

@Composable
private fun ChoiceCard(c: Choice, onClick: () -> Unit) {
    val border = if (c.recommended) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(if (c.recommended) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface)
            .border(if (c.recommended) 2.dp else 1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(Space.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(c.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.padding(start = Space.lg).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, style = MaterialTheme.typography.titleMedium)
                if (c.recommended) Text(Copy.RECOMMENDED, Modifier.padding(start = Space.sm), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(c.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
