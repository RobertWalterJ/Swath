package app.wallcapture.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import app.wallcapture.app.ui.Copy
import app.wallcapture.app.ui.NoteColours
import app.wallcapture.app.ui.PrimaryButton
import app.wallcapture.app.ui.SpeakerButton
import kotlinx.coroutines.launch

/** Three plain steps, each with a picture, a short text and a speaker icon. Nothing advances by itself. */
@Composable
fun HowToOverlay(onDone: () -> Unit) {
    val titles = listOf(Copy.HOWTO_1_TITLE, Copy.HOWTO_2_TITLE, Copy.HOWTO_3_TITLE)
    val bodies = listOf(Copy.HOWTO_1_BODY, Copy.HOWTO_2_BODY, Copy.HOWTO_3_BODY)
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == 2

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDone) { Text(Copy.SKIP, style = MaterialTheme.typography.labelLarge) }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
                Canvas(Modifier.fillMaxWidth().aspectRatio(1.25f).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    when (page) { 0 -> drawAim(); 1 -> drawWalk(); else -> drawCheck() }
                }
                Box(Modifier.size(24.dp))
                Text(titles[page], style = MaterialTheme.typography.headlineMedium)
                Box(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(bodies[page], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 12.dp))
                    SpeakerButton(titles[page] + ". " + bodies[page], label = Copy.LISTEN)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.Center) {
            repeat(3) { i ->
                Box(
                    Modifier.padding(4.dp).size(if (i == pager.currentPage) 10.dp else 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                )
            }
        }
        PrimaryButton(
            text = if (last) Copy.START else Copy.NEXT,
            onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val ink = Color(0xFF16181D)
private val accent = Color(0xFF3441D6)

private fun DrawScope.wall(left: Float, top: Float, w: Float, h: Float) {
    drawRoundRect(Color(0xFFD9C7A6), Offset(left, top), Size(w, h), CornerRadius(14f))
    val names = listOf("yellow", "pink", "green", "blue", "orange", "purple", "yellow", "green")
    var i = 0
    val cell = w / 4
    for (r in 0..1) for (c in 0..3) {
        val s = cell * 0.5f
        drawRoundRect(NoteColours.of(names[i++ % names.size]), Offset(left + c * cell + cell * 0.25f, top + h * 0.12f + r * h * 0.45f), Size(s, s), CornerRadius(4f))
    }
}

private fun DrawScope.phone(cx: Float, cy: Float, s: Float) {
    drawRoundRect(ink, Offset(cx - s * 0.28f, cy - s * 0.5f), Size(s * 0.56f, s), CornerRadius(s * 0.1f))
    drawRoundRect(Color(0xFFF1EFE9), Offset(cx - s * 0.23f, cy - s * 0.43f), Size(s * 0.46f, s * 0.82f), CornerRadius(s * 0.05f))
}

private fun DrawScope.drawAim() {
    wall(size.width * 0.12f, size.height * 0.1f, size.width * 0.76f, size.height * 0.45f)
    val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))
    drawLine(accent, Offset(size.width * 0.5f, size.height * 0.62f), Offset(size.width * 0.5f, size.height * 0.78f), strokeWidth = 5f, pathEffect = dash, cap = StrokeCap.Round)
    phone(size.width * 0.5f, size.height * 0.88f, size.height * 0.24f)
    // Aim brackets.
    val l = size.width * 0.34f; val t = size.height * 0.16f; val r = size.width * 0.66f; val b = size.height * 0.5f; val k = 28f
    drawLine(accent, Offset(l, t + k), Offset(l, t), 6f, StrokeCap.Round); drawLine(accent, Offset(l, t), Offset(l + k, t), 6f, StrokeCap.Round)
    drawLine(accent, Offset(r - k, t), Offset(r, t), 6f, StrokeCap.Round); drawLine(accent, Offset(r, t), Offset(r, t + k), 6f, StrokeCap.Round)
    drawLine(accent, Offset(l, b - k), Offset(l, b), 6f, StrokeCap.Round); drawLine(accent, Offset(l, b), Offset(l + k, b), 6f, StrokeCap.Round)
    drawLine(accent, Offset(r - k, b), Offset(r, b), 6f, StrokeCap.Round); drawLine(accent, Offset(r, b - k), Offset(r, b), 6f, StrokeCap.Round)
}

private fun DrawScope.drawWalk() {
    wall(size.width * 0.08f, size.height * 0.1f, size.width * 0.84f, size.height * 0.45f)
    phone(size.width * 0.3f, size.height * 0.84f, size.height * 0.24f)
    drawLine(accent, Offset(size.width * 0.42f, size.height * 0.84f), Offset(size.width * 0.82f, size.height * 0.84f), 7f, StrokeCap.Round)
    drawLine(accent, Offset(size.width * 0.82f, size.height * 0.84f), Offset(size.width * 0.76f, size.height * 0.79f), 7f, StrokeCap.Round)
    drawLine(accent, Offset(size.width * 0.82f, size.height * 0.84f), Offset(size.width * 0.76f, size.height * 0.89f), 7f, StrokeCap.Round)
}

private fun DrawScope.drawCheck() {
    // A coverage strip with one gap, then a finger-sized marker on the gap.
    val left = size.width * 0.08f; val top = size.height * 0.3f; val w = size.width * 0.84f; val h = size.height * 0.2f
    drawRoundRect(Color(0xFF202020), Offset(left, top), Size(w, h), CornerRadius(10f))
    drawRoundRect(Color(0xFF3FA34D), Offset(left, top), Size(w * 0.42f, h), CornerRadius(10f))
    drawRoundRect(Color(0xFF3FA34D), Offset(left + w * 0.58f, top), Size(w * 0.42f, h), CornerRadius(10f))
    // Hatching on the gap edges tells amber from empty without colour.
    var x = left + w * 0.42f
    while (x < left + w * 0.5f) { drawLine(Color(0xFFE0A100), Offset(x, top + h), Offset(x + 12f, top), 4f); x += 14f }
    drawCircle(accent, size.height * 0.07f, Offset(left + w * 0.5f, top + h + size.height * 0.2f))
    drawLine(accent, Offset(left + w * 0.5f, top + h + size.height * 0.13f), Offset(left + w * 0.5f, top + h + 8f), 5f, StrokeCap.Round)
}
