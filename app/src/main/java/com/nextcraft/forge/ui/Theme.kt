package com.nextcraft.forge.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.annotation.StringRes
import com.nextcraft.forge.R

// Base palette constants retained for binary/source compatibility
val ForgeCyan = Color(0xFF38BDF8)
val ForgeGreen = Color(0xFF34D399)
val ForgeAmber = Color(0xFFFBBF24)
val ForgePurple = Color(0xFFA78BFA)
val ForgeRed = Color(0xFFF87171)
val ForgeDarkBg = Color(0xFF0B0F17)
val ForgeDarkSurface = Color(0xFF111827)
val ForgeDarkElevated = Color(0xFF1E293B)

/**
 * Four selectable theme modes supported by NextCraft Forge:
 * 1. SYSTEM ("system") — Bawaan System (follows Android OS day/night setting)
 * 2. DARK   ("dark")   — Gelap (Zuan Tinted Obsidian Dark)
 * 3. GLASS  ("glass")  — Glass (Zuan Liquid Glassmorphism & Floating Capsule)
 * 4. WHITE  ("white")  — White (Airbnb Daylight 100% Pure White)
 */
enum class ForgeThemeMode(
    val id: String,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
    val badge: String,
) {
    SYSTEM(id = "system", title = R.string.theme_system_title, subtitle = R.string.theme_system_sub, badge = "AUTO"),
    DARK(id = "dark", title = R.string.theme_linear_title, subtitle = R.string.theme_linear_sub, badge = "DARK"),
    GLASS(id = "glass", title = R.string.theme_glass_title, subtitle = R.string.theme_glass_sub, badge = "GLASS"),
    WHITE(id = "white", title = R.string.theme_white_title, subtitle = R.string.theme_white_sub, badge = "WHITE");

    companion object {
        fun fromStored(raw: String?): ForgeThemeMode {
            val clean = raw?.trim()?.lowercase().orEmpty()
            if (clean == "light") return WHITE
            return entries.firstOrNull { it.id == clean } ?: DARK
        }
    }
}

@Immutable
data class ForgeThemeTokens(
    val mode: ForgeThemeMode,
    val isDark: Boolean,
    val isGlass: Boolean,
    val isWhite: Boolean,
    val canvasBrush: Brush,
    val windowBackgroundArgb: Int,
    val cardContainerColor: Color,
    val cardElevatedColor: Color,
    val cardBorderStroke: BorderStroke?,
    val elevatedBorderStroke: BorderStroke?,
    val cardCornerRadius: Dp,
    val topBarContainerColor: Color,
    val bottomBarContainerColor: Color,
    val accentText: Color,
    val successText: Color,
    val warningText: Color,
    val dangerText: Color,
    val terminalBackground: Color,
    val terminalHeaderBackground: Color,
    val terminalText: Color,
    val inlineCodeBackground: Color,
    val diffAddedBackground: Color,
    val diffAddedText: Color,
    val diffRemovedBackground: Color,
    val diffRemovedText: Color,
    val selectionColors: TextSelectionColors,
)

// 1. DARK (neutral near-black tinted by the one brand hue, indigo)
internal val ZuanDarkColorScheme: ColorScheme = darkColorScheme(
    // Near-black neutrals with a faint indigo tint, one indigo accent. Generated in OKLCH; ContrastTest keeps every pair at AA.
    primary = Color(0xFF007AC1),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF002B49),
    onPrimaryContainer = Color(0xFFC9E2F7),
    secondary = Color(0xFF4CB47F),
    onSecondary = Color(0xFF06231A),
    secondaryContainer = Color(0xFF12281E),
    onSecondaryContainer = Color(0xFFB6F0D0),
    tertiary = Color(0xFFF2C94C),
    onTertiary = Color(0xFF2A2000),
    background = Color(0xFF090B0E),
    onBackground = Color(0xFFECEFF1),
    surface = Color(0xFF111417),
    onSurface = Color(0xFFECEFF1),
    surfaceVariant = Color(0xFF191C1F),
    onSurfaceVariant = Color(0xFFA0A5AB),
    outline = Color(0xFF646A6E),
    outlineVariant = Color(0xFF34393D),
    error = Color(0xFFFD736D),
    onError = Color(0xFF160605),
    errorContainer = Color(0xFF401312),
    onErrorContainer = Color(0xFFF8D4D1),
    surfaceTint = Color.Transparent,
)

