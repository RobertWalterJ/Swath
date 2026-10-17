package app.wallcapture.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wallcapture.core.model.NOTE_COLOURS
import app.wallcapture.storage.SearchHit

/** Full-text search across every session, with colour and date filters. Tapping a hit jumps to it on the wall. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf<Int?>(null) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }

    LaunchedEffect(query, colour, days) { hits = if (query.isBlank() && colour == null) emptyList() else vm.search(query, colour, days) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search)) },
                navigationIcon = { IconButton(onClick = { vm.screen = Screen.Library }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.search_hint)) })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(colour == null, { colour = null }, { Text("Any colour") })
            }
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(NOTE_COLOURS) { c -> FilterChip(colour == c, { colour = if (colour == c) null else c }, { Text(c) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(days == null, { days = null }, { Text("Any time") })
                FilterChip(days == 7, { days = 7 }, { Text("7 days") })
                FilterChip(days == 30, { days = 30 }, { Text("30 days") })
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(hits, key = { it.sessionId + it.noteId }) { h ->
                    Column(Modifier.fillMaxWidth().clickable { vm.screen = Screen.Viewer(h.sessionId, h.noteId) }.padding(vertical = 6.dp)) {
                        Text(h.text.replace('\n', ' ').ifBlank { "(no text)" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                        Text("${h.sessionTitle}  ·  ${h.colour}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
