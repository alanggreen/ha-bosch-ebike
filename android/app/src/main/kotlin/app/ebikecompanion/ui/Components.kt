package app.ebikecompanion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val ink @Composable get() = MaterialTheme.colorScheme.onBackground
val ground @Composable get() = MaterialTheme.colorScheme.background
val soft @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant

fun Modifier.topRule(color: Color) = drawBehind { drawRect(color, size = Size(size.width, 2.dp.toPx())) }
fun Modifier.bottomRule(color: Color) =
    drawBehind { drawRect(color, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx())) }

/** Filled holds, half is partial, hollow is not active, dashed is unknown, a cross is a failure. Words always accompany it. */
@Composable
fun Mark(state: MarkState, size: Dp = 24.dp, onAlert: Boolean = false) {
    val i = ink; val g = ground; val s = soft
    Canvas(Modifier.size(size)) {
        val sw = 2.dp.toPx()
        val w = this.size.width; val h = this.size.height
        val inner = Size(w - sw, h - sw); val tl = Offset(sw / 2, sw / 2)
        fun cross(c: Color) { val m = w * 0.27f; drawLine(c, Offset(m, m), Offset(w - m, h - m), sw); drawLine(c, Offset(w - m, m), Offset(m, h - m), sw) }
        when (state) {
            MarkState.OK -> drawRect(i)
            MarkState.WARN -> { drawRect(i, size = Size(w / 2, h)); drawRect(i, tl, inner, style = Stroke(sw)) }
            MarkState.OFF -> drawRect(i, tl, inner, style = Stroke(sw))
            MarkState.UNKNOWN -> drawRect(s, tl, inner, style = Stroke(sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            MarkState.BAD -> if (onAlert) { drawRect(g); cross(Palette.Navy) } else { drawRect(Palette.Alert); drawRect(i, tl, inner, style = Stroke(sw)); cross(Palette.Bone) }
        }
    }
}

/** The one reversed plate at the top of Status: alert red, waiting yellow, or ink. */
@Composable
fun CountPlate(count: Count) {
    val (bg, fg) = when (count.tone) {
        Tone.BAD -> Palette.Alert to Palette.Bone
        Tone.WAIT -> Palette.Wait to Palette.Navy
        Tone.NORMAL -> ink to ground
    }
    Column(Modifier.fillMaxWidth().background(bg).padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
        Text(count.title.uppercase(), style = MaterialTheme.typography.displayLarge, color = fg, maxLines = 1, softWrap = false)
        Text(count.sub, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = fg, modifier = Modifier.padding(top = 4.dp))
    }
}

private val cellText = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** Fixed cells, right-aligned into [pattern] ('#' digit, '.' fixed point); only the cells that change flip. */
@Composable
fun FlipCells(text: String, pattern: String, big: Boolean, modifier: Modifier = Modifier) {
    val chars = arrayOfNulls<Char>(pattern.length)
    var j = text.length - 1
    for (k in pattern.length - 1 downTo 0) { if (j >= 0) { chars[k] = text[j]; j-- } }
    pattern.forEachIndexed { k, p -> if (p == '.') chars[k] = '.' }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (big) 6.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
        pattern.indices.forEach { k ->
            val dot = pattern[k] == '.'
            val w = if (big) 76.dp else if (dot) 16.dp else 34.dp
            val h = if (big) 124.dp else 54.dp
            Cell(chars[k], w, h, if (big) 112 else 46, if (big) 2.dp else 1.dp)
        }
    }
}

@Composable
private fun Cell(ch: Char?, w: Dp, h: Dp, fontSp: Int, split: Dp) {
    val i = ink; val g = ground; val s = soft
    val reduce = LocalReduceMotion.current
    val base = Modifier.size(w, h)
    Box(if (ch == null) base.border(2.dp, s.copy(alpha = .4f)) else base.background(i), contentAlignment = Alignment.Center) {
        if (ch != null) {
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else (slideInVertically(tween(220)) { -it / 3 } + fadeIn(tween(220))) togetherWith fadeOut(tween(80))
                },
                label = "flip",
            ) { c ->
                Text(c.toString(), color = g, style = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = fontSp.sp, lineHeight = fontSp.sp, lineHeightStyle = cellText))
            }
            Box(Modifier.fillMaxWidth().height(split).align(Alignment.Center).background(g.copy(alpha = .85f)))
        }
    }
}

@Composable
fun ZoneBar(current: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        Palette.Zones.forEachIndexed { z, color ->
            val on = z == current
            Box(
                Modifier.weight(1f).height(if (on) 48.dp else 36.dp).alpha(if (on) 1f else .35f).background(color).then(if (on) Modifier.border(2.dp, Palette.Navy) else Modifier),
                contentAlignment = Alignment.Center,
            ) { Text("${z + 1}", color = Palette.Navy, style = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 20.sp)) }
        }
    }
}