// 2. GLASS (Zuan Liquid Glassmorphism)
internal val ZuanGlassColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF5DBEB2),
    onPrimary = Color(0xFF001411),
    primaryContainer = Color(0x6605413C),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0x73115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFC084FC),
    onTertiary = Color(0xFF2E1065),
    background = Color(0xFF001619),
    onBackground = Color(0xFFF2F6F5),
    surface = Color(0xF0011613),
    onSurface = Color(0xFFF2F6F5),
    surfaceVariant = Color(0x661E293B),
    onSurfaceVariant = Color(0xFFD7E9E6),
    outline = Color(0x55FFFFFF),
    outlineVariant = Color(0x2EFFFFFF),
    error = Color(0xFFFCA098),
    onError = Color(0xFF1E0707),
    errorContainer = Color(0x99450A0A),
    onErrorContainer = Color(0xFFFEE2E2),
    surfaceTint = Color.Transparent,
)

// 3. WHITE (near-white tinted by the same indigo)
internal val AirbnbWhiteColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF315DD4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE2EBFF),
    onPrimaryContainer = Color(0xFF0F2669),
    secondary = Color(0xFF008489),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6F6F7),
    onSecondaryContainer = Color(0xFF004F52),
    tertiary = Color(0xFF6D28D9),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFBFCFE),
    onBackground = Color(0xFF1C2030),
    surface = Color(0xFFFBFCFE),
    onSurface = Color(0xFF1C2030),
    surfaceVariant = Color(0xFFF1F3F8),
    onSurfaceVariant = Color(0xFF4B5163),
    outline = Color(0xFF6F7587),
    outlineVariant = Color(0xFFD3D8E3),
    error = Color(0xFFB71824),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE9E6),
    onErrorContainer = Color(0xFF551112),
    surfaceTint = Color.Transparent,
)

// 4. SISTEM (follows the phone): keeps its own earlier colours, separate from the Dark / Glass / White picks above.
private val SystemDarkColorScheme: ColorScheme = darkColorScheme(
    // Linear-style neutral dark: R=G=B greys, one indigo accent (values measured from Linear Android).
    primary = Color(0xFF5E6AD2),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1C1F3D),
    onPrimaryContainer = Color(0xFFDDE0FF),
    secondary = Color(0xFF4CB47F),
    onSecondary = Color(0xFF06231A),
    secondaryContainer = Color(0xFF12281E),
    onSecondaryContainer = Color(0xFFB6F0D0),
    tertiary = Color(0xFFF2C94C),
    onTertiary = Color(0xFF2A2000),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF131313),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF181818),
    onSurfaceVariant = Color(0xFF9C9C9C),
    outline = Color(0xFF2A2A2A),
    outlineVariant = Color(0xFF1E1E1E),
    error = Color(0xFFE5484D),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFD5D6),
    surfaceTint = Color.Transparent,
)

private val SystemLightBaseColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFD70F47), // Airbnb coral darkened: white text on #FF385C is only 3.5:1
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFF0F3),
    onPrimaryContainer = Color(0xFF8A0F28),
    secondary = Color(0xFF008489),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6F6F7),
    onSecondaryContainer = Color(0xFF004F52),
    tertiary = Color(0xFF6D28D9),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF222222),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF222222),
    surfaceVariant = Color(0xFFF7F7F7),
    onSurfaceVariant = Color(0xFF666666),
    outline = Color(0xFFDDDDDD),
    outlineVariant = Color(0xFFEBEBEB),
    error = Color(0xFFC13515),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFF0EE),
    onErrorContainer = Color(0xFF7A1C08),
    surfaceTint = Color.Transparent,
)

