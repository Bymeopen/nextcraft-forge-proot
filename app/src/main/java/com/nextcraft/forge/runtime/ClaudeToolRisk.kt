package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private val PERMISSION_DENIAL_REGEX = Regex(
    "permission.{0,60}denied|denied.{0,40}permission|not allowed|doesn't want to proceed|user rejected|blocked by|requires approval",
    RegexOption.IGNORE_CASE,
)

/** True for Claude Code's text when a tool call was refused by permissions rather than failing for a technical reason. */
internal fun isPermissionDenialText(text: String): Boolean = PERMISSION_DENIAL_REGEX.containsMatchIn(text)

private val DANGEROUS_PATTERNS = listOf(
    "git push", "git reset", "git clean", "git checkout --", "sudo", "curl ", "wget ", "chmod ", "chown ", "mkfs",
    "dd if=", "/dev/block", "/dev/loop", "/dev/sd", "/dev/zero", "/dev/mem", "/dev/kmem", "/proc/", "umount",
    "| sh", "| bash", "find -exec", "find -delete", "pocketdev-bridge", "nextcraft-bridge", "pocket-bridge",
    "/opt/pocket", "/opt/nextcraft", ".claude", ".git-credentials",
    // Root shell on Android itself (Settings → Android system access).
    "android-shell", "android-screencap", "android-dumpsys", "nsenter",
    // Credential stores the Bash route could cat: the SSH keys, the GitHub CLI token, agent homes and token files.
    ".ssh/", "/.ssh", ".config/gh", "/.dsh/", "/.gemini/", "/.npmrc", "/.netrc",
)

private val SAFE_STREAM_REDIRECT_REGEX =
    Regex("(?:[12]?>>?\\s*/dev/null|&>\\s*/dev/null|[12]>&[12]|<\\s*/dev/null)")

// Commands whose first word is auto-approved; everything else (python, node, sh, find -exec ...) needs the user.
private val SAFE_BASH_COMMANDS = setOf(
    "ls", "cat", "pwd", "echo", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "sort", "uniq", "diff", "cd",
    "true", "false", "test", "which", "type", "stat", "file", "tree", "mkdir", "touch", "date", "printf",
    "basename", "dirname", "realpath", "readlink", "cut", "tr", "awk", "sed", "find", "xargs", "jq", "du", "df",
    "env", "md5sum", "sha256sum", "cp", "mv", "gradle", "javac", "kotlinc",
)
private val UNSAFE_TOOL_FLAG_REGEX = Regex("""--pre(?:=.*)?|--hostname-bin(?:=.*)?|--compress-program(?:=.*)?|--output(?:=.*)?|-fprint0?|-fprintf|-fls""")
private val ENV_ASSIGNMENT_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*=\\S*")

// awk and sed are safe for plain text processing, but their scripts can run commands or write files.
private val AWK_WORD_REGEX = Regex("\\bawk\\b")
private val AWK_EXEC_REGEX = Regex("""\|\s*\\?"|system\s*\(|getline|\|&|\bpopen\b|\bENVIRON\b""")
private val SED_WORD_REGEX = Regex("\\bsed\\b")
private val SED_EXEC_OR_WRITE_REGEX = Regex("""(?:^|[;{}'"\s/0-9$,!])(?:e(?:['";}\s]|$)|[wWrR]\s)""")

// jq can print the process environment (`jq -n env`, `$ENV`), which holds provider keys.
private val JQ_WORD_REGEX = Regex("\\bjq\\b")
private val JQ_ENV_REGEX = Regex("""\benv\b|[$]ENV|\binput_filename\b""")

// A shell variable expansion can print an exported secret (`echo "$ANTHROPIC_API_KEY"`); those commands need the user.
private val VARIABLE_EXPANSION_REGEX = Regex("""\$[A-Za-z_{@*#?0-9!-]""")

