package com.nextcraft.forge

import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.blur
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animateDpAsState
import android.Manifest
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.ui.LocalChatImageLoader
import com.nextcraft.forge.runtime.BatteryOptimization
import com.nextcraft.forge.ui.AppStrings
import com.nextcraft.forge.ui.LocalForgeThemeTokens
import com.nextcraft.forge.ui.LocaleHelper
import com.nextcraft.forge.ui.MainViewModel
import com.nextcraft.forge.ui.NextCraftForgeApp
import com.nextcraft.forge.ui.NextCraftForgeTheme
import com.nextcraft.forge.ui.resolveThemeTokens

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        viewModel.refreshRuntimeStatus()
        if (chainBatteryPrompt) {
            chainBatteryPrompt = false
            requestBatteryExemption()
        }
    }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { _ ->
        viewModel.diagnostics.refresh()
    }

    /** Set while the notification prompt is on screen so the battery prompt follows it, not overlaps it. */
    private var chainBatteryPrompt = false

    private var appliedLanguage = "system"

    override fun attachBaseContext(newBase: Context) {
        appliedLanguage = AppStrings.languageCode(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        applyWindowTheme(AppPreferences(this).themeMode)
        super.onCreate(savedInstanceState)
        com.nextcraft.forge.ui.glassUserSolid = AppPreferences(this).glassSolid
        com.nextcraft.forge.ui.glassReducedEffects = com.nextcraft.forge.ui.detectReducedEffects(this)
        // First-time users are asked when they start the runtime setup; existing installs keep the old behaviour.
        if (AppPreferences(this).runtimeSetupComplete) requestNotificationPermissionIfNeeded()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.backgroundPrompts.collect { requestBackgroundPrompts() }
            }
        }

        setContent {
            val state by viewModel.state.collectAsState()
            // The locale is applied in attachBaseContext, so a changed language needs a new Activity.
            LaunchedEffect(state.languageCode) {
                if (state.languageCode != appliedLanguage || AppStrings.languageCode(this@MainActivity) != appliedLanguage) {
                    recreate()
                }
            }
            LaunchedEffect(state.themeMode) {
                applyWindowTheme(state.themeMode)
            }
            NextCraftForgeTheme(themeMode = state.themeMode) {
                val tokens = LocalForgeThemeTokens.current
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(tokens.canvasBrush),
                ) {
                    val imageLoader = remember<suspend (String) -> androidx.compose.ui.graphics.ImageBitmap?>(viewModel) {
                        { src -> viewModel.loadChatImage(src) }
                    }
                    val appContext = this@MainActivity
                    val videoHandler = remember(viewModel) {
                        object : com.nextcraft.forge.ui.ChatVideoHandler {
                            override suspend fun thumbnail(src: String) = viewModel.loadChatVideoThumbnail(src)
                            override fun open(src: String) = viewModel.openChatVideo(appContext, src)
                        }
                    }
                    val sheetDepth = remember { mutableIntStateOf(0) }
                    // Glass: while a sheet floats above, the page behind it is blurred so nothing reads through the sheet.
                    val blurRadius by animateDpAsState(
                        targetValue = if (tokens.isGlass && com.nextcraft.forge.ui.glassBlurSupported && sheetDepth.intValue > 0) com.nextcraft.forge.ui.GlassSheetBlur else 0.dp,
                        label = "sheetBlur",
                    )
                    CompositionLocalProvider(
                        LocalChatImageLoader provides imageLoader,
                        com.nextcraft.forge.ui.LocalChatVideoHandler provides videoHandler,
                        com.nextcraft.forge.ui.LocalForgeSheetDepth provides sheetDepth,
                    ) {
                        Box(modifier = Modifier.fillMaxSize().then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier)) {
                            NextCraftForgeApp(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun applyWindowTheme(themeMode: String) {
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val (_, tokens) = resolveThemeTokens(themeMode, isNight)
        window.decorView.setBackgroundColor(tokens.windowBackgroundArgb)
        window.statusBarColor = tokens.topBarContainerColor.toArgb()
        window.navigationBarColor = if (tokens.isGlass) {
            android.graphics.Color.parseColor("#0B1324")
        } else {
            tokens.bottomBarContainerColor.toArgb()
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !tokens.isDark
        controller.isAppearanceLightNavigationBars = !tokens.isDark
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshRuntimeStatusOnResume()
        com.nextcraft.forge.ui.ReturnToApp.dismiss(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            viewModel.releaseMemoryCaches()
        }
        com.nextcraft.forge.runtime.GradleDaemonReaper.onTrimMemory(this, level)
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermissionIfNeeded() {
        if (needsNotificationPermission()) {
            runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainActivity", it) }
        }
    }

    /** Notifications first (the runtime reports progress there), then the battery exemption, each at most once. */
    private fun requestBackgroundPrompts() {
        val preferences = AppPreferences(this)
        if (preferences.backgroundPromptsShown) return
        preferences.backgroundPromptsShown = true
        if (needsNotificationPermission()) {
            chainBatteryPrompt = true
            val launched = runCatching { notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }.isSuccess
            if (launched) return
            chainBatteryPrompt = false
        }
        requestBatteryExemption()
    }

    private fun requestBatteryExemption() {
        if (BatteryOptimization.isUnrestricted(this)) return
        BatteryOptimization.launch(this) { batteryLauncher.launch(it) }
    }
}