internal val SystemLightColorScheme: ColorScheme = SystemLightBaseColorScheme.copy(
    primary = Color(0xFF0369A1), // white on #0284C7 was 4.1:1
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0F2FE),
    onPrimaryContainer = Color(0xFF0C4A6E),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
)

// Pre-allocated Brushes & Tokens to prevent recomposition allocation thrashing (THEME-BUG-025)
private val GlassSpecularBorderBrush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.38f),
        Color.White.copy(alpha = 0.12f),
    ),
)

private val GlassInnerBorderBrush = Brush.linearGradient(
    colors = listOf(
        Color.White.copy(alpha = 0.24f),
        Color.White.copy(alpha = 0.08f),
    ),
)

// Diagonal indigo-to-violet wash: gives the translucent surfaces something coloured to show through.
/** Opaque, darker than every stop of the Glass wash: the colour of sheets, dialogs and menus in Glass. */
internal val GlassFloatingSurface = Color(0xFF000D0B)

internal val GlassCanvasColors = listOf(Color(0xFF001411), Color(0xFF00202E), Color(0xFF000F0D))

private val GlassCosmicCanvasBrush = Brush.linearGradient(colors = GlassCanvasColors)

private val DarkThemeTokens = ForgeThemeTokens(
    mode = ForgeThemeMode.DARK,
    isDark = true,
    isGlass = false,
    isWhite = false,
    canvasBrush = SolidColor(Color(0xFF090B0E)),
    windowBackgroundArgb = Color(0xFF090B0E).toArgb(),
    cardContainerColor = Color(0xFF191C1F),
    cardElevatedColor = Color(0xFF23272A),
    cardBorderStroke = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    elevatedBorderStroke = null,
    cardCornerRadius = 18.dp,
    topBarContainerColor = Color(0xFF090B0E),
    bottomBarContainerColor = Color(0xFF191C1F),
    accentText = Color(0xFF7EC4FE),
    successText = Color(0xFF49C96F),
    warningText = Color(0xFFF9C02F),
    dangerText = Color(0xFFFD736D),
    terminalBackground = Color(0xFF040608),
    terminalHeaderBackground = Color(0xFF191C1F),
    terminalText = Color(0xFFDCDEE0),
    inlineCodeBackground = Color(0x33007AC1),
    diffAddedBackground = Color(0xFF062911),
    diffAddedText = Color(0xFF67C07D),
    diffRemovedBackground = Color(0xFF3D110F),
    diffRemovedText = Color(0xFFF18F88),
    selectionColors = TextSelectionColors(
        handleColor = Color(0xFF007AC1),
        backgroundColor = Color(0xFF007AC1).copy(alpha = 0.32f),
    ),
)

internal val GlassThemeTokens = ForgeThemeTokens(
    mode = ForgeThemeMode.GLASS,
    isDark = true,
    isGlass = true,
    isWhite = false,
    canvasBrush = GlassCosmicCanvasBrush,
    windowBackgroundArgb = Color(0xFF000F0D).toArgb(),
    cardContainerColor = Color(0xFFFFFFFF).copy(alpha = 0.09f),
    cardElevatedColor = Color(0xFFFFFFFF).copy(alpha = 0.14f),
    cardBorderStroke = BorderStroke(0.8.dp, GlassSpecularBorderBrush),
    elevatedBorderStroke = BorderStroke(0.8.dp, GlassInnerBorderBrush),
    cardCornerRadius = 18.dp,
    topBarContainerColor = Color(0xFF001411).copy(alpha = 0.30f),
    bottomBarContainerColor = Color(0xFFFFFFFF).copy(alpha = 0.12f),
    accentText = Color(0xFF59F2E1),
    successText = Color(0xFF79E393),
    warningText = Color(0xFFFEE5B1),
    dangerText = Color(0xFFFCA098),
    terminalBackground = Color(0xFF000F0D).copy(alpha = 0.58f),
    terminalHeaderBackground = Color(0xFFFFFFFF).copy(alpha = 0.10f),
    terminalText = Color(0xFFF2F6F5),
    inlineCodeBackground = Color(0x405DBEB2),
    diffAddedBackground = Color(0x40064E3B),
    diffAddedText = Color(0xFFA7F3D0),
    diffRemovedBackground = Color(0x407F1D1D),
    diffRemovedText = Color(0xFFFECACA),
    selectionColors = TextSelectionColors(
        handleColor = Color(0xFF5DBEB2),
        backgroundColor = Color(0xFF5DBEB2).copy(alpha = 0.35f),
    ),
)