private fun scriptCanEscapeSandbox(text: String): Boolean =
    (JQ_WORD_REGEX.containsMatchIn(text) && JQ_ENV_REGEX.containsMatchIn(text)) ||
    (AWK_WORD_REGEX.containsMatchIn(text) && AWK_EXEC_REGEX.containsMatchIn(text)) ||
        (SED_WORD_REGEX.containsMatchIn(text) && SED_EXEC_OR_WRITE_REGEX.containsMatchIn(text))

/** Commands that create, overwrite or move files; their path arguments must stay inside the project. */
/** The option letters of a short-option cluster (`-Ei` gives "Ei"); empty for a long option or a non-option word. */
private fun String.shortFlagCluster(): String =
    if (startsWith("-") && !startsWith("--")) drop(1).takeWhile { it.isLetter() } else ""

private fun mutatesFiles(first: String, words: List<String>): Boolean =
    first in setOf("cp", "mv", "mkdir", "touch", "uniq") ||
        (first == "sed" && words.any { it.startsWith("--in-place") || it.trim('\'', '"').shortFlagCluster().contains('i') })

private fun isOutsideWorkspacePath(word: String): Boolean {
    var arg = word.trim('\'', '"')
    // cp/mv/install take the destination as an option too: --target-directory=DIR or -tDIR (a separate `-t DIR`
    // value is already checked as its own word).
    arg = when {
        arg.startsWith("--target-directory=") -> arg.removePrefix("--target-directory=")
        arg.startsWith("-t") && arg.length > 2 && !arg.startsWith("--") -> arg.removePrefix("-t")
        else -> arg
    }
    if (arg.startsWith("-") || arg.isEmpty()) return false
    // ~/.bashrc and friends: the tilde expands to the home of root, which is outside the project.
    if (arg == "~" || arg.startsWith("~/")) return true
    if (isSensitivePath(arg)) return true
    return arg.startsWith("/") && !arg.startsWith("/workspace/") && !arg.startsWith("/tmp/")
}

private val SAFE_GIT_SUBCOMMANDS = setOf(
    "status", "diff", "log", "show", "add", "commit", "branch", "rev-parse", "ls-files", "ls-tree", "blame",
    "remote", "fetch", "pull", "stash", "restore", "switch", "tag",
)
private val SENSITIVE_PATH_PREFIXES = listOf(
    "/opt/", "/etc/", "/proc/", "/sys/", "/dev/", "/usr/", "/bin", "/sbin", "/lib", "/var/",
    "/pocketdev-bridge", "/nextcraft-bridge", "/pocket-bridge",
)
private val SENSITIVE_PATH_PARTS = listOf("..", "/.claude", "/.ssh", ".git-credentials", "/.config/gh", "permission-hook", "/.git/")

// A file written under .git/ (hooks, config with core.fsmonitor / diff.external) runs the next time git is auto-approved.
private fun isSensitivePath(path: String): Boolean =
    SENSITIVE_PATH_PREFIXES.any(path::startsWith) || SENSITIVE_PATH_PARTS.any(path::contains) || path.startsWith(".git/")

/** Files whose content must never reach the model; a glob or quote trick in a command is matched against them. */
private val PROTECTED_LITERAL_PATHS = listOf(
    "/proc/self/environ", "/proc/1/environ", "/proc/thread-self/environ",
    "/root/.claude/.credentials.json", "~/.claude/.credentials.json", "/root/.claude.json", "~/.claude.json",
    "/root/.ssh/id_ed25519", "~/.ssh/id_ed25519", "/root/.ssh/id_rsa", "~/.ssh/id_rsa",
    "/root/.git-credentials", "~/.git-credentials", "/root/.config/gh/hosts.yml", "~/.config/gh/hosts.yml",
)

// A word with glob characters that can expand to a protected file (a '?' standing in for a letter of /proc/self/environ, or a
// '*' inside the name of the Claude credentials folder).
private fun commandTargetsProtectedFile(command: String): Boolean {
    val words = command.split(Regex("\\s+")).map { it.trim('\'', '"') }
    return words.any { word ->
        if (word.none { it == '*' || it == '?' || it == '[' }) return@any false
        val regex = runCatching {
            Regex(buildString {
                var i = 0
                while (i < word.length) {
                    when (val c = word[i]) {
                        '*' -> append("[^/]*")
                        '?' -> append("[^/]")
                        '[' -> {
                            val end = word.indexOf(']', i + 1)
                            if (end > i) { append(word.substring(i, end + 1)); i = end } else append("\\[")
                        }
                        else -> append(Regex.escape(c.toString()))
                    }
                    i++
                }
            })
        }.getOrNull() ?: return@any true
        PROTECTED_LITERAL_PATHS.any(regex::matches)
    }
}

