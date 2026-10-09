package com.dtu.cleaner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ———————————————————— 按压效果 ————————————————————

/** iOS 式按钮按下：变淡 */
fun Modifier.iosPress(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    graphicsLayer { alpha = if (pressed) 0.35f else 1f }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

/** iOS 式列表行按下：整行变灰 */
fun Modifier.rowPress(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else composed {
        val src = remember { MutableInteractionSource() }
        val pressed by src.collectIsPressedAsState()
        background(if (pressed) LocalIos.current.pressed else Color.Transparent)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
    }

// ———————————————————— 导航栏 / 大标题 ————————————————————

@Composable
fun IosNavBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backText: String = "返回",
    showTitle: Boolean = true,
    divider: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(ios.bar)) {
        Box(Modifier.fillMaxWidth().statusBarsPadding().height(44.dp)) {
            if (onBack != null) {
                Row(
                    Modifier.align(Alignment.CenterStart).padding(start = 6.dp).iosPress(onClick = onBack).padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBackIos, "返回", tint = ios.blue, modifier = Modifier.size(19.dp))
                    Text(backText, style = IosType.body, color = ios.blue)
                }
            }
            if (showTitle) {
                Text(
                    title, style = IosType.headline, color = ios.label, maxLines = 1,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
                )
            }
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(if (divider) ios.separator else Color.Transparent))
    }
}

@Composable
fun LargeTitle(text: String, subtitle: String? = null) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp)) {
        Text(text, style = IosType.largeTitle, color = ios.label)
        if (subtitle != null) Text(subtitle, style = IosType.subhead, color = ios.secondary, modifier = Modifier.padding(top = 2.dp))
    }
}

/** 导航栏右侧的文字按钮 */
@Composable
fun NavAction(text: String, color: Color = ios.blue, bold: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, style = IosType.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
        color = if (enabled) color else ios.tertiary,
        modifier = Modifier.iosPress(enabled, onClick).padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
fun NavIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).iosPress(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = ios.blue, modifier = Modifier.size(24.dp))
    }
}

// ———————————————————— 分组列表 ————————————————————

@Composable
fun InsetGroup(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (header != null) {
            Text(header, style = IosType.footnote, color = ios.secondary, modifier = Modifier.padding(start = 16.dp, bottom = 7.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ios.card), content = content)
        if (footer != null) {
            Text(footer, style = IosType.footnote, color = ios.secondary, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
        }
    }
}

@Composable
fun IosDivider(start: Dp = 16.dp) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(ios.separator))
}

/** iOS 设置里那种圆角彩色小图标 */
@Composable
fun IconTile(icon: ImageVector, bg: Color, size: Dp = 30.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(bg), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.62f))
    }
}

@Composable
fun IosRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    icon: ImageVector? = null,
    iconBg: Color = ios.blue,
    titleColor: Color = ios.label,
    enabled: Boolean = true,
    divider: Boolean = true,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().rowPress(if (enabled) onClick else null)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                IconTile(icon, if (enabled) iconBg else ios.gray)
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = IosType.body, color = if (enabled) titleColor else ios.secondary)
                if (subtitle != null) Text(subtitle, style = IosType.footnote, color = ios.secondary)
            }
            if (value != null) Text(value, style = IosType.body, color = ios.secondary, modifier = Modifier.padding(start = 8.dp))
            if (chevron && onClick != null && enabled) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = ios.tertiary, modifier = Modifier.size(24.dp))
            }
        }
        if (divider) IosDivider(if (icon != null) 60.dp else 16.dp)
    }
}

/** 列表里的一行纯文字按钮（蓝色/红色） */
@Composable
fun IosActionRow(text: String, color: Color = ios.blue, divider: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().rowPress(onClick)) {
        Text(text, style = IosType.body, color = color, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp))
        if (divider) IosDivider()
    }
}

// ———————————————————— 控件 ————————————————————

@Composable
fun <T> SegmentedControl(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(32.dp).clip(RoundedCornerShape(9.dp)).background(ios.fill).padding(2.dp)) {
        options.forEach { (v, label) ->
            val sel = v == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .then(
                        if (sel) Modifier.shadow(1.5.dp, RoundedCornerShape(7.dp))
                            .background(if (ios.dark) Color(0xFF636366) else Color.White, RoundedCornerShape(7.dp))
                        else Modifier,
                    )
                    .clip(RoundedCornerShape(7.dp))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(v) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, maxLines = 1, color = ios.label,
                    style = IosType.footnote.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                )
            }
        }
    }
}

@Composable
fun FilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = ios.blue,
    enabled: Boolean = true,
    height: Dp = 50.dp,
) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(14.dp)).background(if (enabled) color else ios.fill)
            .iosPress(enabled, onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = IosType.headline, color = if (enabled) Color.White else ios.tertiary, maxLines = 1)
    }
}

/** 胶囊形小按钮（工具栏里用） */
@Composable
fun CapsuleButton(text: String, onClick: () -> Unit, color: Color = ios.blue, enabled: Boolean = true, filled: Boolean = true) {
    Box(
        Modifier.height(36.dp).clip(CircleShape)
            .background(if (!enabled) ios.fill else if (filled) color else color.copy(alpha = 0.12f))
            .iosPress(enabled, onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), maxLines = 1,
            color = if (!enabled) ios.tertiary else if (filled) Color.White else color,
        )
    }
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, color: Color = ios.blue, style: TextStyle = IosType.body, enabled: Boolean = true) {
    Text(text, style = style, color = if (enabled) color else ios.tertiary, modifier = Modifier.iosPress(enabled, onClick).padding(vertical = 6.dp))
}

