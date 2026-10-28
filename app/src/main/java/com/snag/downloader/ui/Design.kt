package com.snag.downloader.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snag.downloader.R
import com.snag.downloader.data.DownloadStatus

/* --------------------------------------------------------------------------------------------
 * Tokens
 *
 * Open near-white ground, a single electric-blue action colour, and two intentional dark
 * fields. White panels are quiet; the few dark regions carry the product story.
 * ------------------------------------------------------------------------------------------ */

@Immutable
data class SnagColors(
    val ground: Color,
    val panel: Color,
    val muted: Color,
    val rule: Color,
    val ink: Color,
    val inkSecondary: Color,
    val accent: Color,
    val onAccent: Color,
    val gold: Color,
    val onGold: Color,
    val field: Color,
    val onField: Color,
    val onFieldSecondary: Color,
)

val LightSnagColors = SnagColors(
    ground = Color(0xFFFCFBF8),
    panel = Color(0xFFFFFFFF),
    muted = Color(0xFFF7F1EA),
    rule = Color(0xFFE4D9CC),
    ink = Color(0xFF1D1114),
    inkSecondary = Color(0xFF5A4A4D),
    accent = Color(0xFF6E1B2A),
    onAccent = Color(0xFFFFF8F0),
    gold = Color(0xFFB08A45),
    onGold = Color(0xFF2A0E13),
    field = Color(0xFF2A0E13),
    onField = Color(0xFFFFF8F0),
    onFieldSecondary = Color(0xFFE3C9A8),
)

val DarkSnagColors = SnagColors(
    ground = Color(0xFF140B0E),
    panel = Color(0xFF1E1215),
    muted = Color(0xFF26181C),
    rule = Color(0xFF3A282D),
    ink = Color(0xFFFFF8F0),
    inkSecondary = Color(0xFFD8C3C7),
    accent = Color(0xFFE0B56A),
    onAccent = Color(0xFF2A0E13),
    gold = Color(0xFFE0B56A),
    onGold = Color(0xFF2A0E13),
    field = Color(0xFF2A0E13),
    onField = Color(0xFFFFF8F0),
    onFieldSecondary = Color(0xFFD8B98F),
)

val LocalSnagColors = staticCompositionLocalOf { LightSnagColors }

@Composable
fun snagColors(): SnagColors = LocalSnagColors.current

private val LightScheme = lightColorScheme(
    primary = LightSnagColors.accent,
    onPrimary = LightSnagColors.onAccent,
    primaryContainer = Color(0xFFF3E2E5),
    onPrimaryContainer = Color(0xFF3A0F18),
    secondary = Color(0xFF8A6A2F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3E7D3),
    onSecondaryContainer = Color(0xFF4A3515),
    background = LightSnagColors.ground,
    onBackground = LightSnagColors.ink,
    surface = LightSnagColors.panel,
    onSurface = LightSnagColors.ink,
    surfaceVariant = LightSnagColors.muted,
    onSurfaceVariant = LightSnagColors.inkSecondary,
    outline = LightSnagColors.rule,
    outlineVariant = LightSnagColors.rule,
    error = Color(0xFFB42318),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),
    inverseSurface = LightSnagColors.field,
    inverseOnSurface = LightSnagColors.onField,
    inversePrimary = Color(0xFFE0B56A),
)

private val DarkScheme = darkColorScheme(
    primary = DarkSnagColors.accent,
    onPrimary = DarkSnagColors.onAccent,
    primaryContainer = Color(0xFF5A3E18),
    onPrimaryContainer = Color(0xFFFFE2A8),
    secondary = Color(0xFFD7A9B2),
    onSecondary = Color(0xFF2A0E13),
    secondaryContainer = DarkSnagColors.muted,
    onSecondaryContainer = DarkSnagColors.ink,
    background = DarkSnagColors.ground,
    onBackground = DarkSnagColors.ink,
    surface = DarkSnagColors.panel,
    onSurface = DarkSnagColors.ink,
    surfaceVariant = DarkSnagColors.muted,
    onSurfaceVariant = DarkSnagColors.inkSecondary,
    outline = DarkSnagColors.rule,
    outlineVariant = Color(0xFF302025),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFF3A1A20),
    inverseOnSurface = Color(0xFFFFF8F0),
    inversePrimary = DarkSnagColors.accent,
)

