package com.nextcraft.forge.ui

/**
 * [translated] is true only when the locale's strings.xml carries the full catalogue. Locales still at
 * the 2-string stub fall back to English silently, so the picker hides them until they are filled in
 * (LanguageCatalogueTest keeps this flag honest).
 */
enum class AppLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val translated: Boolean = false,
) {
    SYSTEM("system", "System Default", "Default", translated = true),
    INDONESIAN("id", "Indonesian", "Bahasa Indonesia", translated = true),
    ENGLISH("en", "English", "English", translated = true),
    SPANISH("es", "Spanish", "Español", translated = true),
    CHINESE("zh", "Chinese (Simplified)", "简体中文", translated = true),
    ARABIC("ar", "Arabic", "العربية", translated = true),
    HINDI("hi", "Hindi", "हिन्दी", translated = true),
    RUSSIAN("ru", "Russian", "Русский", translated = true);

    companion object {
        /** Languages the user can pick: fully translated ones only. */
        val selectable: List<AppLanguage> get() = entries.filter { it.translated }

        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
    }
}
