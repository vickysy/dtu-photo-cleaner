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

/** iOS 系统配色（参考 Apple Human Interface Guidelines 的系统颜色） */
@Immutable
data class IosPalette(
    val bg: Color,
    val card: Color,
    val elevated: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val separator: Color,
    val fill: Color,
    val pressed: Color,
    val bar: Color,
    val blue: Color,
    val red: Color,
    val green: Color,
    val orange: Color,
    val yellow: Color,
    val purple: Color,
    val pink: Color,
    val teal: Color,
    val indigo: Color,
    val gray: Color,
    val dark: Boolean,
)

val LightIos = IosPalette(
    bg = Color(0xFFF2F2F7), card = Color.White, elevated = Color.White,
    label = Color.Black, secondary = Color(0x993C3C43), tertiary = Color(0x4D3C3C43),
    separator = Color(0x4A3C3C43), fill = Color(0x1F767680), pressed = Color(0xFFE5E5EA), bar = Color(0xF7F9F9F9),
    blue = Color(0xFF007AFF), red = Color(0xFFFF3B30), green = Color(0xFF34C759), orange = Color(0xFFFF9500),
    yellow = Color(0xFFFFCC00), purple = Color(0xFFAF52DE), pink = Color(0xFFFF2D55), teal = Color(0xFF30B0C7),
    indigo = Color(0xFF5856D6), gray = Color(0xFF8E8E93), dark = false,
)

val DarkIos = IosPalette(
    bg = Color.Black, card = Color(0xFF1C1C1E), elevated = Color(0xFF2C2C2E),
    label = Color.White, secondary = Color(0x99EBEBF5), tertiary = Color(0x4DEBEBF5),
    separator = Color(0xA6545458), fill = Color(0x3D767680), pressed = Color(0xFF3A3A3C), bar = Color(0xF21C1C1E),
    blue = Color(0xFF0A84FF), red = Color(0xFFFF453A), green = Color(0xFF30D158), orange = Color(0xFFFF9F0A),
    yellow = Color(0xFFFFD60A), purple = Color(0xFFBF5AF2), pink = Color(0xFFFF375F), teal = Color(0xFF40C8E0),
    indigo = Color(0xFF5E5CE6), gray = Color(0xFF8E8E93), dark = true,
)

val LocalIos = staticCompositionLocalOf { LightIos }

/** 当前配色，在 Composable 里直接用 `ios.blue` */
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
val Keep = Color(0xFF34C759)
val Gold = Color(0xFFFFCC00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DtuTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val p = if (dark) DarkIos else LightIos
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.blue, onPrimary = Color.White, secondary = p.gray, error = p.red,
            background = p.bg, onBackground = p.label, surface = p.card, onSurface = p.label,
            surfaceContainer = p.card, surfaceContainerHigh = p.elevated, outline = p.separator,
        )
    } else {
        lightColorScheme(
            primary = p.blue, onPrimary = Color.White, secondary = p.gray, error = p.red,
            background = p.bg, onBackground = p.label, surface = p.card, onSurface = p.label,
            surfaceContainer = p.card, surfaceContainerHigh = Color(0xFFE5E5EA), outline = p.separator,
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
