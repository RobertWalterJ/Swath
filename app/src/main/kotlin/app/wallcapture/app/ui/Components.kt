package app.wallcapture.app.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale

/** The main action on a screen: one per screen, a full-width pill. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Box(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A calm second choice next to a primary button. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Box(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A 48 dp round icon button that sits on top of a picture. */
@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface),
    ) { Icon(icon, description) }
}

/** The four capture stages as a rail of dots. The current stage is long and named. */
@Composable
fun StepRail(labels: List<String>, current: Int, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val on = if (onDark) Color.White else MaterialTheme.colorScheme.primary
    val off = if (onDark) Color.White.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outline
    Row(modifier.semantics { contentDescription = "Step ${current + 1} of ${labels.size}: ${labels[current]}" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            if (i == current) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(on).padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("${i + 1}  $label", style = MaterialTheme.typography.labelMedium, color = if (onDark) Color(0xFF16181D) else MaterialTheme.colorScheme.onPrimary)
                }
            } else {
                Box(Modifier.size(if (i < current) 10.dp else 8.dp).clip(CircleShape).background(if (i < current) on else off))
            }
        }
    }
}

/** A swatch with the colour's first letter inside, so colour is never the only cue. */
@Composable
fun NoteSwatch(name: String, modifier: Modifier = Modifier, size: Int = 28) {
    Box(
        modifier.size(size.dp).clip(RoundedCornerShape(6.dp)).background(NoteColours.of(name))
            .border(1.dp, Color(0x33000000), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(NoteColours.letter(name), style = MaterialTheme.typography.labelSmall, color = Color(0xFF16181D))
    }
}

// ---- Read aloud ----------------------------------------------------------------------------------------------------

class Speech(private val tts: TextToSpeech?) {
    fun speak(text: String) { tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "swath") }
    fun stop() { tts?.stop() }
}

val LocalSpeech = compositionLocalOf<Speech?> { null }

/** Provides one text-to-speech engine to the whole app. */
@Composable
fun ProvideSpeech(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val holder = remember {
        object { var tts: TextToSpeech? = null }
    }
    val speech = remember {
        holder.tts = TextToSpeech(ctx.applicationContext) { status -> if (status == TextToSpeech.SUCCESS) holder.tts?.language = Locale.getDefault() }
        Speech(holder.tts)
    }
    DisposableEffect(Unit) { onDispose { holder.tts?.stop(); holder.tts?.shutdown() } }
    CompositionLocalProvider(LocalSpeech provides speech, content = content)
}

/** A visible speaker icon that reads [text] aloud. Put one beside anything longer than a line. */
@Composable
fun SpeakerButton(text: String, modifier: Modifier = Modifier, label: String = "Listen") {
    val speech = LocalSpeech.current
    IconButton(onClick = { speech?.speak(text) }, modifier = modifier.size(48.dp)) {
        Icon(Icons.Rounded.VolumeUp, label, tint = MaterialTheme.colorScheme.primary)
    }
}
