package com.nextcraft.forge.runtime

import org.json.JSONArray
import org.json.JSONObject

/** One line of `rea doctor`: a capability that was tested for real. [status] is "ok", "warn" or "fail". */
data class ReaCheck(val id: String, val label: String, val status: String, val detail: String, val hint: String)

data class ReaError(val code: String, val message: String, val hint: String)

/** The JSON object every `rea ... --json` command prints on its last stdout line (schema 1). */
data class ReaResponse(
    val command: String,
    val ok: Boolean,
    val mode: String?,
    val data: JSONObject?,
    val warnings: List<String>,
    val error: ReaError?,
) {
    /** The doctor's capability list; empty for other commands. */
    val checks: List<ReaCheck>
        get() {
            val array = data?.optJSONArray("checks") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                ReaCheck(
                    id = item.optString("id"),
                    label = item.optString("label"),
                    status = item.optString("status"),
                    detail = item.optString("detail"),
                    hint = item.optString("hint"),
                )
            }
        }
}

object ReaJson {
    const val SCHEMA = 1

    /**
     * Takes the last line of [output] that is a REA result. The chroot merges stderr into the output, so notices and
     * warnings can precede it; lines that are not JSON, or JSON of another shape, are skipped.
     */
    fun parse(output: String): ReaResponse? {
        for (line in output.lineSequence().map(String::trim).toList().asReversed()) {
            if (!line.startsWith("{") || !line.endsWith("}")) continue
            val json = runCatching { JSONObject(line) }.getOrNull() ?: continue
            if (json.optInt("schema", -1) != SCHEMA || !json.has("command")) continue
            val warnings = json.optJSONArray("warnings")?.let { array -> (0 until array.length()).map { array.optString(it) } }.orEmpty()
            val error = json.optJSONObject("error")?.let {
                ReaError(it.optString("code"), it.optString("message"), it.optString("hint"))
            }
            return ReaResponse(
                command = json.optString("command"),
                ok = json.optBoolean("ok", false),
                mode = json.optString("mode").takeIf { it.isNotEmpty() },
                data = json.optJSONObject("data"),
                warnings = warnings,
                error = error,
            )
        }
        return null
    }
}

data class ReaIdentity(
    val path: String,
    val type: String,
    val arch: String?,
    val bits: Int?,
    val size: Long,
    val md5: String,
    val sha256: String,
    val entropy: Double,
    val packedHint: Boolean,
)

data class ReaFunction(val name: String, val addr: Long, val size: Long)

data class ReaString(val offset: Long, val encoding: String, val text: String, val score: Int)

data class ReaInstruction(val addr: Long, val bytes: String, val mnemonic: String, val operands: String)

data class ReaElf(
    val type: String,
    val machine: String,
    val entry: Long,
    val stripped: Boolean,
    val needed: List<String>,
    val sectionCount: Int,
    val segmentCount: Int,
    val functions: List<ReaFunction>,
    val imports: List<String>,
)

data class ReaDisasm(val function: String?, val address: Long, val instructions: List<ReaInstruction>)

/** What `rea analyze --json` found; the parts the user did not ask for are empty or null. */
data class ReaAnalysis(
    val identity: ReaIdentity,
    val elf: ReaElf?,
    val strings: List<ReaString>,
    val stringsTotal: Int,
    val disasm: ReaDisasm?,
)

private fun org.json.JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Reads the `data` of an `analyze` response; null when it does not carry an identity. */
fun ReaResponse.analysis(): ReaAnalysis? {
    val root = data ?: return null
    val id = root.optJSONObject("identify") ?: return null
    val identity = ReaIdentity(
        path = id.optString("path"),
        type = id.optString("type"),
        arch = id.optString("arch").takeIf { it.isNotEmpty() },
        bits = if (id.has("bits")) id.optInt("bits") else null,
        size = id.optLong("size"),
        md5 = id.optString("md5"),
        sha256 = id.optString("sha256"),
        entropy = id.optDouble("entropy", 0.0),
        packedHint = id.optBoolean("packed_hint", false),
    )
    val elf = root.optJSONObject("elf")?.let { e ->
        ReaElf(
            type = e.optString("type"),
            machine = e.optString("machine"),
            entry = e.optLong("entry"),
            stripped = e.optBoolean("stripped"),
            needed = e.optJSONArray("needed")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
            sectionCount = e.optJSONArray("sections")?.length() ?: 0,
            segmentCount = e.optJSONArray("segments")?.length() ?: 0,
            functions = e.optJSONArray("functions").objects().map { ReaFunction(it.optString("name"), it.optLong("addr"), it.optLong("size")) },
            imports = e.optJSONArray("imports")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        )
    }
    val stringsObject = root.optJSONObject("strings")
    val strings = stringsObject?.optJSONArray("strings").objects().map {
        ReaString(it.optLong("offset"), it.optString("encoding"), it.optString("text"), it.optInt("score"))
    }
    val disasm = root.optJSONObject("disasm")?.let { d ->
        ReaDisasm(
            function = d.optString("function").takeIf { it.isNotEmpty() },
            address = d.optLong("address"),
            instructions = d.optJSONArray("instructions").objects().map {
                ReaInstruction(it.optLong("addr"), it.optString("bytes"), it.optString("mnemonic"), it.optString("operands"))
            },
        )
    }
    return ReaAnalysis(identity, elf, strings, stringsObject?.optInt("total") ?: strings.size, disasm)
}

