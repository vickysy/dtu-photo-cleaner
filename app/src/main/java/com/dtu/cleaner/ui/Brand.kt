package com.dtu.cleaner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 卡片底：白底页面上是白卡片加一圈细边；深色模式是深灰卡片，不加边 */
fun Modifier.cardSurface(radius: Dp): Modifier = composed {
    val p = LocalIos.current
    val shape = RoundedCornerShape(radius)
    val base = clip(shape).background(p.card)
    if (p.dark) base else base.border(1.dp, p.outline, shape)
}

/** 荧光笔效果：文字下半截垫一道黄色 */
fun Modifier.highlighter(color: Color, from: Float = 0.58f, to: Float = 0.90f): Modifier = drawBehind {
    drawRect(color, topLeft = Offset(-2.dp.toPx(), size.height * from), size = Size(size.width + 4.dp.toPx(), size.height * (to - from)))
}

/** 浅黄底小标签，比如“推荐” */
@Composable
fun YellowChip(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = IosType.caption.copy(fontWeight = FontWeight.Medium), color = ios.onYellow, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(ios.yellowSoft).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

// Logo：3×3 照片格，右上角那格被挑出来（实心绿 + 黄星），坐标和启动图标一样，按 108 的画布画
private val TILES = listOf(22 to 22, 45 to 22, 22 to 45, 45 to 45, 68 to 45, 22 to 68, 45 to 68, 68 to 68)
private val STAR = floatArrayOf(77f, 25.5f, 78.9f, 29.4f, 83.2f, 30f, 80.1f, 33f, 80.8f, 37.3f, 77f, 35.3f, 73.2f, 37.3f, 73.9f, 33f, 70.8f, 30f, 75.1f, 29.4f)

/** “轻了相册”的 logo：白底圆角方块 + 照片格 */
@Composable
fun BrandMark(size: Dp, modifier: Modifier = Modifier) {
    val outline = ios.outline
    Canvas(modifier.size(size)) {
        val k = this.size.width / 108f
        val bg = CornerRadius(24 * k)
        drawRoundRect(Color.White, cornerRadius = bg)
        drawRoundRect(outline, cornerRadius = bg, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        // 和启动图标一样，图形缩在中间
        val s = 0.84f
        val ox = 54f - 55f * s
        val oy = 54f - 53f * s
        fun x(v: Float) = (ox + v * s) * k
        fun y(v: Float) = (oy + v * s) * k
        TILES.forEach { (tx, ty) ->
            drawRoundRect(
                BrandGreen.copy(alpha = 0.3f), topLeft = Offset(x(tx.toFloat()), y(ty.toFloat())),
                size = Size(18 * s * k, 18 * s * k), cornerRadius = CornerRadius(4 * s * k),
            )
        }
        drawRoundRect(BrandGreen, topLeft = Offset(x(66f), y(20f)), size = Size(22 * s * k, 22 * s * k), cornerRadius = CornerRadius(5 * s * k))
        val star = Path().apply {
            moveTo(x(STAR[0]), y(STAR[1]))
            for (i in 2 until STAR.size step 2) lineTo(x(STAR[i]), y(STAR[i + 1]))
            close()
        }
        drawPath(star, BrandYellow)
    }
}
