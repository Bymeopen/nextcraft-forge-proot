@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ReaState
import com.nextcraft.forge.runtime.ReaController
import com.nextcraft.forge.runtime.ReaAnalysis
import com.nextcraft.forge.runtime.ReaCheck

private const val MAX_FUNCTION_ROWS = 100
private const val MAX_STRING_ROWS = 200

/** REA (Reverse Engine Anything): install it, check the environment, and analyse files of the open project. */
@Composable
fun ReaScreen(state: ForgeUiState, viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val rea by viewModel.rea.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.rea.refresh() }
    val installed = rea.installedVersion != null
    val project = state.activeProject
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var withStrings by rememberSaveable { mutableStateOf(true) }
    var onlyInteresting by rememberSaveable { mutableStateOf(true) }
    var symbol by rememberSaveable { mutableStateOf("main") }
    val clipboard = LocalClipboardManager.current

    ToolScaffold(
        title = stringResource(R.string.tools_rea),
        subtitle = stringResource(R.string.tools_rea_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        ToolCard {
            Text(stringResource(R.string.rea_notice), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
        }
        ToolSectionLabel(stringResource(R.string.rea_section_install))
        ToolCard {
            KeyValueRow(
                stringResource(R.string.rea_status),
                rea.installedVersion?.let { stringResource(R.string.rea_installed_version, it) } ?: stringResource(R.string.rea_not_installed),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ForgePrimaryButton(
                    text = stringResource(if (installed) R.string.rea_reinstall else R.string.rea_install),
                    onClick = viewModel.rea::install,
                    enabled = !rea.busy,
                    modifier = Modifier.weight(1f),
                )
                if (installed) {
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel.rea::uninstall,
                        enabled = !rea.busy,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.rea_remove)) }
                }
            }
            if (rea.busy) {
                OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.rea::cancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.tool_stop))
                }
            }
            rea.message?.let { Text(it.asString(), fontSize = ForgeType.secondary, color = tokens.accentText) }
            ReaInstallProgress(rea)
            LogBox(text = rea.log, placeholder = stringResource(R.string.rea_log_placeholder))
        }

        ToolSectionLabel(stringResource(R.string.rea_section_analysis))
        ToolCard {
            if (project == null) {
                Text(stringResource(R.string.rea_open_project), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            } else {
                Text(project.name, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = {
                        viewModel.refreshWorkspaceFiles()
                        pickerOpen = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(rea.target ?: stringResource(R.string.rea_pick_file)) }
                ReaCheckboxRow(stringResource(R.string.rea_opt_strings), withStrings) { withStrings = it }
                if (withStrings) ReaCheckboxRow(stringResource(R.string.rea_opt_interesting), onlyInteresting) { onlyInteresting = it }
                ForgeTextField(
                    value = symbol,
                    onValueChange = { symbol = it.trim() },
                    kind = ForgeFieldKind.IDENTIFIER,
                    label = { Text(stringResource(R.string.rea_opt_symbol)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgePrimaryButton(
                    text = stringResource(R.string.rea_analyze),
                    onClick = {
                        rea.target?.let { path ->
                            viewModel.rea.analyze(project, path, withStrings, onlyInteresting, symbol.takeIf { it.isNotEmpty() })
                        }
                    },
                    enabled = installed && !rea.busy && rea.target != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!installed) Text(stringResource(R.string.rea_install_first), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            }
        }

        rea.analysis?.let { analysis ->
            ReaAnalysisCards(
                analysis = analysis,
                onDisassemble = { name -> project?.let { p -> rea.target?.let { viewModel.rea.disassemble(p, it, name) } } },
            )
            ToolCard {
                ForgePrimaryButton(
                    text = stringResource(R.string.rea_save_report),
                    onClick = { project?.let { p -> rea.target?.let { viewModel.rea.report(p, it) } } },
                    enabled = !rea.busy && project != null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        rea.reportMarkdown?.let { markdown ->
            ToolSectionLabel(stringResource(R.string.rea_section_report))
            ToolCard {
                rea.reportPath?.let { Text(it, fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant) }
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { clipboard.setText(AnnotatedString(markdown)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.tool_copy)) }
                MarkdownText(markdown = markdown, modifier = Modifier.fillMaxWidth())
            }
        }

        ToolSectionLabel(stringResource(R.string.rea_section_doctor))
        ToolCard {
            ForgePrimaryButton(
                text = stringResource(R.string.rea_run_doctor),
                onClick = viewModel.rea::runDoctor,
                enabled = installed && !rea.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                shape = RoundedCornerShape(100),
                onClick = viewModel.rea::installOptionalTools,
                enabled = installed && !rea.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.rea_install_optional)) }
            Text(stringResource(R.string.rea_install_optional_hint), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            rea.mode?.let { mode ->
                Text(
                    text = stringResource(R.string.rea_mode, reaModeLabel(mode)),
                    fontSize = ForgeType.control,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accentText,
                )
            }
            if (rea.checks.isEmpty() && rea.mode == null) {
                Text(stringResource(R.string.rea_doctor_hint), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            }
            rea.checks.forEach { check -> ReaCheckRow(check) }
        }

        ToolSectionLabel(stringResource(R.string.rea_section_learn))
        ToolCard {
            val done = rea.lessons.count { it.exerciseDone }
            if (rea.lessons.isEmpty()) {
                Text(stringResource(R.string.rea_learn_hint), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            } else {
                Text(
                    stringResource(R.string.rea_learn_progress, done, rea.lessons.size),
                    fontSize = ForgeType.control,
                    fontWeight = FontWeight.SemiBold,
                    color = tokens.accentText,
                )
            }
            ForgePrimaryButton(
                text = stringResource(if (rea.lessons.isEmpty()) R.string.rea_learn_open else R.string.rea_learn_refresh),
                onClick = viewModel.rea::loadLessons,
                enabled = installed && !rea.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            var lastStage = 0
            rea.lessons.forEach { lesson ->
                if (lesson.stage != lastStage) {
                    lastStage = lesson.stage
                    Text(
                        stringResource(R.string.rea_learn_stage, lesson.stage, lesson.stageName),
                        fontSize = ForgeType.caption,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !rea.busy, role = Role.Button) { viewModel.rea.openLesson(lesson.n) },
                ) {
                    Text(
                        text = numbered(lesson.n, lesson.title),
                        fontSize = ForgeType.rowTitle,
                        modifier = Modifier.weight(1f),
                    )
                    val quiz = if (lesson.quizBest != null) " ${lesson.quizBest}/${lesson.quizTotal}" else ""
                    Text(
                        text = (if (lesson.exerciseDone) "✅" else "") + quiz,
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (rea.lesson != null) {
        ReaLessonSheet(rea = rea, controller = viewModel.rea, onDismiss = viewModel.rea::closeLesson)
    }

    if (pickerOpen) {
        ReaFilePicker(
            files = state.workspaceFiles.filter { !it.isDirectory && !it.path.startsWith(".rea/") && !it.path.startsWith(".forge/") },
            onPick = { path ->
                pickerOpen = false
                viewModel.rea.selectTarget(path)
            },
            onDismiss = { pickerOpen = false },
        )
    }
}

@Composable
private fun ReaCheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, fontSize = ForgeType.body, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun ReaAnalysisCards(analysis: ReaAnalysis, onDisassemble: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val id = analysis.identity
    ToolSectionLabel(stringResource(R.string.rea_section_identity))
    ToolCard {
        KeyValueRow(stringResource(R.string.rea_k_type), listOfNotNull(id.type, id.arch, id.bits?.let { "$it-bit" }).joinToString(" · "))
        KeyValueRow(stringResource(R.string.rea_k_size), "${id.size} B")
        KeyValueRow(stringResource(R.string.rea_k_entropy), "%.2f".format(java.util.Locale.ROOT, id.entropy))
        KeyValueRow("SHA-256", id.sha256.take(16) + "…")
        if (id.packedHint) Text(stringResource(R.string.rea_packed_hint), fontSize = ForgeType.secondary, color = tokens.accentText)
    }
    analysis.elf?.let { elf ->
        ToolSectionLabel(stringResource(R.string.rea_section_elf))
        ToolCard {
            KeyValueRow(stringResource(R.string.rea_k_type), "${elf.type} · ${elf.machine}")
            KeyValueRow(stringResource(R.string.rea_k_entry), "0x" + elf.entry.toString(16))
            KeyValueRow(stringResource(R.string.rea_k_symbols), stringResource(if (elf.stripped) R.string.rea_stripped else R.string.rea_has_symbols))
            KeyValueRow(stringResource(R.string.rea_k_layout), stringResource(R.string.rea_layout, elf.sectionCount, elf.segmentCount))
            if (elf.needed.isNotEmpty()) KeyValueRow(stringResource(R.string.rea_k_libs), elf.needed.joinToString(", "))
        }
        if (elf.functions.isNotEmpty()) {
            ToolSectionLabel(stringResource(R.string.rea_section_functions, elf.functions.size))
            ToolCard {
                Text(stringResource(R.string.rea_functions_hint), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
                elf.functions.take(MAX_FUNCTION_ROWS).forEach { fn ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onDisassemble(fn.name) }.padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(fn.name, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("0x" + fn.addr.toString(16), fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (analysis.strings.isNotEmpty()) {
        ToolSectionLabel(stringResource(R.string.rea_section_strings, analysis.strings.size, analysis.stringsTotal))
        ToolCard {
            analysis.strings.take(MAX_STRING_ROWS).forEach { s ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("%x".format(java.util.Locale.ROOT, s.offset), fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                    Text(s.score.toString(), fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = tokens.accentText)
                    Text(s.text, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    analysis.disasm?.let { code ->
        ToolSectionLabel(stringResource(R.string.rea_section_disasm, code.function ?: "?", "0x" + code.address.toString(16)))
        ToolCard {
            LogBox(
                text = code.instructions.joinToString("\n") { "%x:  %-18s %s %s".format(java.util.Locale.ROOT, it.addr, it.bytes, it.mnemonic, it.operands).trimEnd() },
                placeholder = "",
            )
        }
    }
}

/** "3. Title": a list number in front of text that already comes from the lesson data. */
private fun numbered(n: Int, text: String): String = "$n. $text"

/** One lesson: theory, diagram, the exercise with an answer check, the quiz, and the answer key behind a confirmation. */
@Composable
private fun ReaLessonSheet(rea: ReaState, controller: ReaController, onDismiss: () -> Unit) {
    val lesson = rea.lesson ?: return
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    var answer by rememberSaveable(lesson.n) { mutableStateOf("") }
    var hintShown by rememberSaveable(lesson.n) { mutableStateOf(false) }
    var confirmReveal by remember(lesson.n) { mutableStateOf(false) }
    // One 1-based pick per question (0 = not answered yet); reset whenever a new quiz is loaded.
    val picks = remember(lesson.n, rea.quiz) { mutableStateListOf<Int>().apply { repeat(rea.quiz?.size ?: 0) { add(0) } } }
    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForgeSpace.card)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.rea_learn_stage, lesson.n, lesson.stageName), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
            Text(lesson.title, fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
            Text(lesson.theory, fontSize = ForgeType.control)
            Text(lesson.diagram, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace)

            Text(stringResource(R.string.rea_lesson_task), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
            Text(lesson.task, fontSize = ForgeType.control)
            if (lesson.requires.isNotEmpty()) {
                Text(stringResource(R.string.rea_lesson_requires, lesson.requires.joinToString(", ")), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
            }
            if (lesson.hasExercise) {
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { controller.startExercise(lesson.n) },
                    enabled = !rea.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(if (lesson.started) R.string.rea_lesson_prepare_again else R.string.rea_lesson_prepare)) }
                lesson.exerciseDir?.let {
                    Text(stringResource(R.string.rea_lesson_folder, it), fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                }
            }
            if (hintShown) {
                Text(lesson.hint, fontSize = ForgeType.secondary, color = tokens.accentText)
            } else {
                ForgeTextButton(onClick = { hintShown = true }) { Text(stringResource(R.string.rea_lesson_show_hint)) }
            }
            ForgeTextField(
                value = answer,
                onValueChange = { answer = it.take(300) },
                kind = ForgeFieldKind.IDENTIFIER,
                label = { Text(stringResource(R.string.rea_lesson_answer_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.rea_lesson_check),
                onClick = { controller.checkAnswer(lesson.n, answer) },
                enabled = answer.isNotBlank() && !rea.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            rea.checkResult?.let { result ->
                Text(
                    (if (result.correct) "✅ " else "❌ ") + result.message,
                    fontSize = ForgeType.control,
                    fontWeight = FontWeight.SemiBold,
                    color = if (result.correct) tokens.accentText else scheme.error,
                )
            }
            if (lesson.exerciseDone) Text(stringResource(R.string.rea_lesson_done), fontSize = ForgeType.secondary, color = tokens.accentText)

            Text(stringResource(R.string.rea_lesson_quiz), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            val quiz = rea.quiz
            if (quiz == null) {
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { controller.loadQuiz(lesson.n) },
                    enabled = !rea.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.rea_lesson_quiz_start)) }
            } else {
                quiz.forEachIndexed { qi, question ->
                    Text(numbered(qi + 1, question.text), fontSize = ForgeType.control, fontWeight = FontWeight.Medium)
                    question.options.forEachIndexed { oi, option ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable(role = Role.RadioButton) { if (qi < picks.size) picks[qi] = oi + 1 }
                                .forgeSelected(qi < picks.size && picks[qi] == oi + 1),
                        ) {
                            RadioButton(selected = qi < picks.size && picks[qi] == oi + 1, onClick = null)
                            Text(option, fontSize = ForgeType.control, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
                ForgePrimaryButton(
                    text = stringResource(R.string.rea_lesson_quiz_grade),
                    onClick = { controller.gradeQuiz(lesson.n, picks.toList()) },
                    enabled = picks.isNotEmpty() && picks.all { it > 0 } && !rea.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                rea.quizResult?.let { result ->
                    Text(
                        stringResource(R.string.rea_lesson_quiz_score, result.score, result.total) + " · " +
                            stringResource(if (result.passed) R.string.rea_lesson_quiz_passed else R.string.rea_lesson_quiz_retry),
                        fontSize = ForgeType.control,
                        fontWeight = FontWeight.SemiBold,
                        color = if (result.passed) tokens.accentText else scheme.error,
                    )
                }
            }

            rea.revealedAnswer?.let { Text(stringResource(R.string.rea_lesson_answer_is, it), fontSize = ForgeType.control, fontWeight = FontWeight.SemiBold) }
            ForgeTextButton(onClick = { confirmReveal = true }, enabled = !rea.busy) { Text(stringResource(R.string.rea_lesson_reveal)) }
        }
    }
    if (confirmReveal) {
        ForgeAlertDialog(
            onDismissRequest = { confirmReveal = false },
            title = { Text(stringResource(R.string.rea_lesson_reveal_title)) },
            text = { Text(stringResource(R.string.rea_lesson_reveal_message)) },
            confirmButton = {
                ForgeTextButton(onClick = { confirmReveal = false; controller.revealAnswer(lesson.n) }) { Text(stringResource(R.string.rea_lesson_reveal)) }
            },
            dismissButton = { ForgeTextButton(onClick = { confirmReveal = false }) { Text(stringResource(R.string.projects_cancel)) } },
        )
    }
}

@Composable
private fun ReaFilePicker(files: List<com.nextcraft.forge.model.WorkspaceEntry>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val tokens = LocalForgeThemeTokens.current
    var query by rememberSaveable { mutableStateOf("") }
    val shown = files.filter { query.isBlank() || it.path.contains(query, ignoreCase = true) }
    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = ForgeSpace.card).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.rea_pick_file), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
            ForgeTextField(
                value = query,
                onValueChange = { query = it },
                kind = ForgeFieldKind.IDENTIFIER,
                placeholder = { Text(stringResource(R.string.model_picker_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (shown.isEmpty()) Text(stringResource(R.string.rea_no_files), fontSize = ForgeType.secondary)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(shown.take(300), key = { it.path }) { file ->
                    Column(modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(file.path) }.padding(vertical = 8.dp)) {
                        Text(file.path, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${file.sizeBytes} B", fontSize = ForgeType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun reaModeLabel(mode: String): String = stringResource(
    when (mode) {
        "full-dynamic" -> R.string.rea_mode_full
        "emulated-dynamic" -> R.string.rea_mode_emulated
        else -> R.string.rea_mode_static
    },
)

@Composable
private fun ReaCheckRow(check: ReaCheck) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val symbol = when (check.status) {
        "ok" -> "\u2705"
        "warn" -> "\u26A0\uFE0F"
        else -> "\u274C"
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(symbol, fontSize = ForgeType.body)
        Column(modifier = Modifier.weight(1f)) {
            Text(check.label, fontSize = ForgeType.body, fontWeight = FontWeight.Medium)
            if (check.detail.isNotBlank()) {
                Text(check.detail, fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
            }
            if (check.hint.isNotBlank() && check.status != "ok") {
                Text(check.hint, fontSize = ForgeType.secondary, color = tokens.accentText)
            }
        }
    }
}

/** Percent of the install steps, the step running, MB received since the task began and the current MB/s. */
@Composable
private fun ReaInstallProgress(rea: ReaState) {
    val transfer = rea.transfer ?: return
    val percent = rea.progressPercent
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        if (percent != null) {
            androidx.compose.material3.LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
        } else if (rea.busy) {
            androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = stringResource(R.string.rea_progress_line, percent ?: 0, rea.progressLabel.orEmpty()),
            fontSize = ForgeType.secondary,
            color = scheme.onSurface,
        )
        Text(
            text = stringResource(R.string.rea_progress_speed, transfer.bytes / 1_048_576.0, transfer.bytesPerSecond / 1_048_576.0),
            fontSize = ForgeType.secondary,
            color = scheme.onSurfaceVariant,
        )
    }
}
