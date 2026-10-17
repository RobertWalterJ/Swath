package app.wallcapture.core.model

/** Box in wall mm, origin bottom-left of the captured extent. [rotationDeg] is counter-clockwise. */
data class BoxMm(val x: Double, val y: Double, val w: Double, val h: Double, val rotationDeg: Double = 0.0) {
    val centreX get() = x + w / 2
    val centreY get() = y + h / 2
}

val NOTE_COLOURS = listOf("yellow", "pink", "orange", "green", "blue", "purple", "white")

data class Note(
    val id: String,
    val boxMm: BoxMm,
    val colour: String,
    val clusterId: Int,
    val ocrText: String = "",
    val ocrConfidence: Double = 0.0,
    val userText: String? = null,
    val userColour: String? = null,
) {
    val text get() = userText ?: ocrText
    val shownColour get() = userColour ?: colour
    val edited get() = userText != null || userColour != null
}

data class TextBlock(
    val id: String,
    val boxMm: BoxMm,
    val ocrText: String,
    val confidence: Double,
    val noteId: String? = null,
    val userText: String? = null,
    val needsReview: Boolean = false,
)

data class Cluster(val id: Int, val label: String? = null, val colour: String? = null)

/** One OCR line in image pixels. */
data class TextLine(val text: String, val confidence: Double, val x: Double, val y: Double, val w: Double, val h: Double)

data class SessionMeta(
    val id: String,
    val title: String,
    val createdAt: String,
    val location: String? = null,
    val tags: List<String> = emptyList(),
    val wallWidthMm: Double = 0.0,
    val wallHeightMm: Double = 0.0,
    val pxPerMm: Double = 4.0,
    val status: String = "capturing",
)
