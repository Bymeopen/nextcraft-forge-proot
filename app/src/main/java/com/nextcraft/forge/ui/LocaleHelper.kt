package com.nextcraft.forge.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

class LocalizedContext(
    hostContext: Context,
    private val localizedDelegate: Context,
) : ContextWrapper(hostContext) {
    override fun getResources(): Resources {
        val res = localizedDelegate.resources
        baseContext?.resources?.configuration?.let { parentCfg ->
            val desiredNightBits = parentCfg.uiMode and Configuration.UI_MODE_NIGHT_MASK
            val activeNightBits = res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (desiredNightBits != activeNightBits) {
                res.configuration.uiMode =
                    (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or desiredNightBits
            }
        }
        return res
    }

    override fun getAssets(): AssetManager = localizedDelegate.assets
}

object LocaleHelper {
    fun getLocale(languageCode: String): Locale? = when (languageCode.trim().lowercase(Locale.ROOT)) {
        "", "system" -> null
        "id", "in" -> Locale.forLanguageTag("id-ID")
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "en" -> Locale.ENGLISH
        else -> Locale.forLanguageTag(languageCode)
    }

    fun wrapContext(context: Context): Context =
        applyLanguage(context, AppStrings.languageCode(context))

    fun applyLanguage(context: Context, languageCode: String): Context {
        val resolvedLocale = getLocale(languageCode) ?: resolveSystemDefaultLocale() ?: return context
        Locale.setDefault(resolvedLocale)
        val updatedCfg = Configuration(context.resources.configuration).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                setLocales(LocaleList(resolvedLocale))
            } else {
                @Suppress("DEPRECATION")
                locale = resolvedLocale
            }
            setLayoutDirection(resolvedLocale)
        }
        return LocalizedContext(context, context.createConfigurationContext(updatedCfg))
    }

    private fun resolveSystemDefaultLocale(): Locale? {
        val sysCfg = Resources.getSystem().configuration
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sysCfg.locales[0]
        } else {
            @Suppress("DEPRECATION")
            sysCfg.locale
        }
    }
}

object AppStrings {
    private const val SHARED_PREFS_FILE = "nextcraft_preferences"
    private const val PREF_LANG_KEY = "language_code"

    fun languageCode(context: Context): String =
        context.getSharedPreferences(SHARED_PREFS_FILE, Context.MODE_PRIVATE)
            .getString(PREF_LANG_KEY, "system")
            .orEmpty()
            .ifBlank { "system" }

    fun saveLanguageCode(context: Context, code: String) {
        val normalized = code.trim().ifEmpty { "system" }
        context.getSharedPreferences(SHARED_PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LANG_KEY, normalized)
            .commit()
    }

    fun context(context: Context): Context =
        LocaleHelper.applyLanguage(context, languageCode(context))

    fun get(context: Context, @StringRes id: Int, vararg args: Any): String =
        context(context).getString(id, *args)
}
