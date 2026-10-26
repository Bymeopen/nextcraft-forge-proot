package com.nextcraft.forge.ui

import com.nextcraft.forge.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps [MessageText] honest: the table, the English resources and the Indonesian resources must agree. */
class MessageTextTest {
    private fun strings(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    private val en = strings("src/main/res/values/strings.xml")
    private val id = strings("src/main/res/values-in/strings.xml")
    private val nameOf: Map<Int, String> = R.string::class.java.fields.associate { it.getInt(null) to it.name }

    @Test
    fun everyExactSentenceEqualsItsEnglishResourceAndIsTranslated() {
        MessageText.exact.forEach { (sentence, resId) ->
            val name = nameOf.getValue(resId)
            assertEquals("$name must hold the English sentence it translates", sentence, en.getValue(name))
            assertNotEquals("$name is not translated", sentence, id.getValue(name))
        }
    }

    @Test
    fun everyRuleMatchesItsOwnEnglishTemplateAndIsTranslated() {
        MessageText.rules.forEach { rule ->
            val name = nameOf.getValue(rule.id)
            val template = en.getValue(name)
            var sample = template
            val args = Regex("%(\\d)\\$[sd]").findAll(template).map { it.groupValues[1].toInt() }.toSet().sorted()
            args.forEach { sample = sample.replace("%$it\$s", "A$it").replace("%$it\$d", "7") }
            val matched = rule.regex.matchEntire(sample)
            assertNotNull("rule for $name must match '$sample'", matched)
            assertEquals("$name capture count", args.size, matched!!.groupValues.size - 1)
            assertTrue("$name is not translated", id.getValue(name) != template)
        }
    }

    @Test
    fun knownSentencesAreFoundWhateverTheWhitespace() {
        assertNotNull(MessageText.match("  Check this API key or select another saved key.\n"))
        assertEquals(null, MessageText.match("a server reply nobody mapped"))
    }

    /**
     * Source scan: fixed English sentences in the files whose text is shown on screen must be mapped, so a new
     * sentence added there fails here instead of silently reaching Indonesian users in English.
     */
    @Test
    fun sentencesInUserFacingSourcesAreMapped() {
        val sentence = Regex("\"((?:[A-Z][a-z]+)(?: [A-Za-z][^\"\\\\]*){2,})\"")
        val skipLine = Regex("Log\\.|Regex\\(|\\.contains\\(|startsWith\\(|endsWith\\(|const val|@Suppress|// |\\* |putExtra")
        // Sentences that are not shown as written: internal errors, shell text, prompts for the agent.
        val allowed = setOf(
            "Unsafe Gradle task name: '\$task'",
            "Invalid application UID for \$packageName",
            "Update download exceeds the allowed size.", // covered by an exact entry; listed twice in source
        )
        val files = listOf(
            "src/main/java/com/nextcraft/forge/runtime/AndroidBuildSupport.kt",
            "src/main/java/com/nextcraft/forge/network/ProviderApiClient.kt",
            "src/main/java/com/nextcraft/forge/runtime/SshController.kt",
            "src/main/java/com/nextcraft/forge/runtime/SnapshotManager.kt",
        )
        val missing = files.flatMap { path ->
            File(path).readLines().filterNot { skipLine.containsMatchIn(it) }.flatMap { line ->
                sentence.findAll(line).map { it.groupValues[1] }.filter { it.length >= 14 }.toList()
            }
        }.filter { it !in allowed }
            // Templates: $var and ${expr} stand for any value.
            .filter { MessageText.match(it.replace(Regex("\\$\\{[^}]*}|\\$[A-Za-z_]+"), "1")) == null }
            // Prompts and progress lines that are shell/log text rather than UI.
            .filterNot { it.startsWith("Checking the SSH") || it.startsWith("Installing the OpenSSH") || it.startsWith("Preparing the SSH") }
        assertTrue("English sentences with no translation entry:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
