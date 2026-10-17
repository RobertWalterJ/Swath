package app.wallcapture.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.wallcapture.app.ui.Copy
import app.wallcapture.app.ui.PrimaryButton
import app.wallcapture.app.ui.QuietButton
import app.wallcapture.app.ui.Space

private val STEPS = listOf(Copy.STEP_ALIGN, Copy.STEP_JOIN, Copy.STEP_FIND, Copy.STEP_READ)

private fun stepIndex(message: String?): Int = when (message) {
    null, "" -> 0
    "Reading stills", "Refining poses" -> 0
    "Writing tiles" -> 1
    "Finding notes" -> 2
    "Reading notes" -> 3
    else -> 0
}

/** The long wait gets its own screen: four named steps, one done mark each, and no countdown. */
@Composable
fun ProcessingScreen(vm: AppViewModel, sessionId: String) {
    val sessions by vm.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sessionId }
    val message = vm.progress[sessionId]
    val ready = session?.status == "ready" && message == null
    val failed = session?.status == "failed" && message == null
    val current = if (ready) STEPS.size else stepIndex(message)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Space.edge, vertical = Space.xl),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.lg)) {
            Text(
                when { ready -> Copy.READY_TITLE; failed -> Copy.FAILED_TITLE; else -> Copy.MAKING_TITLE },
                style = MaterialTheme.typography.headlineLarge,
            )
            if (!ready && !failed) Text(Copy.MAKING_BODY, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed) Text(session?.progress.orEmpty().ifBlank { Copy.STATUS_FAILED }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.size(Space.sm))
            if (!failed) STEPS.forEachIndexed { i, label -> StepRow(label, if (i < current) 2 else if (i == current) 1 else 0) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
            when {
                ready -> {
                    PrimaryButton(Copy.OPEN_WALL, onClick = { vm.screen = Screen.Viewer(sessionId) }, modifier = Modifier.fillMaxWidth())
                    QuietButton(Copy.BACK_HOME, onClick = { vm.screen = Screen.Library }, modifier = Modifier.fillMaxWidth())
                }
                failed -> {
                    PrimaryButton(Copy.TRY_AGAIN, onClick = { vm.process(sessionId) }, modifier = Modifier.fillMaxWidth())
                    QuietButton(Copy.BACK_HOME, onClick = { vm.screen = Screen.Library }, modifier = Modifier.fillMaxWidth())
                }
                else -> QuietButton(Copy.BACK_HOME, onClick = { vm.screen = Screen.Library }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/** state: 0 waiting, 1 working, 2 done. A tick, a spinner and an empty ring differ by shape, not only colour. */
@Composable
private fun StepRow(label: String, state: Int) {
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = label + when (state) { 2 -> ", done"; 1 -> ", in progress"; else -> ", waiting" } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            when (state) {
                2 -> Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
                1 -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                else -> Box(Modifier.size(28.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
            }
        }
        Text(
            label, Modifier.padding(start = Space.lg),
            style = MaterialTheme.typography.titleMedium,
            color = if (state == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}
