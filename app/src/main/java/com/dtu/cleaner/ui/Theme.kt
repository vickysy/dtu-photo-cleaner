package com.dtu.cleaner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 配色：白底 + 品牌绿（书封面那个绿）+ 黄色点缀。
 * accent 是文字、图标用的绿（深一点，白底上看得清）；brand 是按钮、进度条、勾选圈这些大块的绿。
 */
@Immutable
data class IosPalette(
    val bg: Color,
    val card: Color,
    val elevated: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    /** 卡片的细边：白底页面上白卡片靠它分开 */
    val outline: Color,
    val fill: Color,
    val pressed: Color,
    val bar: Color,
    val accent: Color,
    val brand: Color,
    val brandSoft: Color,
    val yellow: Color,
    val yellowSoft: Color,
    /** 浅黄底上的字 */
    val onYellow: Color,
    val red: Color,
    val green: Color,
    val gray: Color,
    val dark: Boolean,
)

val BrandGreen = Color(0xFF2FC4A0)
val BrandYellow = Color(0xFFF6CC45)

val LightIos = IosPalette(
    bg = Color.White, card = Color.White, elevated = Color.White,
    label = Color(0xFF1A1A1A), secondary = Color(0x993C3C43), tertiary = Color(0x4D3C3C43),
    separator = Color(0x333C3C43), outline = Color(0xFFECEEF0), fill = Color(0x1A767680), pressed = Color(0xFFF2F3F5), bar = Color(0xFAFFFFFF),
    accent = Color(0xFF119C7E), brand = BrandGreen, brandSoft = Color(0xFFE8F8F3),
    yellow = BrandYellow, yellowSoft = Color(0xFFFFF5D6), onYellow = Color(0xFF8A6A00),
    red = Color(0xFFFF3B30), green = BrandGreen, gray = Color(0xFF8E8E93), dark = false,
)

val DarkIos = IosPalette(
    bg = Color.Black, card = Color(0xFF1C1C1E), elevated = Color(0xFF2C2C2E),
    label = Color.White, secondary = Color(0x99EBEBF5), tertiary = Color(0x4DEBEBF5),
    separator = Color(0xA6545458), outline = Color(0xFF2C2C2E), fill = Color(0x3D767680), pressed = Color(0xFF3A3A3C), bar = Color(0xF21C1C1E),
    accent = Color(0xFF3DD6B0), brand = BrandGreen, brandSoft = Color(0xFF12332B),
    yellow = BrandYellow, yellowSoft = Color(0xFF3A3218), onYellow = BrandYellow,
    red = Color(0xFFFF453A), green = BrandGreen, gray = Color(0xFF8E8E93), dark = true,
)

val LocalIos = staticCompositionLocalOf { LightIos }

/** 当前配色，在 Composable 里直接用 `ios.accent` */
val ios: IosPalette
    @Composable @ReadOnlyComposable get() = LocalIos.current

/** iOS 字号层级 */
object IosType {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold)
    val title1 = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)
    val subhead = TextStyle(fontSize = 15.sp, lineHeight = 20.sp)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
}

// 不随深浅色变化的常用色
val Danger = Color(0xFFFF3B30)
val Keep = BrandGreen
val Gold = BrandYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkIos else LightIos
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.brand, onPrimary = Color.White, secondary = p.gray, error = p.red,
            background = p.bg, onBackground = p.label, surface = p.card, onSurface = p.label,
            surfaceContainer = p.card, surfaceContainerHigh = p.elevated, outline = p.separator,
        )
    } else {
        lightColorScheme(
            primary = p.brand, onPrimary = Color.White, secondary = p.gray, error = p.red,
            background = p.bg, onBackground = p.label, surface = p.card, onSurface = p.label,
            surfaceContainer = p.card, surfaceContainerHigh = Color(0xFFF2F3F5), outline = p.separator,
        )
    }
    // 不要 Material 的水波纹，按下效果用 iOS 式的变灰/变淡
    CompositionLocalProvider(LocalIos provides p, LocalRippleConfiguration provides null) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(bodyLarge = IosType.body, bodyMedium = IosType.subhead, labelLarge = IosType.subhead),
            content = content,
        )
    }
}
