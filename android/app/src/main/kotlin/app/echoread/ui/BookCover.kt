package app.echoread.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.echoread.core.BookMeta
import app.echoread.core.Hash
import app.echoread.ui.motion.Dur
import app.echoread.ui.motion.Ease
import app.echoread.ui.motion.Thr

/** 已解码封面的进程级缓存：列表滚动往返不重复解码（≤360px JPEG，约 0.3MB/张） */
private val coverCache = LruCache<String, ImageBitmap>(48)

/** 封面：有图用图（IO 线程解码、淡入），无图按书名哈希生成极光风渐变（与网页版同色算法） */
@Composable
fun BookCover(book: BookMeta, modifier: Modifier = Modifier, radius: Dp = 14.dp, titleSize: Int = 15) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(radius)
    val cover = book.cover
    val cacheKey = "${book.id}:${cover?.size ?: 0}"
    val bitmap by produceState<ImageBitmap?>(initialValue = if (cover == null) null else coverCache.get(cacheKey), cacheKey) {
        if (cover != null && value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeByteArray(cover, 0, cover.size)?.asImageBitmap() }.getOrNull()
                    ?.also { coverCache.put(cacheKey, it) }
            }
        }
    }
    Box(modifier.clip(shape), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            // 真实淡入：旧代码 animateFloatAsState(1f) 起点终点都是 1f，这个「淡入」从来没播过
            val fade = remember(bmp) { Animatable(0f, Thr.ALPHA) }
            LaunchedEffect(bmp) { fade.animateTo(1f, tween(Dur.Medium, easing = Ease.Linear)) }
            Image(
                bmp, book.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    alpha = fade.value
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            )
        } else if (cover != null) {
            Box(Modifier.fillMaxSize().background(echo.cardAlt))
        } else {
            val h = Hash.cyrb53(book.title).take(8).toLong(16)
            val hue1 = (h % 360).toFloat()
            val hue2 = ((hue1 + 48) % 360)
            val brush = Brush.linearGradient(listOf(Color.hsl(hue1, 0.62f, 0.52f), Color.hsl(hue2, 0.7f, 0.34f)))
            Box(Modifier.fillMaxSize().background(brush))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.1f), Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
            Text(
                book.title.take(8),
                color = Color.White.copy(alpha = 0.95f),
                fontSize = titleSize.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                textAlign = TextAlign.Center,
                lineHeight = (titleSize + 5).sp,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            Text(
                book.format.label.uppercase(),
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 8.sp,
                letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp)
            )
        }
    }
}
