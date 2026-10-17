package app.wallcapture.app

import app.wallcapture.app.ui.Copy
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The plain-writing standard as a build check: every sentence in the app is 20 words or fewer,
 * titles are 8 words or fewer, and no line uses a banned idiom or a "not X" definition.
 */
class PlainWritingTest {
    private val lines = Copy.all()

    private fun sentences(text: String) = text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    private fun words(text: String) = text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

    @Test
    fun copyIsNotEmpty() = assertTrue(lines.size > 80, "found ${lines.size} lines")

    @Test
    fun everySentenceIsTwentyWordsOrFewer() {
        val long = lines.flatMap { sentences(it) }.filter { words(it) > 20 }
        assertTrue(long.isEmpty(), "Too long: $long")
    }

    @Test
    fun noLineIsATwoParagraphWall() {
        val dense = lines.filter { sentences(it).size > 5 }
        assertTrue(dense.isEmpty(), "More than five sentences: $dense")
    }

    @Test
    fun titlesAreShort() {
        val long = lines.filter { it.length < 40 && !it.endsWith(".") }.filter { words(it) > 8 }
        assertTrue(long.isEmpty(), "Titles over eight words: $long")
    }

    @Test
    fun noIdiomOrNegativeDefinitions() {
        val banned = listOf("piece of cake", "on the fly", "under the hood", "at a glance", "out of the box", "it is not ", "this is not ")
        val bad = lines.filter { l -> banned.any { l.lowercase().contains(it) } }
        assertTrue(bad.isEmpty(), "Banned phrases: $bad")
    }
}
