package app.wallcapture.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.wallcapture.app.ui.Copy
import app.wallcapture.app.ui.NoteColours
import app.wallcapture.app.ui.NoteSwatch
import app.wallcapture.app.ui.RoundIconButton
import app.wallcapture.app.ui.Space
import app.wallcapture.storage.SearchHit

/** Search every note on every wall. Filters sit under the field and stay out of the way until needed. */
@Composable
fun SearchScreen(vm: AppViewModel) {
    var query by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf<String?>(null) }
    var days by remember { mutableStateOf<Int?>(null) }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query, colour, days) { hits = if (query.isBlank() && colour == null) emptyList() else vm.search(query, colour, days) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.edge, vertical = Space.lg), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            RoundIconButton(Icons.Rounded.ArrowBack, "Back", { vm.screen = Screen.Library }, container = MaterialTheme.colorScheme.surfaceVariant)
            OutlinedTextField(
                query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                placeholder = { Text(Copy.SEARCH_HINT) }, shape = RoundedCornerShape(28.dp),
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            item { FilterChip(colour == null, { colour = null }, { Text(Copy.ANY_COLOUR) }) }
            items(NoteColours.all) { c -> FilterChip(colour == c, { colour = if (colour == c) null else c }, { Text(NoteColours.label(c)) }, leadingIcon = { NoteSwatch(c, size = 20) }) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            item { FilterChip(days == null, { days = null }, { Text(Copy.ANY_TIME) }) }
            item { FilterChip(days == 7, { days = 7 }, { Text(Copy.LAST_7) }) }
            item { FilterChip(days == 30, { days = 30 }, { Text(Copy.LAST_30) }) }
        }
        when {
            query.isBlank() && colour == null -> Text(Copy.SEARCH_EMPTY, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            hits.isEmpty() -> Text(Copy.SEARCH_NONE, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(hits, key = { it.sessionId + it.noteId }) { h ->
                    Row(Modifier.fillMaxWidth().clickable { vm.screen = Screen.Viewer(h.sessionId, h.noteId) }.padding(vertical = Space.md), verticalAlignment = Alignment.CenterVertically) {
                        NoteSwatch(h.colour)
                        Column(Modifier.padding(start = Space.md)) {
                            Text(h.text.replace('\n', ' ').ifBlank { Copy.NO_TEXT }, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                            Text(h.sessionTitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