internal val WhiteThemeTokens = ForgeThemeTokens(
    mode = ForgeThemeMode.WHITE,
    isDark = false,
    isGlass = false,
    isWhite = true,
    canvasBrush = SolidColor(Color(0xFFFBFCFE)),
    windowBackgroundArgb = Color(0xFFFBFCFE).toArgb(),
    cardContainerColor = Color(0xFFF1F3F8),
    cardElevatedColor = Color(0xFFEAEDF2),
    cardBorderStroke = BorderStroke(0.75.dp, Color(0xFFDDE1EA)),
    elevatedBorderStroke = BorderStroke(0.75.dp, Color(0xFFDDE1EA)),
    cardCornerRadius = 18.dp,
    topBarContainerColor = Color(0xFFFBFCFE),
    bottomBarContainerColor = Color(0xFFF1F3F8),
    accentText = Color(0xFF2B51B9),
    successText = Color(0xFF0A6B31),
    warningText = Color(0xFF825207),
    dangerText = Color(0xFFB71824),
    terminalBackground = Color(0xFFF1F3F8),
    terminalHeaderBackground = Color(0xFFE5E8ED),
    terminalText = Color(0xFF1C2030),
    inlineCodeBackground = Color(0xFFE2EBFF),
    diffAddedBackground = Color(0xFFE0FAE4),
    diffAddedText = Color(0xFF1E6935),
    diffRemovedBackground = Color(0xFFFFEDEB),
    diffRemovedText = Color(0xFFA82D2E),
    selectionColors = TextSelectionColors(
        handleColor = Color(0xFF315DD4),
        backgroundColor = Color(0xFF315DD4).copy(alpha = 0.22f),
    ),
)

private val SystemDarkThemeTokens = ForgeThemeTokens(
    mode = ForgeThemeMode.SYSTEM,
    isDark = true,
    isGlass = false,
    isWhite = false,
    canvasBrush = SolidColor(Color(0xFF0B0B0B)),
    windowBackgroundArgb = Color(0xFF0B0B0B).toArgb(),
    cardContainerColor = Color(0xFF131313),
    cardElevatedColor = Color(0xFF1B1B1B),
    cardBorderStroke = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
    elevatedBorderStroke = null,
    cardCornerRadius = 18.dp,
    topBarContainerColor = Color(0xFF0B0B0B),
    bottomBarContainerColor = Color(0xFF131313),
    accentText = Color(0xFFB9BFF5),
    successText = Color(0xFF4CB47F),
    warningText = Color(0xFFF2C94C),
    dangerText = Color(0xFFE94D52), // E5484D was 4.4:1 on the elevated card
    terminalBackground = Color(0xFF070707),
    terminalHeaderBackground = Color(0xFF131313),
    terminalText = Color(0xFFE6E6E6),
    inlineCodeBackground = Color(0x335E6AD2),
    diffAddedBackground = Color(0xFF0F2A1F),
    diffAddedText = Color(0xFF6EE7B7),
    diffRemovedBackground = Color(0xFF2E1214),
    diffRemovedText = Color(0xFFFCA5A5),
    selectionColors = TextSelectionColors(
        handleColor = Color(0xFF5E6AD2),
        backgroundColor = Color(0xFF5E6AD2).copy(alpha = 0.32f),
    ),
)