private val HostGrotesk = FontFamily(
    Font(R.font.host_grotesk_regular, FontWeight.Normal),
    Font(R.font.host_grotesk_medium, FontWeight.Medium),
    Font(R.font.host_grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.host_grotesk_bold, FontWeight.Bold),
)

private val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
    Font(R.font.instrument_sans_bold, FontWeight.Bold),
)

private fun display(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: Double,
): TextStyle = TextStyle(
    fontFamily = HostGrotesk,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (size * tracking).sp,
)

private fun body(
    size: Int,
    line: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
): TextStyle = TextStyle(
    fontFamily = InstrumentSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (size * tracking).sp,
)

private val SnagTypography = Typography(
    displayLarge = display(42, 46, FontWeight.Bold, -0.03),
    displayMedium = display(36, 40, FontWeight.Bold, -0.03),
    displaySmall = display(30, 34, FontWeight.Bold, -0.025),
    headlineLarge = display(28, 32, FontWeight.Bold, -0.02),
    headlineMedium = display(26, 30, FontWeight.SemiBold, -0.02),
    headlineSmall = display(22, 27, FontWeight.SemiBold, -0.015),
    titleLarge = display(20, 25, FontWeight.SemiBold, -0.01),
    titleMedium = display(17, 23, FontWeight.SemiBold, -0.01),
    titleSmall = display(15, 20, FontWeight.SemiBold, 0.0),
    bodyLarge = body(16, 26),
    bodyMedium = body(15, 24),
    bodySmall = body(14, 22),
    labelLarge = body(15, 20, FontWeight.Medium),
    labelMedium = body(13, 18, FontWeight.Medium),
    labelSmall = body(12, 16, FontWeight.Medium, 0.015),
)

private val SnagShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)

@Composable
fun SnagTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSnagColors provides if (darkTheme) DarkSnagColors else LightSnagColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = SnagTypography,
            shapes = SnagShapes,
            content = content,
        )
    }
}

/* --------------------------------------------------------------------------------------------
 * Surfaces
 * ------------------------------------------------------------------------------------------ */

@Composable
fun SnagBackground(content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    val dark = scheme.background.luminance() < 0.5f
    val washAlpha = if (dark) 0.08f else 0.06f
    val wash = colors.gold.copy(alpha = washAlpha)
    val brush = Brush.linearGradient(
        colors = listOf(
            wash,
            Color.Transparent,
            wash.copy(alpha = washAlpha * 0.40f),
            Color.Transparent,
        ),
        start = Offset.Zero,
        end = Offset(1_000f, 2_400f),
    )
    CompositionLocalProvider(
        LocalContentColor provides scheme.onBackground,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(scheme.background)
                .drawBehind { drawRect(brush = brush) },
            content = content,
        )
    }
}

/** The royal mark. Used sparingly: the wordmark and the app icon carry it. */
@Composable
fun RoyalMark(modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_crown),
        contentDescription = null,
        tint = snagColors().gold,
        modifier = modifier,
    )
}

/** A small heraldic lozenge for ruled strips and quiet separators. */
@Composable
fun GoldDiamond(modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(4.dp)
            .rotate(45f)
            .background(snagColors().gold.copy(alpha = 0.75f)),
    )
}

/** A quiet white panel: 12dp corners, a hairline rule, and one soft downward shadow. */
@Composable
fun SnagCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = modifier
                .shadow(
                    elevation = 2.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.05f),
                    spotColor = Color.Black.copy(alpha = 0.10f),
                )
                .clip(shape)
                .background(containerColor)
                .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** A substantial dark passage. The faint blue tint keeps the surface from reading as a flat block. */
