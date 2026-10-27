package com.nextcraft.forge.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.SheetValue
import android.util.Log
import androidx.compose.material3.SheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.MaterialTheme
import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Liquid-glass material for the Glass theme, built on Kyant0's Backdrop library.
 *
 * Screens draw into one [LayerBackdrop] ([forgeGlassSource]); floating chrome (the bottom bar) re-draws that layer
 * blurred and refracted behind itself ([forgeGlassSurface]). Backdrop effects need RenderEffect (Android 12+), the
 * lens needs AGSL RuntimeShader (Android 13+). Below that, and in every non-Glass theme, the plain translucent
 * surface stays in use, so nothing here changes how Dark and White look.
 */
internal val LocalForgeBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/**
 * Set at start-up when blur should be skipped even though the OS can do it: low-RAM devices (the blur is the costliest
 * thing the app draws) and people who turned on the system's high-contrast text, who need solid surfaces.
 */
internal var glassReducedEffects: Boolean by androidx.compose.runtime.mutableStateOf(false)

/** The user's own "solid Glass" choice (Settings > Appearance); a state, so flipping it updates the open screen at once. */
internal var glassUserSolid: Boolean by androidx.compose.runtime.mutableStateOf(false)

internal val glassBlurSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !glassReducedEffects && !glassUserSolid

internal fun detectReducedEffects(context: android.content.Context): Boolean {
    val lowRam = context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true
    val highContrast = runCatching {
        android.provider.Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    }.getOrDefault(false)
    return lowRam || highContrast
}
internal val glassLensSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Records the content behind floating chrome. Apply to the container that holds the screens, not the chrome. */
@Composable
internal fun Modifier.forgeGlassSource(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else layerBackdrop(backdrop)

@Composable
internal fun rememberForgeBackdrop(enabled: Boolean): LayerBackdrop? {
    val backdrop = rememberLayerBackdrop()
    return if (enabled && glassBlurSupported) backdrop else null
}

/**
 * Glass fill for a capsule: blur + vibrancy everywhere it is supported, plus the refracting lens on Android 13+.
 * [tint] is the theme's translucent container colour, drawn over the blurred backdrop.
 */
@Composable
internal fun Modifier.forgeGlassSurface(backdrop: LayerBackdrop?, tint: Color): Modifier {
    if (backdrop == null) return this
    val lens = glassLensSupported
    return drawBackdrop(
        backdrop = backdrop,
        shape = { RoundedCornerShape(percent = 50) },
        effects = {
            vibrancy()
            blur(10.dp.toPx())
            if (lens) lens(refractionHeight = 14.dp.toPx(), refractionAmount = 18.dp.toPx())
        },
        onDrawSurface = {
            drawRect(tint)
            // Soft top sheen so the bar reads as glass rather than a flat tinted pane.
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)))
        },
    )
}

/**
 * Container colour for modal bottom sheets. A sheet covers the screen, so unlike a card it must never let the content
 * behind it show through: the Glass card colour is translucent by design, which made sheets unreadable there. Glass
 * uses [GlassFloatingSurface], opaque and darker than the page; the other themes keep the card colour.
 */
@Composable
internal fun forgeSheetColor(): Color {
    val tokens = LocalForgeThemeTokens.current
    return if (tokens.isGlass) GlassFloatingSurface else tokens.cardContainerColor
}

/** How many floating sheets are open. The root blurs the page behind them in Glass while this is above zero. */
internal val LocalForgeSheetDepth = staticCompositionLocalOf<MutableIntState?> { null }

/** Blur radius of the page behind an open sheet in Glass; below Android 12 there is no RenderEffect, so no blur. */
internal val GlassSheetBlur = 18.dp

/**
 * The one way to show a modal bottom sheet. Opaque colour from [forgeSheetColor], and while it is open the page behind
 * it is blurred in Glass (frosted, nothing shows through or piles up under the sheet's text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForgeModalSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable ColumnScope.() -> Unit,
) {
    // Count the sheet only while it is really on screen (not just composed): a composed-but-hidden sheet used to keep the
    // whole app blurred with nothing in front of it.
    val tracker = rememberSheetDepthTracker()
    LaunchedEffect(tracker, sheetState) {
        snapshotFlow { sheetState.targetValue != SheetValue.Hidden || sheetState.currentValue != SheetValue.Hidden }
            .collect { visible -> tracker.set(visible) }
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = forgeSheetColor(),
        contentColor = contentColor,
        content = content,
    )
}

/**
 * One open modal's share of [LocalForgeSheetDepth]. Idempotent (a repeated `set(true)` counts once), never lets the
 * shared count go below zero, and releases its share when it leaves the composition.
 */
internal class SheetDepthTracker(private val depth: MutableIntState?) {
    private var counted = false

    fun set(visible: Boolean) {
        val shared = depth ?: return
        if (visible == counted) return
        counted = visible
        shared.intValue = (shared.intValue + if (visible) 1 else -1).coerceAtLeast(0)
        Log.d("ForgeGlass", "modal depth -> ${shared.intValue} (visible=$visible)")
    }
}

@Composable
internal fun rememberSheetDepthTracker(): SheetDepthTracker {
    val depth = LocalForgeSheetDepth.current
    val tracker = remember(depth) { SheetDepthTracker(depth) }
    DisposableEffect(tracker) { onDispose { tracker.set(false) } }
    return tracker
}

/** Counts an open modal in [LocalForgeSheetDepth] so the root blurs the page behind it (Glass), like a sheet does. */
@Composable
internal fun CountAsOpenModal() {
    val tracker = rememberSheetDepthTracker()
    DisposableEffect(tracker) {
        tracker.set(true)
        onDispose { tracker.set(false) }
    }
}

/** The one way to show an alert dialog: Material's AlertDialog plus the same blurred backdrop in Glass as a sheet. */
@Composable
internal fun ForgeAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: @Composable (() -> Unit)? = null,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.material3.AlertDialogDefaults.shape,
) {
    CountAsOpenModal()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = shape,
    )
}
