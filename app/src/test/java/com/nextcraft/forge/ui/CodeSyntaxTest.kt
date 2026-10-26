package com.nextcraft.forge.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeSyntaxTest {
    private fun kinds(line: String, language: CodeLanguage) =
        CodeSyntax.tokenize(line, language).map { line.substring(it.start, it.end) to it.kind }

    @Test
    fun language_followsTheFileExtension() {
        assertEquals(CodeLanguage.C_LIKE, CodeLanguage.forPath("app/src/Main.kt"))
        assertEquals(CodeLanguage.HASH, CodeLanguage.forPath("scripts/build.sh"))
        assertEquals(CodeLanguage.HASH, CodeLanguage.forPath("Makefile"))
        assertEquals(CodeLanguage.MARKUP, CodeLanguage.forPath("index.html"))
        assertEquals(CodeLanguage.JSON, CodeLanguage.forPath("package.json"))
        assertEquals(CodeLanguage.PLAIN, CodeLanguage.forPath("README"))
    }

    @Test
    fun cLike_findsKeywordsStringsNumbersAndComments() {
        val tokens = kinds("val x = \"a//b\" + 42 // note", CodeLanguage.C_LIKE)
        assertEquals(
            listOf(
                "val" to CodeTokenKind.KEYWORD,
                "\"a//b\"" to CodeTokenKind.STRING,
                "42" to CodeTokenKind.NUMBER,
                "// note" to CodeTokenKind.COMMENT,
            ),
            tokens,
        )
    }

    @Test
    fun cLike_blockCommentAndEscapedQuote() {
        assertEquals(listOf("/* hi */" to CodeTokenKind.COMMENT), kinds("/* hi */", CodeLanguage.C_LIKE))
        assertEquals(listOf("\"a\\\"b\"" to CodeTokenKind.STRING), kinds("\"a\\\"b\"", CodeLanguage.C_LIKE))
    }

    @Test
    fun identifiersContainingKeywordsAreNotKeywords() {
        assertTrue(kinds("value valid iffy", CodeLanguage.C_LIKE).isEmpty())
        assertTrue(kinds("x1 = a2", CodeLanguage.C_LIKE).isEmpty())
    }

    @Test
    fun hash_commentNeedsWhitespaceBefore() {
        assertEquals(listOf("# todo" to CodeTokenKind.COMMENT), kinds("# todo", CodeLanguage.HASH))
        assertTrue(kinds("color=#fff", CodeLanguage.HASH).none { it.second == CodeTokenKind.COMMENT })
        assertEquals("def" to CodeTokenKind.KEYWORD, kinds("def run():", CodeLanguage.HASH).first())
    }

    @Test
    fun markup_tagsAndAttributes() {
        assertEquals(
            listOf("<div" to CodeTokenKind.KEYWORD, "\"a\"" to CodeTokenKind.STRING),
            kinds("<div class=\"a\">", CodeLanguage.MARKUP),
        )
        assertEquals(listOf("<!-- x -->" to CodeTokenKind.COMMENT), kinds("<!-- x -->", CodeLanguage.MARKUP))
    }

    @Test
    fun unterminatedStringRunsToTheEndOfTheLine() {
        val line = "say(\"open"
        val token = CodeSyntax.tokenize(line, CodeLanguage.C_LIKE).single()
        assertEquals(CodeTokenKind.STRING, token.kind)
        assertEquals(line.length, token.end)
    }

    @Test
    fun tokensNeverOverlapAndStayInBounds() {
        val samples = listOf("if (a == \"b\") { return 1 } // x", "<a href='x'><!-- c --></a>", "\\\"", "", "'")
        CodeLanguage.values().forEach { language ->
            samples.forEach { line ->
                var last = 0
                CodeSyntax.tokenize(line, language).forEach { t ->
                    assertTrue("$language/$line: $t", t.start >= last && t.end > t.start && t.end <= line.length)
                    last = t.end
                }
            }
        }
    }
}

class FileViewerLinesTest {
    @Test
    fun numbersLinesFromOneAndIgnoresTheTrailingNewline() {
        val lines = fileAsViewerLines("a\nb\n")
        assertEquals(listOf("a", "b"), lines.map { it.text })
        assertEquals(listOf(1, 2), lines.map { it.newLine })
    }

    @Test
    fun windowsLineEndingsDoNotLeaveCarriageReturns() {
        assertEquals(listOf("a", "b"), fileAsViewerLines("a\r\nb").map { it.text })
    }

    @Test
    fun emptyFileIsOneEmptyLine() {
        assertEquals(1, fileAsViewerLines("").size)
    }
}
