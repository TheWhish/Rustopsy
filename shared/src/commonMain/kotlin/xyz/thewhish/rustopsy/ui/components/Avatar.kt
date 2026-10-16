package xyz.thewhish.rustopsy.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.thewhish.rustopsy.ui.theme.Ash
import xyz.thewhish.rustopsy.ui.theme.AshColors
import xyz.thewhish.rustopsy.ui.theme.AshText

val LocalAvatars = staticCompositionLocalOf<suspend (String) -> ImageBitmap?> { { null } }

@Composable
fun Avatar(url: String?, name: String, size: Dp, modifier: Modifier = Modifier, ring: Color? = null) {
    val loader = LocalAvatars.current
    val image by produceState<ImageBitmap?>(null, url) { value = url?.let { loader(it) } }
    val base = modifier.size(size).clip(CircleShape)
    val framed = if (ring != null) base.border(2.dp, ring, CircleShape) else base
    val current = image
    if (current != null) {
        Image(current, contentDescription = name, modifier = framed, contentScale = ContentScale.Crop)
    } else {
        Box(framed.background(AshColors.ash3), contentAlignment = Alignment.Center) {
            AshText(name.firstOrNull()?.uppercase() ?: "?", Ash.type.bodyStrong.copy(fontSize = (size.value * 0.42f).sp), color = AshColors.text2)
        }
    }
}