@Composable
fun SnagFieldCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = snagColors()
    val shape = RoundedCornerShape(12.dp)
    CompositionLocalProvider(
        LocalContentColor provides colors.onField,
    ) {
        Column(
            modifier = modifier
                .shadow(
                    elevation = 4.dp,
                    shape = shape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.08f),
                    spotColor = Color.Black.copy(alpha = 0.16f),
                )
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.field,
                            lerp(colors.field, colors.gold, 0.06f),
                        )
                    )
                )
                .border(1.dp, colors.gold.copy(alpha = 0.24f), shape)
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
fun SnagDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline,
    )
}

/* --------------------------------------------------------------------------------------------
 * Controls
 * ------------------------------------------------------------------------------------------ */

@Composable
fun SnagPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val colors = snagColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = SnagMotion.stiffness(Spring.StiffnessMediumLow),
        ),
        label = "primary-press",
    )
    Button(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled && !loading,
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.accent.copy(alpha = 0.35f),
            disabledContentColor = colors.onAccent.copy(alpha = 0.78f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        interactionSource = interaction,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = colors.onAccent,
            )
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun SnagSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onField: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = snagColors()
    val content = if (onField) colors.onField else MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = SnagMotion.stiffness(Spring.StiffnessMediumLow),
        ),
        label = "secondary-press",
    )
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 52.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            1.dp,
            if (onField) colors.onField.copy(alpha = 0.30f) else MaterialTheme.colorScheme.outline,
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = content,
            disabledContentColor = content.copy(alpha = 0.40f),
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        interactionSource = interaction,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun SnagTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = snagColors().accent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Filter / quality chip. Selected is a blue fill plus a check, so the state is not colour-only.
 * The focus ring is drawn for keyboard and switch-access users.
 */
@Composable
fun SnagChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    showCheck: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = SnagMotion.stiffness(Spring.StiffnessMediumLow),
        ),
        label = "chip-press",
    )
    val background by animateColorAsState(
        targetValue = if (selected) scheme.primary else scheme.surface,
        animationSpec = tween(SnagMotion.ms(180)),
        label = "chip-bg",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) scheme.onPrimary else scheme.onSurface,
        animationSpec = tween(SnagMotion.ms(180)),
        label = "chip-content",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> scheme.primary
            selected -> scheme.primary
            else -> scheme.outline
        },
        animationSpec = tween(SnagMotion.ms(180)),
        label = "chip-border",
    )

    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(background)
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = role,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected && showCheck) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

/* --------------------------------------------------------------------------------------------
 * Small parts
 * ------------------------------------------------------------------------------------------ */

@Composable
fun StatusPill(status: DownloadStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = snagColors()
    val (label, container, content, border) = when (status) {
        DownloadStatus.DONE -> Quad(
            "Done",
            colors.accent.copy(alpha = 0.10f),
            colors.accent,
            colors.accent.copy(alpha = 0.22f),
        )
        DownloadStatus.RUNNING -> Quad(
            "Downloading",
            colors.accent.copy(alpha = 0.10f),
            colors.accent,
            colors.accent.copy(alpha = 0.22f),
        )
        DownloadStatus.QUEUED -> Quad(
            "Queued",
            scheme.surfaceVariant,
            scheme.onSurfaceVariant,
            scheme.outline,
        )
        DownloadStatus.FAILED -> Quad(
            "Failed",
            scheme.errorContainer,
            scheme.onErrorContainer,
            scheme.error.copy(alpha = 0.30f),
        )
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(container)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content)
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun SnagEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
) {
    val colors = snagColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.padding(top = 14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = colors.ink)
        if (!body.isNullOrBlank()) {
            Spacer(Modifier.padding(top = 6.dp))
            Text(
                text = body,
                modifier = Modifier.widthIn(max = 420.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.inkSecondary,
                textAlign = TextAlign.Start,
            )
        }
    }
}

@Composable
fun MetaRow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                leading?.invoke()
                if (leading != null) Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