private val SystemLightBaseTokens = ForgeThemeTokens(
    mode = ForgeThemeMode.WHITE,
    isDark = false,
    isGlass = false,
    isWhite = true,
    canvasBrush = SolidColor(Color(0xFFFFFFFF)),
    windowBackgroundArgb = Color(0xFFFFFFFF).toArgb(),
    cardContainerColor = Color(0xFFF7F7F7),
    cardElevatedColor = Color(0xFFEFEFEF),
    cardBorderStroke = BorderStroke(0.5.dp, Color(0xFFE4E4E4)),
    elevatedBorderStroke = BorderStroke(0.5.dp, Color(0xFFE4E4E4)),
    cardCornerRadius = 18.dp,
    topBarContainerColor = Color(0xFFFFFFFF),
    bottomBarContainerColor = Color(0xFFF7F7F7),
    accentText = Color(0xFFC70A32),  // >= 4.5:1 on every White surface
    successText = Color(0xFF007505), // Kazan Green darkened (>= 4.5:1 on #F7F7F7)
    warningText = Color(0xFFA04A07), // Deep Amber (>= 4.5:1 on #EFEFEF)
    dangerText = Color(0xFFC13515),  // Airbnb Arches Red
    terminalBackground = Color(0xFFF7F7F7),
    terminalHeaderBackground = Color(0xFFEBEBEB),
    terminalText = Color(0xFF222222),
    inlineCodeBackground = Color(0xFFFFF0F3),
    diffAddedBackground = Color(0xFFEAFBF0),
    diffAddedText = Color(0xFF006338),
    diffRemovedBackground = Color(0xFFFFF0F0),
    diffRemovedText = Color(0xFFC13515),
    selectionColors = TextSelectionColors(
        handleColor = Color(0xFFD70F47),
        backgroundColor = Color(0xFFD70F47).copy(alpha = 0.22f),
    ),
)

private val SystemLightThemeTokens = SystemLightBaseTokens.copy(
    mode = ForgeThemeMode.SYSTEM,
    isWhite = false,
    canvasBrush = SolidColor(Color(0xFFF8FAFC)),
    windowBackgroundArgb = Color(0xFFF8FAFC).toArgb(),
    cardElevatedColor = Color(0xFFF1F5F9),
    cardBorderStroke = BorderStroke(1.dp, Color(0xFFCBD5E1)),
    elevatedBorderStroke = BorderStroke(1.dp, Color(0xFFE2E8F0)),
    accentText = Color(0xFF0369A1),
    inlineCodeBackground = Color(0xFFE0F2FE),
    selectionColors = TextSelectionColors(
        handleColor = Color(0xFF0284C7),
        backgroundColor = Color(0xFF0284C7).copy(alpha = 0.22f),
    ),
)

fun resolveThemeTokens(
    themeMode: String?,
    systemDark: Boolean,
): Pair<ColorScheme, ForgeThemeTokens> {
    return when (ForgeThemeMode.fromStored(themeMode)) {
        ForgeThemeMode.SYSTEM -> if (systemDark) {
            SystemDarkColorScheme to SystemDarkThemeTokens
        } else {
            SystemLightColorScheme to SystemLightThemeTokens
        }
        ForgeThemeMode.DARK -> ZuanDarkColorScheme to DarkThemeTokens
        ForgeThemeMode.GLASS -> ZuanGlassColorScheme to GlassThemeTokens
        ForgeThemeMode.WHITE -> AirbnbWhiteColorScheme to WhiteThemeTokens
    }
}

val LocalForgeThemeTokens = staticCompositionLocalOf<ForgeThemeTokens> { DarkThemeTokens }

val ForgeCodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = ForgeType.secondary,
    lineHeight = 19.sp,
)

private val ForgeTypography = Typography()

