package app.ebikecompanion.ui

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ebikecompanion.R

/** The dashboard's world (dashboard/themes/ebike.yaml): bone and navy by day, navy and bone by night. */
object Palette {
    val Bone = Color(0xFFEDF1F5)
    val Navy = Color(0xFF0F1B2D)
    val NavySoft = Color(0xFF3D4B61)
    val StageText = Color(0xFFF2EFE6)
    val StageSoft = Color(0xFFA3B4CC)
    val Alert = Color(0xFFB3261E) // Material error role; deliberately not Zone 5's orange-red
    val Zones = listOf(Color(0xFF2A9D8F), Color(0xFF8AB17D), Color(0xFFFFD23F), Color(0xFFF4A259), Color(0xFFEF6A43))
    val Wait = Color(0xFFFFD23F)
}

val Display = FontFamily(Font(R.font.barlow_condensed_bold, FontWeight.Bold), Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold))
val Body = FontFamily(Font(R.font.barlow_medium, FontWeight.Medium), Font(R.font.barlow_semibold, FontWeight.SemiBold))

private val Day = lightColorScheme(
    primary = Palette.Navy, onPrimary = Palette.Bone,
    background = Palette.Bone, onBackground = Palette.Navy,
    surface = Palette.Bone, onSurface = Palette.Navy, onSurfaceVariant = Palette.NavySoft,
    outline = Palette.Navy, error = Palette.Alert, onError = Palette.Bone,
)
private val Night = darkColorScheme(
    primary = Palette.Wait, onPrimary = Palette.Navy,
    background = Palette.Navy, onBackground = Palette.StageText,
    surface = Palette.Navy, onSurface = Palette.StageText, onSurfaceVariant = Palette.StageSoft,
    outline = Palette.StageText, error = Palette.Alert, onError = Palette.Bone,
)

private val Flat = Shapes(RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp), RoundedCornerShape(0.dp))

private val Type = Typography(
    // Material roles mapped to the world: sp so text follows the system font size.
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.7.sp),
)

/** True when the user turned animations off (Remove animations): flip cells then change instantly. */
val LocalReduceMotion = compositionLocalOf { false }

@Composable
fun EbikeTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val reduce = Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Night else Day, shapes = Flat, typography = Type) {
        CompositionLocalProvider(LocalReduceMotion provides reduce, content = content)
    }
}
