package com.nextcraft.forge.ui

/** What a run of characters is, for colouring. Pure data so the tokenizer is unit-tested without Android. */
internal enum class CodeTokenKind { KEYWORD, STRING, NUMBER, COMMENT, ANNOTATION }

internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

internal enum class CodeLanguage {
    /** Kotlin, Java, JS/TS, C/C++, Go, Rust, Swift, Dart, PHP... */
    C_LIKE,

    /** Python, shell, YAML, TOML, Ruby, Makefile. */
    HASH,

    /** HTML, XML, SVG. */
    MARKUP,
    JSON,
    PLAIN,
    ;

    companion object {
        fun forPath(path: String): CodeLanguage = when (path.substringAfterLast('.', "").lowercase()) {
            "kt", "kts", "java", "js", "jsx", "mjs", "ts", "tsx", "c", "h", "cc", "cpp", "hpp", "cs", "go", "rs", "swift", "dart", "php", "gradle", "scala" -> C_LIKE
            "py", "sh", "bash", "zsh", "yml", "yaml", "toml", "rb", "mk", "conf", "properties" -> HASH
            "html", "htm", "xml", "svg" -> MARKUP
            "json" -> JSON
            else -> if (path.substringAfterLast('/') in setOf("Makefile", "Dockerfile")) HASH else PLAIN
        }
    }
}

/**
 * A small line-by-line tokenizer: strings, numbers, comments and keywords. It is not a parser: a block comment
 * that spans lines is only recognised on the line where it opens, which is the right trade for a diff preview.
 */
internal object CodeSyntax {
    private val cLikeKeywords = setOf(
        "abstract", "as", "break", "case", "catch", "class", "const", "continue", "data", "default", "do", "else", "enum", "export",
        "extends", "false", "final", "finally", "for", "fun", "func", "function", "if", "implements", "import", "in", "interface", "is",
        "let", "new", "null", "object", "override", "package", "private", "protected", "public", "return", "sealed", "static", "struct",
        "super", "suspend", "switch", "this", "throw", "true", "try", "typeof", "val", "var", "void", "when", "while", "async", "await",
        "fn", "impl", "mut", "pub", "use", "mod", "nil", "undefined",
    )
    private val hashKeywords = setOf(
        "and", "as", "async", "await", "break", "case", "class", "continue", "def", "do", "done", "elif", "else", "esac", "except", "export",
        "false", "False", "fi", "finally", "for", "from", "function", "if", "import", "in", "is", "lambda", "None", "not", "or", "pass",
        "raise", "return", "then", "true", "True", "try", "while", "with", "yield", "null", "local", "echo",
    )
    private val jsonKeywords = setOf("true", "false", "null")

    fun tokenize(line: String, language: CodeLanguage): List<CodeToken> = when (language) {
        CodeLanguage.PLAIN -> emptyList()
        CodeLanguage.MARKUP -> markup(line)
        else -> code(line, language)
    }

    private fun code(line: String, language: CodeLanguage): List<CodeToken> {
        val keywords = when (language) {
            CodeLanguage.C_LIKE -> cLikeKeywords
            CodeLanguage.HASH -> hashKeywords
            else -> jsonKeywords
        }
        val out = mutableListOf<CodeToken>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                language == CodeLanguage.C_LIKE && line.startsWith("//", i) -> {
                    out += CodeToken(i, line.length, CodeTokenKind.COMMENT); i = line.length
                }
                language == CodeLanguage.C_LIKE && line.startsWith("/*", i) -> {
                    val close = line.indexOf("*/", i + 2)
                    val end = if (close < 0) line.length else close + 2
                    out += CodeToken(i, end, CodeTokenKind.COMMENT); i = end
                }
                // A hash starts a comment at the start of a line or after whitespace (not inside "a#b" or a colour code).
                language == CodeLanguage.HASH && c == '#' && (i == 0 || line[i - 1].isWhitespace()) -> {
                    out += CodeToken(i, line.length, CodeTokenKind.COMMENT); i = line.length
                }
                c == '"' || c == '\'' || (c == '`' && language == CodeLanguage.C_LIKE) -> {
                    val end = stringEnd(line, i)
                    out += CodeToken(i, end, CodeTokenKind.STRING); i = end
                }
                c.isDigit() && (i == 0 || !isIdentifierPart(line[i - 1])) -> {
                    var end = i + 1
                    while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '.' || line[end] == '_')) end++
                    out += CodeToken(i, end, CodeTokenKind.NUMBER); i = end
                }
                c == '@' && (i == 0 || !isIdentifierPart(line[i - 1])) && i + 1 < line.length && isIdentifierStart(line[i + 1]) -> {
                    var end = i + 2
                    while (end < line.length && (isIdentifierPart(line[end]) || line[end] == '.')) end++
                    out += CodeToken(i, end, CodeTokenKind.ANNOTATION); i = end
                }
                isIdentifierStart(c) -> {
                    var end = i + 1
                    while (end < line.length && isIdentifierPart(line[end])) end++
                    if (line.substring(i, end) in keywords) out += CodeToken(i, end, CodeTokenKind.KEYWORD)
                    i = end
                }
                else -> i++
            }
        }
        return out
    }

    private fun markup(line: String): List<CodeToken> {
        val out = mutableListOf<CodeToken>()
        var i = 0
        while (i < line.length) {
            when {
                line.startsWith("<!--", i) -> {
                    val close = line.indexOf("-->", i + 4)
                    val end = if (close < 0) line.length else close + 3
                    out += CodeToken(i, end, CodeTokenKind.COMMENT); i = end
                }
                line[i] == '<' -> {
                    var end = i + 1
                    if (end < line.length && (line[end] == '/' || line[end] == '?' || line[end] == '!')) end++
                    val nameStart = end
                    while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '-' || line[end] == ':' || line[end] == '.')) end++
                    if (end > nameStart) out += CodeToken(i, end, CodeTokenKind.KEYWORD)
                    i = maxOf(end, i + 1)
                }
                line[i] == '"' || line[i] == '\'' -> {
                    val end = stringEnd(line, i)
                    out += CodeToken(i, end, CodeTokenKind.STRING); i = end
                }
                else -> i++
            }
        }
        return out
    }

    /** Index just past the closing quote, or the end of the line when the string does not close here. */
    private fun stringEnd(line: String, start: Int): Int {
        val quote = line[start]
        var i = start + 1
        while (i < line.length) {
            if (line[i] == '\\') i += 2
            else if (line[i] == quote) return i + 1
            else i++
        }
        return line.length
    }

    private fun isIdentifierStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
}
