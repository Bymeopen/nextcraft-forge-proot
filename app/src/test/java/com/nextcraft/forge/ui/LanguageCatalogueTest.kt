package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The language picker must only offer locales whose strings.xml matches the default catalogue. */
class LanguageCatalogueTest {
    private val res = File("src/main/res")

    private fun keys(dir: String): Set<String> {
        val f = File(res, "$dir/strings.xml")
        assertTrue("missing ${f.absolutePath}", f.isFile)
        return Regex("""<string\s+name="([^"]+)"""").findAll(f.readText()).map { it.groupValues[1] }.toSet()
    }

    private fun resDir(language: AppLanguage) = when (language) {
        AppLanguage.SYSTEM, AppLanguage.ENGLISH -> "values"
        AppLanguage.INDONESIAN -> "values-in"
        AppLanguage.CHINESE -> "values-zh-rCN"
        else -> "values-${language.code}"
    }

    @Test
    fun translatedLocales_coverEveryDefaultKey() {
        val base = keys("values")
        AppLanguage.selectable.forEach { lang ->
            val missing = base - keys(resDir(lang))
            assertTrue("${lang.code} marked translated but misses ${missing.size} keys, e.g. ${missing.take(5)}", missing.isEmpty())
        }
    }

    private fun plurals(dir: String): Set<String> =
        Regex("""<plurals\s+name="([^"]+)"""").findAll(File(res, "$dir/strings.xml").readText()).map { it.groupValues[1] }.toSet()

    /** Counted nouns ("1 file" / "2 files") must exist in every translated locale, or it falls back to English. */
    @Test
    fun translatedLocales_coverEveryPlural() {
        val base = plurals("values")
        AppLanguage.selectable.forEach { lang ->
            val missing = base - plurals(resDir(lang))
            assertTrue("${lang.code} misses plurals $missing", missing.isEmpty())
        }
    }

    @Test
    fun stubLocales_areNotSelectable() {
        val base = keys("values").size
        AppLanguage.entries.filter { !it.translated }.forEach { lang ->
            val count = keys(resDir(lang)).size
            assertTrue("${lang.code} has $count/$base keys; set translated = true once complete", count < base)
        }
        assertEquals(setOf("system", "id", "en", "es", "zh", "ar", "hi", "ru"), AppLanguage.selectable.map { it.code }.toSet())
    }
}
