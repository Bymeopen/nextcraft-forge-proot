package com.nextcraft.forge.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaResultTest {
    private val doctorLine =
        """{"schema":1,"command":"doctor","ok":true,"data":{"checks":[{"id":"ptrace","label":"ptrace","status":"ok","detail":"PTRACE_TRACEME dan exec berhasil","hint":""},{"id":"qemu","label":"qemu-aarch64","status":"warn","detail":"tidak terpasang","hint":"apt install qemu-user"}],"version":"0.1.0"},"warnings":["w1"],"error":null,"mode":"full-dynamic"}"""

    @Test
    fun parsesTheLastJsonLineAmongNoise() {
        val output = "REA hanya untuk penggunaan yang sah.\nsome {not json}\n$doctorLine\n"
        val response = ReaJson.parse(output)
        assertNotNull(response)
        response!!
        assertTrue(response.ok)
        assertEquals("doctor", response.command)
        assertEquals("full-dynamic", response.mode)
        assertEquals(listOf("w1"), response.warnings)
        assertEquals(2, response.checks.size)
        assertEquals(ReaCheck("qemu", "qemu-aarch64", "warn", "tidak terpasang", "apt install qemu-user"), response.checks[1])
    }

    @Test
    fun parsesAFailureWithItsHint() {
        val line = """{"schema":1,"command":"id","ok":false,"data":{},"warnings":[],"error":{"code":"file_not_found","message":"Berkas tidak ditemukan: x","hint":"Periksa jalur"}}"""
        val response = ReaJson.parse("bash: warning\n$line")!!
        assertFalse(response.ok)
        assertEquals(ReaError("file_not_found", "Berkas tidak ditemukan: x", "Periksa jalur"), response.error)
        assertNull(response.mode)
        assertTrue(response.checks.isEmpty())
    }

    @Test
    fun ignoresOutputThatIsNotAReaResult() {
        assertNull(ReaJson.parse(""))
        assertNull(ReaJson.parse("bash: rea: command not found"))
        // JSON of another shape or another schema version is not trusted.
        assertNull(ReaJson.parse("""{"schema":2,"command":"doctor","ok":true}"""))
        assertNull(ReaJson.parse("""{"hello":"world"}"""))
    }

    @Test
    fun theInstallerVersionMatchesThePythonPackage() {
        // Unit tests run with the app module as the working directory.
        val init = File("src/main/assets/rea/rea/__init__.py").readText()
        assertTrue(init.contains("__version__ = \"${ReaController.REA_VERSION}\""))
    }

    @Test
    fun theInstallScriptUsesTheStagedPackageAndTheVersionMarker() {
        val script = ReaController.installScript()
        assertTrue(script.contains("cp -a /workspace/rea/stage/rea /opt/rea/lib/rea"))
        assertTrue(script.contains("echo \"${ReaController.REA_VERSION}\" > /root/.nextcraft-rea-version"))
        assertTrue(script.contains("cp /root/.nextcraft-rea-version /root/.pocket-rea-version"))
        // The wrapper must pass its arguments through unchanged.
        assertTrue(script.contains("python3 -m rea \"\$@\""))
    }

    @Test
    fun theOptionalToolsScriptInstallsTheDynamicToolsAndNeverAbortsEarly() {
        val script = ReaController.optionalToolsScript()
        assertTrue(script.contains("apt-get install -y -qq strace ltrace qemu-user binwalk"))
        assertTrue(script.contains("python3-unicorn"))
        assertTrue(script.contains("pip install --break-system-packages frida"))
        assertTrue(script.contains("pip install --break-system-packages unicorn"))
        // `set -e` would let one missing package block unicorn.
        assertTrue(!script.contains("set -e"))
        assertTrue(script.trimEnd().endsWith("true"))
    }
}

