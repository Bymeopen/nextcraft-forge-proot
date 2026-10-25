package com.nextcraft.forge.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every translation must be safe to ship: only names the default file has, the same format placeholders (a missing or
 * extra %1$s crashes at runtime), no empty values, and every plural has its "other" form. Coverage of the translated
 * languages must not rot: new English strings may lag a little, not for most of the file.
 */
class StringParityTest {
    private val res = File("src/main/res")
    private val placeholder = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXeEgGcb%n]""")

    private class Parsed(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun parse(file: File): Parsed {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as? Element ?: continue
            when (el.tagName) {
                "string" -> strings[el.getAttribute("name")] = el.textContent
                "plurals" -> {
                    val items = el.getElementsByTagName("item")
                    plurals[el.getAttribute("name")] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return Parsed(strings, plurals)
    }

    private val default = parse(File(res, "values/strings.xml"))
    private val locales = res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").isFile }!!
        .map { it.name to parse(File(it, "strings.xml")) }

    private fun holders(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun translationsOnlyUseNamesTheDefaultFileHas() {
        val offenders = locales.flatMap { (name, p) ->
            (p.strings.keys - default.strings.keys).map { "$name: string $it" } +
                (p.plurals.keys - default.plurals.keys).map { "$name: plurals $it" }
        }
        assertTrue("names missing from values/strings.xml:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun placeholdersMatchTheDefaultAndNothingIsEmpty() {
        val offenders = mutableListOf<String>()
        for ((name, p) in locales) {
            for ((key, text) in p.strings) {
                val source = default.strings[key] ?: continue
                if (text.isBlank()) offenders += "$name: $key is empty"
                if (holders(source) != holders(text)) offenders += "$name: $key placeholders ${holders(source)} vs ${holders(text)}"
            }
            for ((key, items) in p.plurals) {
                if ("other" !in items) offenders += "$name: $key has no 'other'"
                items.forEach { (q, text) -> if (text.isBlank()) offenders += "$name: $key[$q] is empty" }
            }
        }
        assertTrue("translation problems:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun translatedLanguagesKeepMostOfTheStrings() {
        val total = default.strings.size
        val thin = listOf("values-in", "values-ar", "values-es", "values-hi", "values-ru", "values-zh-rCN").mapNotNull { name ->
            val have = locales.firstOrNull { it.first == name }?.second?.strings?.keys?.count { it in default.strings } ?: 0
            if (have * 100 < total * 90) "$name has $have of $total" else null
        }
        assertTrue("translation coverage fell below 90%:\n" + thin.joinToString("\n"), thin.isEmpty())
        assertEquals(true, total > 1000)
    }
}
