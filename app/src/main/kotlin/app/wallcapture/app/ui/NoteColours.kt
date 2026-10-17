package app.wallcapture.app.ui

import androidx.compose.ui.graphics.Color
import app.wallcapture.core.model.NOTE_COLOURS

/**
 * The seven sticky-note colours. Colour is never the only cue: each has a name and a letter, and
 * [NoteSwatch] draws the letter inside the swatch.
 */
object NoteColours {
    private val colours = mapOf(
        "yellow" to Color(0xFFFFEB3B),
        "pink" to Color(0xFFFF80AB),
        "orange" to Color(0xFFFF9800),
        "green" to Color(0xFF8BC34A),
        "blue" to Color(0xFF4FC3F7),
        "purple" to Color(0xFFBA68C8),
        "white" to Color(0xFFFAFAFA),
    )

    fun of(name: String): Color = colours[name] ?: Color(0xFFFAFAFA)

    /** ARGB for drawing outside Compose, such as in the PDF. */
    fun argb(name: String): Int = of(name).let {
        (0xFF shl 24) or ((it.red * 255).toInt() shl 16) or ((it.green * 255).toInt() shl 8) or (it.blue * 255).toInt()
    }

    fun label(name: String): String = name.replaceFirstChar { it.uppercase() }
    fun letter(name: String): String = name.take(1).uppercase()
    val all: List<String> get() = NOTE_COLOURS
}