class ReaAnalysisTest {
    private val line =
        """{"schema":1,"command":"analyze","ok":true,"data":{"identify":{"path":"t","type":"ELF","arch":"arm64","bits":64,"size":15984,"md5":"m","sha256":"abcdef0123456789abcdef","entropy":5.9,"packed_hint":false},"elf":{"type":"dyn","machine":"aarch64","entry":4192,"stripped":false,"needed":["libc.so.6"],"sections":[{},{}],"segments":[{}],"functions":[{"name":"main","addr":4450,"size":40},{"name":"helper","addr":4400,"size":20}],"imports":["puts"]},"strings":{"strings":[{"offset":8206,"encoding":"ascii","text":"https://example.com","score":6}],"total":9,"truncated":true},"disasm":{"function":"main","address":4450,"instructions":[{"addr":4450,"size":4,"bytes":"f30f1efa","mnemonic":"endbr64","operands":""},{"addr":4454,"size":1,"bytes":"55","mnemonic":"push","operands":"rbp"}]}},"warnings":[],"error":null}"""

    @Test
    fun parsesAnAnalysis() {
        val analysis = ReaJson.parse(line)!!.analysis()!!
        assertEquals("ELF", analysis.identity.type)
        assertEquals("arm64", analysis.identity.arch)
        assertEquals(64, analysis.identity.bits)
        val elf = analysis.elf!!
        assertEquals(4192L, elf.entry)
        assertEquals(2, elf.sectionCount)
        assertEquals(listOf(ReaFunction("main", 4450, 40), ReaFunction("helper", 4400, 20)), elf.functions)
        assertEquals(listOf("libc.so.6"), elf.needed)
        assertEquals(1, analysis.strings.size)
        assertEquals(9, analysis.stringsTotal)
        assertEquals("endbr64", analysis.disasm!!.instructions[0].mnemonic)
        assertEquals("rbp", analysis.disasm!!.instructions[1].operands)
    }

    @Test
    fun partsThatWereNotRequestedStayEmpty() {
        val bare = """{"schema":1,"command":"analyze","ok":true,"data":{"identify":{"path":"a.txt","type":"text","size":3,"md5":"m","sha256":"s","entropy":1.0,"packed_hint":false}},"warnings":[],"error":null}"""
        val analysis = ReaJson.parse(bare)!!.analysis()!!
        assertNull(analysis.elf)
        assertNull(analysis.disasm)
        assertTrue(analysis.strings.isEmpty())
        assertNull(analysis.identity.arch)
        assertNull(analysis.identity.bits)
    }

    @Test
    fun aResponseWithoutIdentityIsNotAnAnalysis() {
        assertNull(ReaJson.parse("""{"schema":1,"command":"doctor","ok":true,"data":{"checks":[]},"warnings":[],"error":null}""")!!.analysis())
    }

    @Test
    fun theAnalyzeCommandFollowsTheOptions() {
        assertEquals("rea analyze 'a b.elf' --json", ReaController.analyzeCommand("a b.elf", strings = false, interesting = false, symbol = null))
        assertEquals(
            "rea analyze 'x' --json --strings --max-strings 300 --interesting --disasm 'main' --count 80",
            ReaController.analyzeCommand("x", strings = true, interesting = true, symbol = "main"),
        )
        // A blank symbol means "start at the entry point".
        assertEquals("rea analyze 'x' --json --disasm --count 80", ReaController.analyzeCommand("x", strings = false, interesting = false, symbol = ""))
        // A quote in the file name cannot break out of the command.
        assertTrue(ReaController.analyzeCommand("a'; rm -rf /; '", strings = false, interesting = false, symbol = null).contains("'\"'\"'"))
    }
}

class ReaLearnParsingTest {
    // ReaJson reads one line, as the CLI prints it; the samples below are wrapped for readability only.
    private fun parse(data: String, command: String = "learn") =
        ReaJson.parse("""{"schema":1,"command":"$command","ok":true,"data":${data.replace("\n", "")},"warnings":[],"error":null}""")!!