private val CREDENTIAL_PATH_PARTS = listOf(
    "/proc/", "/.claude", "/.ssh", ".git-credentials", "/.config/gh", "permission-hook",
    "/pocketdev-bridge", "/nextcraft-bridge", "/pocket-bridge", "/.gradle/gradle.properties",
    "/.dsh/", "/.gemini/", "/.npmrc", "/.netrc",
)

private fun isCredentialPath(path: String): Boolean = CREDENTIAL_PATH_PARTS.any(path::contains)

private fun maskQuotedAndEscapedSeparators(input: String): String {
    val out = StringBuilder(input.length)
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false
    for (ch in input) {
        if (escaped) {
            out.append(if (ch == '|' || ch == '&' || ch == ';') '_' else ch)
            escaped = false
            continue
        }
        if (ch == '\\' && !inSingleQuote) {
            escaped = true
            out.append(ch)
            continue
        }
        if (ch == '\'' && !inDoubleQuote) {
            inSingleQuote = !inSingleQuote
            out.append(ch)
            continue
        }
        if (ch == '"' && !inSingleQuote) {
            inDoubleQuote = !inDoubleQuote
            out.append(ch)
            continue
        }
        if ((inSingleQuote || inDoubleQuote) && (ch == '|' || ch == '&' || ch == ';')) {
            out.append('_')
        } else {
            out.append(ch)
        }
    }
    return out.toString()
}

/** System directories whose programs may be named by full path; any other path could point at a file the agent wrote. */
/** Options of env/xargs that take a value as the next word. */
private val WRAPPER_OPTIONS_WITH_VALUE = setOf("-u", "-C", "-S", "-I", "-i", "-E", "-e", "-d", "-L", "-l", "-n", "-P", "-s", "-a")
private val WRAPPER_LONG_OPTIONS_WITH_VALUE = setOf("unset", "chdir", "split-string", "replace", "eof", "delimiter", "max-lines", "max-args", "max-procs", "max-chars", "arg-file")

private val TRUSTED_COMMAND_DIRS = listOf("/usr/bin/", "/bin/", "/usr/local/bin/")