// --- Learn mode (`rea learn ... --json`) ---------------------------------------------------------------------------

data class ReaLesson(
    val n: Int,
    val stage: Int,
    val stageName: String,
    val title: String,
    val exerciseDone: Boolean,
    val quizBest: Int?,
    val quizTotal: Int?,
)

data class ReaLessonDetail(
    val n: Int,
    val stageName: String,
    val title: String,
    val theory: String,
    val diagram: String,
    val task: String,
    val hint: String,
    val requires: List<String>,
    val hasExercise: Boolean,
    val started: Boolean,
    val exerciseDone: Boolean,
    val attempts: Int,
    val exerciseDir: String?,
)

data class ReaQuizQuestion(val text: String, val options: List<String>)

data class ReaQuizResult(val score: Int, val total: Int, val passed: Boolean)

data class ReaCheckResult(val correct: Boolean, val message: String, val hint: String?)

private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

/** `rea learn list`: every lesson with its progress; empty for other commands. */
fun ReaResponse.lessons(): List<ReaLesson> = data?.optJSONArray("lessons").objects().map { item ->
    val quiz = item.optJSONObject("quiz")
    ReaLesson(
        n = item.optInt("n"),
        stage = item.optInt("stage"),
        stageName = item.optString("stage_name"),
        title = item.optString("title"),
        exerciseDone = item.optBoolean("exercise_done"),
        quizBest = quiz?.optInt("best"),
        quizTotal = quiz?.optInt("total"),
    )
}

/** `rea learn show`: theory, diagram, task and state of one lesson. */
fun ReaResponse.lessonDetail(): ReaLessonDetail? {
    val d = data ?: return null
    if (!d.has("theory")) return null
    return ReaLessonDetail(
        n = d.optInt("n"),
        stageName = d.optString("stage_name"),
        title = d.optString("title"),
        theory = d.optString("theory"),
        diagram = d.optString("diagram"),
        task = d.optString("task"),
        hint = d.optString("hint"),
        requires = d.optJSONArray("requires").strings(),
        hasExercise = d.optBoolean("has_exercise"),
        started = d.optBoolean("started"),
        exerciseDone = d.optBoolean("exercise_done"),
        attempts = d.optInt("attempts"),
        exerciseDir = d.stringOrNull("exercise_dir"),
    )
}

/** `rea learn quiz` without answers: the questions (the answer key is never sent). */
fun ReaResponse.quizQuestions(): List<ReaQuizQuestion> = data?.optJSONArray("questions").objects().map {
    ReaQuizQuestion(it.optString("text"), it.optJSONArray("options").strings())
}

/** `rea learn quiz --answers ...`: the grade. */
fun ReaResponse.quizResult(): ReaQuizResult? {
    val d = data ?: return null
    if (!d.has("score")) return null
    return ReaQuizResult(d.optInt("score"), d.optInt("total"), d.optBoolean("passed"))
}

/** `rea learn check`: whether the answer was right, with the explanation or the hint. */
fun ReaResponse.checkResult(): ReaCheckResult? {
    val d = data ?: return null
    if (!d.has("correct")) return null
    return ReaCheckResult(d.optBoolean("correct"), d.optString("message"), d.stringOrNull("hint"))
}

/** `rea learn answer`: the revealed answers joined for display. */
fun ReaResponse.revealedAnswer(): String? = data?.optJSONArray("answers")?.strings()?.joinToString(" / ")?.takeIf { it.isNotBlank() }
