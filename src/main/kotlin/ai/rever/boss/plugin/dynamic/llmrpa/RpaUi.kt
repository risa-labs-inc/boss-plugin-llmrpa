package ai.rever.boss.plugin.dynamic.llmrpa

import ai.rever.boss.plugin.ui.BossColors
import ai.rever.boss.plugin.ui.BossPopup
import ai.rever.boss.plugin.ui.BossPopupAnchoring
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** BOSS palette roles as this panel uses them. */
internal object RpaTokens {
    val Panel = BossThemeColors.SurfaceColor
    val Content = BossThemeColors.BackgroundColor
    val Raised = BossColors.darkSurface
    val Border = BossThemeColors.BorderColor
    val Text = BossThemeColors.TextPrimary
    val TextSecondary = BossThemeColors.TextSecondary
    val TextMuted = BossThemeColors.TextMuted
    val Accent = BossThemeColors.AccentColor
    val Error = BossThemeColors.ErrorColor
    val Success = BossThemeColors.SuccessColor
    val Warning = BossThemeColors.WarningColor
    val MenuBackground = BossColors.contextMenuBackground
    val MenuBorder = BossColors.contextMenuBorder
    val Shape = RoundedCornerShape(6.dp)
    val Mono = FontFamily.Monospace
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier, color = RpaTokens.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
}

internal enum class Tone { NEUTRAL, ACCENT, WARNING, ERROR, SUCCESS }

internal fun Tone.color(): Color = when (this) {
    Tone.NEUTRAL -> RpaTokens.Border
    Tone.ACCENT -> RpaTokens.Accent
    Tone.WARNING -> RpaTokens.Warning
    Tone.ERROR -> RpaTokens.Error
    Tone.SUCCESS -> RpaTokens.Success
}

/** A small filled label; text always carries the meaning, color only reinforces it. */
@Composable
internal fun Pill(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = tone.color()
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(c.copy(alpha = if (tone == Tone.NEUTRAL) 0.35f else 0.16f))
            .border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        color = if (tone == Tone.NEUTRAL || tone == Tone.ACCENT) RpaTokens.Text else c,
        fontSize = 12.sp,
        fontFamily = if (mono) RpaTokens.Mono else FontFamily.Default,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = RpaTokens.Accent) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 2.dp, vertical = 2.dp),
        color = color,
        fontSize = 12.sp,
    )
}

/** Multi-line input with the same surface, border and radius as BossTextField. */
@Composable
internal fun RpaTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String,
    label: String,
    minHeight: Int,
    readOnly: Boolean = false,
) {
    val style = TextStyle(color = RpaTokens.Text, fontSize = 13.sp, lineHeight = 19.sp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        readOnly = readOnly,
        textStyle = style,
        cursorBrush = SolidColor(RpaTokens.Accent),
        modifier = modifier.semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().heightIn(min = minHeight.dp)
                    .background(RpaTokens.Panel, RpaTokens.Shape)
                    .border(1.dp, RpaTokens.Border, RpaTokens.Shape)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = RpaTokens.TextMuted))
                inner()
            }
        },
    )
}

/** A compact button whose look matches BossSecondaryButton, sized for dense rows. */
@Composable
internal fun OutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: Tone = Tone.ACCENT,
    trailing: String? = null,
) {
    val c = if (enabled) tone.color() else RpaTokens.Border
    Row(
        modifier.heightIn(min = 32.dp).clip(RpaTokens.Shape).border(1.dp, c, RpaTokens.Shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text, color = if (enabled) (if (tone == Tone.NEUTRAL) RpaTokens.TextSecondary else c) else RpaTokens.TextMuted, fontSize = 13.sp)
        trailing?.let { Text(it, color = RpaTokens.TextMuted, fontSize = 11.sp) }
    }
}

@Composable
internal fun SmallIcon(icon: ImageVector, description: String?, tint: Color = RpaTokens.TextMuted) {
    Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(14.dp))
}

/** Menu surface anchored under its caller; BossPopup so it renders over heavyweight content. */
@Composable
internal fun RpaMenu(onDismiss: () -> Unit, width: Int = 280, content: @Composable ColumnScope.() -> Unit) {
    BossPopup(onDismissRequest = onDismiss, focusable = true, anchoring = BossPopupAnchoring.AnchorBounds) {
        Column(
            Modifier.width(width.dp).clip(RpaTokens.Shape).background(RpaTokens.MenuBackground)
                .border(1.dp, RpaTokens.MenuBorder, RpaTokens.Shape).padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
internal fun MenuHeading(text: String, note: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text.uppercase(), color = RpaTokens.TextMuted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp)
        note?.let { Text(it, color = RpaTokens.TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun MenuRow(
    text: String,
    onClick: () -> Unit,
    detail: String? = null,
    selected: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 32.dp).clickable(role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(text, color = RpaTokens.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            detail?.let { Text(it, color = RpaTokens.TextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (selected) Text("✓", color = RpaTokens.Accent, fontSize = 12.sp)
    }
}

@Composable
internal fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(RpaTokens.MenuBorder))
}

/** A single-line search box for menus. */
@Composable
internal fun MenuSearch(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val style = TextStyle(color = RpaTokens.Text, fontSize = 13.sp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(RpaTokens.Accent),
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().background(RpaTokens.Content, RpaTokens.Shape).border(1.dp, RpaTokens.Border, RpaTokens.Shape)
                .padding(horizontal = 8.dp, vertical = 6.dp)) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = RpaTokens.TextMuted))
                inner()
            }
        },
    )
}

internal fun host(url: String?): String =
    url?.substringAfter("://")?.substringBefore('/')?.removePrefix("www.").orEmpty()
