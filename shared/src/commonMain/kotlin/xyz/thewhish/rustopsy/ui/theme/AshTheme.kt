package xyz.thewhish.rustopsy.ui.theme

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import rustopsy.shared.generated.resources.Res
import rustopsy.shared.generated.resources.geologica

@Immutable
class AshType(family: FontFamily) {
    val numHero = style(family, 104, 104, 600, -0.02f)
    val numXL = style(family, 34, 36, 600, -0.02f)
    val numL = style(family, 26, 30, 600, -0.01f)
    val numM = style(family, 17, 22, 600)
    val title = style(family, 24, 30, 600, -0.01f)
    val section = style(family, 15, 20, 600)
    val body = style(family, 14, 20, 400)
    val bodyMedium = style(family, 14, 20, 500)
    val bodyStrong = style(family, 14, 20, 600)
    val secondary = style(family, 13, 18, 400)
    val label = style(family, 12, 16, 400)
    val caption = style(family, 11, 14, 500)

    private companion object {
        fun style(family: FontFamily, size: Int, line: Int, weight: Int, tracking: Float = 0f) = TextStyle(
            fontFamily = family,
            fontSize = size.sp,
            lineHeight = line.sp,
            fontWeight = FontWeight(weight),
            letterSpacing = tracking.em,
            fontFeatureSettings = "tnum",
            color = AshColors.text1,
        )
    }
}

private val LocalAshType = staticCompositionLocalOf<AshType> { error("AshTheme is not applied") }

object Ash {
    val type: AshType
        @Composable
        @ReadOnlyComposable
        get() = LocalAshType.current
}

@Composable
fun AshTheme(content: @Composable () -> Unit) {
    val family = geologica()
    val type = remember(family) { AshType(family) }
    CompositionLocalProvider(LocalAshType provides type, content = content)
}

@Composable
fun AshText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = style.color,
    maxLines: Int = 1,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun geologica(): FontFamily = FontFamily(
    listOf(400, 500, 600).map { weight ->
        Font(
            resource = Res.font.geologica,
            weight = FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("SHRP", 40f)),
        )
    },
)