    @Test
    fun parsesTheLessonListWithProgress() {
        val response = parse(
            """{"stages":["Fondasi"],"lessons":[
              {"n":1,"stage":1,"stage_name":"Fondasi","title":"Apa itu biner","exercise_done":true,"quiz":{"score":1,"total":2,"best":2},"has_exercise":true},
              {"n":2,"stage":1,"stage_name":"Fondasi","title":"Bit dan byte","exercise_done":false,"quiz":null,"has_exercise":true}],
              "total":2,"exercises_done":1}""",
        )
        val lessons = response.lessons()
        assertEquals(2, lessons.size)
        assertTrue(lessons[0].exerciseDone)
        assertEquals(2, lessons[0].quizBest)
        assertEquals(2, lessons[0].quizTotal)
        assertNull(lessons[1].quizBest)
        assertEquals("Bit dan byte", lessons[1].title)
    }

    @Test
    fun parsesALessonAndTreatsANullFolderAsMissing() {
        val detail = parse(
            """{"n":4,"stage":2,"stage_name":"Format biner","title":"Simbol","exercise_done":false,"quiz":null,"has_exercise":true,
              "theory":"t","diagram":"d","task":"tugas","hint":"petunjuk","requires":["gdb"],"quiz_count":2,
              "started":false,"exercise_dir":null,"answer_viewed":false,"attempts":0}""",
        ).lessonDetail()!!
        assertEquals("tugas", detail.task)
        assertEquals(listOf("gdb"), detail.requires)
        assertNull(detail.exerciseDir)
        assertFalse(detail.started)
        val started = parse("""{"n":4,"theory":"t","diagram":"","task":"x","hint":"h","started":true,"exercise_dir":"/root/.rea/learn/lesson04"}""").lessonDetail()!!
        assertEquals("/root/.rea/learn/lesson04", started.exerciseDir)
    }

    @Test
    fun parsesQuizQuestionsAndTheGrade() {
        val questions = parse("""{"n":1,"title":"x","questions":[{"text":"Q1","options":["a","b","c"]},{"text":"Q2","options":["x","y"]}]}""").quizQuestions()
        assertEquals(listOf(3, 2), questions.map { it.options.size })
        assertEquals("Q1", questions[0].text)
        val grade = parse("""{"n":1,"score":1,"total":2,"correct":[true,false],"passed":true}""").quizResult()!!
        assertEquals(1, grade.score)
        assertTrue(grade.passed)
        assertNull(parse("""{"questions":[]}""").quizResult())
    }

    @Test
    fun parsesAnAnswerCheckWithAndWithoutAHint() {
        val wrong = parse("""{"n":4,"correct":false,"attempts":1,"message":"Belum tepat.","hint":"Lihat daftar fungsi"}""").checkResult()!!
        assertFalse(wrong.correct)
        assertEquals("Lihat daftar fungsi", wrong.hint)
        val right = parse("""{"n":4,"correct":true,"attempts":2,"message":"Benar.","hint":null}""").checkResult()!!
        assertTrue(right.correct)
        assertNull(right.hint)
    }

    @Test
    fun revealsTheAnswersAsOneLine() {
        assertEquals("35 / 0x23", parse("""{"n":9,"answers":["35","0x23"],"explanation":"e"}""").revealedAnswer())
        assertNull(parse("""{"n":9,"answers":[]}""").revealedAnswer())
    }

    @Test
    fun otherCommandsYieldNoLessonData() {
        val doctor = parse("""{"checks":[]}""", command = "doctor")
        assertTrue(doctor.lessons().isEmpty())
        assertNull(doctor.lessonDetail())
        assertNull(doctor.checkResult())
    }

    @Test
    fun aFailedLearnCommandCarriesTheHint() {
        val failed = ReaJson.parse(
            "noise\n" + """{"schema":1,"command":"learn","ok":false,"data":{},"warnings":[],"error":{"code":"usage","message":"Pelajaran 99 tidak ada.","hint":"Pilih 1 sampai 13"}}""",
        )!!
        assertFalse(failed.ok)
        assertEquals("Pilih 1 sampai 13", failed.error?.hint)
    }
}