private fun isSafeBashSegment(segment: String): Boolean {
    val words = segment.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    // A leading VAR=value (LD_PRELOAD=./x.so cat f, PATH=./bin ls) changes what the "safe" command really runs.
    if (words.firstOrNull()?.matches(ENV_ASSIGNMENT_REGEX) == true) return false
    val firstWord = words.firstOrNull()?.trim('\'', '"') ?: return false
    // `./ls` or `/workspace/cat` is a program the agent may have just written, not the allowlisted tool of that name.
    if ('/' in firstWord && TRUSTED_COMMAND_DIRS.none(firstWord::startsWith)) return false
    // `/usr/bin/../../workspace/x/cat` starts with a trusted directory but runs a file of the project.
    if (".." in firstWord) return false
    val first = firstWord.substringAfterLast('/')
    if (first == "env" || first == "xargs") {
        // Wrappers run another command, so judge that command. A bare `env` dumps every exported
        // variable (including provider API keys) into the model context and is never auto-approved.
        // `env VAR=value cmd` is refused for the same reason as a leading assignment.
        if (words.drop(1).any { it.matches(ENV_ASSIGNMENT_REGEX) }) return false
        val options = words.drop(1).takeWhile { it.startsWith("-") }
        // `env -u cat sh -c ...` / `xargs -I cat sh -c ...`: an option's value would be mistaken for the wrapped command.
        if (options.any { it in WRAPPER_OPTIONS_WITH_VALUE || (it.startsWith("--") && '=' !in it && it.removePrefix("--") in WRAPPER_LONG_OPTIONS_WITH_VALUE) }) return false
        val wrapped = words.drop(1).dropWhile { it.startsWith("-") }
        if (wrapped.isEmpty()) return first == "xargs"
        return isSafeBashSegment(wrapped.joinToString(" "))
    }
    // Allowlisted tools that can still run a program (rg --pre, sort --compress-program) or write a file
    // (find -fprint, git diff --output, sort/tree -o): never auto-approved.
    val args = words.drop(1).map { it.trim('\'', '"') }
    if (args.any { UNSAFE_TOOL_FLAG_REGEX.matches(it) }) return false
    // -o may sit inside a cluster (-uo out) and getopt_long accepts prefixes of --output.
    if ((first == "sort" || first == "tree") && args.any { it.startsWith("--out") || it.shortFlagCluster().contains('o') }) return false
    // sed/awk read their program from a file with -f/--file; that file may hold `e`/system() the inline check cannot see.
    if ((first == "sed" || first == "awk") && args.any { it == "--file" || it.startsWith("--file=") || it.shortFlagCluster().contains('f') }) return false
    // `date -s` sets the system clock.
    if (first == "date" && args.any { it.shortFlagCluster().contains('s') || it.startsWith("--set") }) return false
    if (mutatesFiles(first, words) && words.drop(1).any(::isOutsideWorkspacePath)) return false
    if (first == "git") return words.getOrNull(1)?.trim('\'', '"') in SAFE_GIT_SUBCOMMANDS
    if (first == "find" && words.any { it.trim('\'', '"') in setOf("-exec", "-execdir", "-delete", "-ok", "-okdir") }) return false
    return first in SAFE_BASH_COMMANDS
}

/**
 * Default-deny for Bash: only simple commands from [SAFE_BASH_COMMANDS] chained without
 * substitution or redirection are auto-approved; anything else is HIGH (user prompt or deny).
 */
internal fun classifyToolRisk(tool: String, command: String?, paths: List<String> = emptyList()): RiskLevel {
    val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
    if (DANGEROUS_PATTERNS.any(preview::contains)) return RiskLevel.HIGH
    if (tool == "Bash") {
        // `cat /pro''c/self/environ` and `cat $'/proc/self/environ'` read the same file as the plain spelling.
        val unquoted = preview.replace(Regex("""\$?['"\\]"""), "")
        if (unquoted != preview && DANGEROUS_PATTERNS.any(unquoted::contains)) return RiskLevel.HIGH
        if (commandTargetsProtectedFile(command.orEmpty())) return RiskLevel.HIGH
    }
    if (tool in setOf("Write", "Edit", "NotebookEdit") && paths.any(::isSensitivePath)) return RiskLevel.HIGH
    // Reads and searches must not reach credentials or the process environment either (/proc/self/environ, ~/.claude).
    if (tool != "Bash" && paths.any(::isCredentialPath)) return RiskLevel.HIGH
    return when (tool) {
        "Bash" -> {
            val text = command.orEmpty().replace(SAFE_STREAM_REDIRECT_REGEX, " ")
            val hasUnsafeSyntax = listOf("`", "$(", "<(", ">", "<", "\n", "\r").any(text::contains) ||
                VARIABLE_EXPANSION_REGEX.containsMatchIn(text) ||
                scriptCanEscapeSandbox(text)
            val masked = maskQuotedAndEscapedSeparators(text)
            val segments = masked.split(Regex("&&|\\|\\||[;|&]")).filter(String::isNotBlank)
            if (text.isBlank() || hasUnsafeSyntax || segments.isEmpty() || !segments.all(::isSafeBashSegment)) {
                RiskLevel.HIGH
            } else {
                RiskLevel.REVIEW
            }
        }
        "Write", "Edit", "NotebookEdit" -> RiskLevel.REVIEW
        else -> RiskLevel.SAFE
    }
}

/** How long to wait for the follow-up turn of a steering message before ending the session. */
internal const val STEER_FOLLOW_UP_WAIT_MS = 20_000L
