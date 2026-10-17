package app.wallcapture.app

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wallcapture.storage.SessionEntity
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    val sessions by vm.sessions.collectAsState()
    val deleted by vm.deleted.collectAsState()
    var showDeleted by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { vm.screen = Screen.Search }) { Icon(Icons.Default.Search, stringResource(R.string.search)) }
                    IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.newSession() }) { Text(stringResource(R.string.new_wall)) }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (sessions.isEmpty()) {
                item { Text(stringResource(R.string.library_empty), Modifier.padding(vertical = 32.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            items(sessions, key = { it.id }) { s -> SessionRow(vm, s) }
            if (deleted.isNotEmpty()) {
                item {
                    TextButton(onClick = { showDeleted = !showDeleted }) { Text(stringResource(R.string.recently_deleted) + " (${deleted.size})") }
                }
                if (showDeleted) items(deleted, key = { "d" + it.id }) { s ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(s.title)
                        TextButton(onClick = { vm.restore(s.id) }) { Text(stringResource(R.string.restore)) }
                    }
                }
            }
            item { Box(Modifier.size(80.dp)) }
        }
    }

    if (settings) {
        AlertDialog(
            onDismissRequest = { settings = false },
            confirmButton = { TextButton(onClick = { settings = false }) { Text(stringResource(R.string.save)) } },
            title = { Text("Output resolution") },
            text = {
                Column {
                    for (v in listOf(3.0, 4.0, 6.0)) {
                        Row(Modifier.fillMaxWidth().clickable { vm.pxPerMm = v; settings = false }.padding(vertical = 10.dp)) {
                            Text("${v.toInt()} px per mm" + if (vm.pxPerMm == v) "  (current)" else "")
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun SessionRow(vm: AppViewModel, s: SessionEntity) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val busy = vm.progress[s.id]
    val sizeMb = remember(s.id, s.status) { vm.sizeOf(s.id) / 1_048_576 }
    val thumb = remember(s.id, s.status) {
        File(vm.store.mosaic(s.id), "preview.jpg").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = s.status == "ready") { vm.screen = Screen.Viewer(s.id) },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 96.dp, height = 64.dp)) {
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium)
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.createdAt)), style = MaterialTheme.typography.bodySmall)
                val status = when {
                    busy != null -> stringResource(R.string.processing) + ": " + busy
                    s.status == "failed" -> stringResource(R.string.failed) + ": " + s.progress
                    s.status == "ready" -> "%.1f m wide, %d MB".format(s.wallWidthMm / 1000.0, sizeMb)
                    else -> s.status
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; vm.duplicate(s.id) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.free_space)) }, onClick = { menu = false; vm.freeUp(s.id) })
                    if (s.status == "failed" || s.status == "capturing") {
                        DropdownMenuItem(text = { Text("Process again") }, onClick = { menu = false; vm.process(s.id) })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menu = false; vm.delete(s.id) })
                }
            }
        }
    }
    if (renaming) {
        var text by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(s.id, text); renaming = false }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