@Composable
fun NextCraftForgeTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val mode = ForgeThemeMode.fromStored(themeMode)
    val (colorScheme, tokens) = remember(mode, systemDark) {
        resolveThemeTokens(mode.id, systemDark)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            val window = activity.window
            window.decorView.setBackgroundColor(tokens.windowBackgroundArgb)
            @Suppress("DEPRECATION")
            run {
                window.statusBarColor = tokens.topBarContainerColor.toArgb()
                window.navigationBarColor = if (tokens.isGlass) {
                    Color(0xFF0B1324).toArgb()
                } else {
                    tokens.bottomBarContainerColor.toArgb()
                }
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !tokens.isDark
            controller.isAppearanceLightNavigationBars = !tokens.isDark
        }
    }

    CompositionLocalProvider(
        LocalForgeThemeTokens provides tokens,
        LocalTextSelectionColors provides tokens.selectionColors,
    ) {
        // Dialogs, sheets and menus read the surfaceContainer roles; map them to the card surfaces so every
        // AlertDialog, bottom sheet and dropdown matches the cards. Glass uses its own opaque, darker floating colour:
        // its card colour is translucent and a see-through dialog is unreadable over the scrim.
        val themedScheme = if (tokens.isGlass) {
            // Floating layers (sheets, dialogs, menus) are the darkest thing on screen and fully opaque, so they
            // read as lifted off the page instead of as another see-through card.
            colorScheme.copy(
                surfaceContainerLowest = GlassFloatingSurface,
                surfaceContainerLow = GlassFloatingSurface,
                surfaceContainer = GlassFloatingSurface,
                surfaceContainerHigh = GlassFloatingSurface,
                surfaceContainerHighest = GlassFloatingSurface,
            )
        } else {
            colorScheme.copy(
                surfaceContainerHigh = tokens.cardContainerColor,
                surfaceContainerLow = tokens.cardContainerColor,
                surfaceContainer = tokens.cardElevatedColor,
            )
        }
        MaterialTheme(
            colorScheme = themedScheme,
            typography = ForgeTypography,
            shapes = ForgeShapes,
        ) {
            // The app draws on a transparent Scaffold over its own canvas, so no Surface sets the
            // content colour. Without this, LocalContentColor stays black and every Text that
            // relies on the default is unreadable on the dark themes.
            CompositionLocalProvider(
                LocalContentColor provides colorScheme.onBackground,
                content = content,
            )
        }
    }
}

/**
 * Primary Z=1 surface card with theme-specific border (Hairline in White, Specular Gradient in Glass,
 * Slate Outline in Dark) and explicit contentColor to prevent THEME-BUG-019 & THEME-BUG-026.
 */
@Composable
fun ForgeThemedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = LocalForgeThemeTokens.current.cardContainerColor,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    border: BorderStroke? = LocalForgeThemeTokens.current.cardBorderStroke,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val shape = RoundedCornerShape(tokens.cardCornerRadius)
    val cardModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .clickable(onClick = onClick)
    } else {
        modifier
    }
    Card(
        modifier = cardModifier,
        shape = shape,
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        content = content,
    )
}

/**
 * Nested Z=1.5 elevated sub-card that stays visually distinct inside [ForgeThemedCard] across all 4 themes
 * (fixes THEME-BUG-019 where inner cards disappeared into outer cards).
 */
@Composable
fun ForgeElevatedCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    // Selected = soft accent tint + accent edge; text keeps the normal surface colour (no filled container).
    val container = if (selected) scheme.primary.copy(alpha = 0.10f) else tokens.cardElevatedColor
    val contentColor = scheme.onSurface
    val border = if (selected) {
        BorderStroke(1.dp, scheme.primary.copy(alpha = 0.45f))
    } else {
        null
    }
    val cardModifier = if (onClick != null) {
        modifier
            .clip(shape)
            .clickable(onClick = onClick)
    } else {
        modifier
    }
    Card(
        modifier = cardModifier,
        shape = shape,
        border = border,
        colors = CardDefaults.cardColors(
            containerColor = container,
            contentColor = contentColor,
        ),
        content = content,
    )
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** One corner scale for dialogs (extraLarge), menus (extraSmall) and sheets, shared by all themes. */
internal val ForgeShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