@Composable
fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text, maxLines = 1,
        style = IosType.subhead.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) Color.White else ios.label,
        modifier = Modifier.clip(CircleShape).background(if (selected) ios.blue else ios.fill)
            .iosPress(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
fun IosProgress(fraction: Float, modifier: Modifier = Modifier, color: Color = ios.blue) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(ios.fill)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(color))
    }
}

/** “照片”App 那样的勾选圈：选中是蓝底白勾 */
@Composable
fun SelectCircle(selected: Boolean, modifier: Modifier = Modifier, size: Dp = 23.dp) {
    Box(
        modifier.size(size).clip(CircleShape)
            .background(if (selected) ios.blue else Color.Black.copy(alpha = 0.18f))
            .border(1.5.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, "已选", tint = Color.White, modifier = Modifier.size(size * 0.66f))
    }
}

/** 照片上的半透明小胶囊标签 */
@Composable
fun PhotoBadge(text: String, modifier: Modifier = Modifier, bg: Color = Color.Black.copy(alpha = 0.45f), icon: ImageVector? = null, iconTint: Color = Color.White) {
    Row(
        modifier.clip(CircleShape).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(11.dp))
            Spacer(Modifier.width(2.dp))
        }
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** 底部工具栏（半透明白 + 顶部细线） */
@Composable
fun IosToolbar(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(ios.bar)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// ———————————————————— 弹窗 / 提示 ————————————————————

class AlertAction(val text: String, val role: Role = Role.Default, val onClick: () -> Unit) {
    enum class Role { Default, Cancel, Destructive }
}

/** iOS 样式的确认弹窗 */
@Composable
fun IosAlert(title: String, message: String?, actions: List<AlertAction>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(280.dp).clip(RoundedCornerShape(14.dp)).background(if (ios.dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F2))) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = IosType.headline, color = ios.label, textAlign = TextAlign.Center)
                if (message != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(message, style = IosType.footnote, color = ios.label, textAlign = TextAlign.Center)
                }
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
            if (actions.size == 2) {
                Row(Modifier.fillMaxWidth().height(46.dp)) {
                    AlertCell(actions[0], Modifier.weight(1f))
                    Box(Modifier.width(0.5.dp).fillMaxHeight().background(ios.separator))
                    AlertCell(actions[1], Modifier.weight(1f))
                }
            } else {
                actions.forEachIndexed { i, a ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(0.5.dp).background(ios.separator))
                    AlertCell(a, Modifier.fillMaxWidth().height(46.dp))
                }
            }
        }
    }
}

@Composable
private fun AlertCell(a: AlertAction, modifier: Modifier) {
    Box(modifier.fillMaxHeight().rowPress(a.onClick), contentAlignment = Alignment.Center) {
        Text(
            a.text,
            style = IosType.body.copy(fontWeight = if (a.role == AlertAction.Role.Cancel) FontWeight.SemiBold else FontWeight.Normal),
            color = if (a.role == AlertAction.Role.Destructive) ios.red else ios.blue,
            maxLines = 1,
        )
    }
}

/** 底部的黑色圆角提示（代替 Material 的 Snackbar） */
@Composable
fun IosToastHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        Box(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                data.visuals.message, color = Color.White, style = IosType.subhead, textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xE61C1C1E)).padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }
    }
}

/** 灰底页面的统一框架：导航栏 + 内容 + 可选底部工具栏 + 提示 */
@Composable
fun IosPage(
    navBar: @Composable () -> Unit,
    toast: SnackbarHostState? = null,
    toolbar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxWidth().fillMaxHeight().background(ios.bg)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight()) {
            navBar()
            Column(Modifier.fillMaxWidth().weight(1f), content = content)
            toolbar?.invoke()
        }
        if (toast != null) IosToastHost(toast, Modifier.align(Alignment.BottomCenter).padding(bottom = if (toolbar != null) 84.dp else 24.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = IosType.title3, color = ios.label, modifier = modifier.padding(start = 20.dp, end = 20.dp))
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start, color: Color = ios.secondary) {
    Text(text, style = IosType.footnote, color = color, textAlign = align, modifier = modifier.padding(horizontal = 32.dp))
}

@Composable
fun Spacer8() = Spacer(Modifier.height(8.dp))

@Composable
fun HStack(modifier: Modifier = Modifier, spacing: Dp = 8.dp, content: @Composable RowScope.() -> Unit) =
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(spacing), verticalAlignment = Alignment.CenterVertically, content = content)

/** iOS 开关：绿色是开 */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val x by androidx.compose.animation.core.animateDpAsState(if (checked) 20.dp else 0.dp, label = "switch")
    Box(
        Modifier.width(51.dp).height(31.dp).clip(CircleShape)
            .background(if (checked) ios.green else if (ios.dark) Color(0xFF39393D) else Color(0xFFE9E9EA))
            .iosPress { onChange(!checked) }
            .padding(2.dp),
    ) {
        Box(Modifier.graphicsLayer { translationX = x.toPx() }.size(27.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
    }
}

/** 设置里的开关行：标题、说明、右边开关 */
@Composable
fun IosSwitchRow(title: String, subtitle: String? = null, checked: Boolean, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = IosType.body, color = ios.label)
                if (subtitle != null) Text(subtitle, style = IosType.footnote, color = ios.secondary)
            }
            Spacer(Modifier.width(12.dp))
            IosSwitch(checked, onChange)
        }
        if (divider) IosDivider()
    }
}
