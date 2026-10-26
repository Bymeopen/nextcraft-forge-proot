package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NextCraft Screenshot: opt-in (-PforgeScreenshots) renders of real composables at the phone sizes the app targets, written to
 * app/build/screenshots. Each scenario is drawn in Indonesian, whose labels are the longest.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// A bare Application: ForgeApplication.onCreate schedules WorkManager, which is not initialised under Robolectric.
@Config(application = android.app.Application::class)
abstract class NextCraftScreenshots(private val label: String, private val theme: String = "dark") {
    @get:Rule val rule = androidx.compose.ui.test.junit4.createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun save(scenario: String) {
        rule.waitForIdle()
        // captureToImage times out under Robolectric (forced redraw never completes), so draw the window directly.
        val view = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$scenario-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        ScreenshotBaselines.handle("$scenario-$label.png", label, bitmap)
    }

    @Composable
    private fun Frame(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
        NextCraftForgeTheme(theme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.height(32.dp)) // status bar
                    content()
                }
            }
        }
    }

    @Test
    fun toolsWithGlobalBar() {
        rule.setContent {
            Frame {
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) { ToolsScreen(onOpen = {}) }
                ForgeBottomBar(navEntries, ForgeTab.TOOLS, changedCount = 4, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("tools")
    }

    @Test
    fun projectBar() {
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "Android · 3 obrolan", onBack = {}, actions = {
                    ForgeGlyphPillIcon(ForgeGlyph.SEARCH, "search", onClick = {})
                    ForgeGlyphPillIcon(ForgeGlyph.BUILD, "tools", onClick = {})
                })
                ForgeListRow(ForgeGlyph.SPARK, "Perbaiki error build di Launch", onClick = {}, subtitle = "2 jam lalu")
                ForgeListRow(ForgeGlyph.SPARK, "Obrolan utama", onClick = {}, subtitle = "kemarin", badge = "3 diubah")
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {}
                ForgeBottomBar(projectEntries, ForgeTab.AGENT, changedCount = 4, agentRunning = true, terminalBusy = false, onSelectTab = {})
            }
        }
        save("projectbar")
    }
    @Test
    fun projects() {
        val now = System.currentTimeMillis()
        rule.setContent {
            Frame {
                ForgeLargeTitle(title = "Bangun dari ponsel Anda", subtitle = "Ngobrol, tinjau perubahan, dan pratinjau proyek Anda.")
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    ForgePrimaryButton("Proyek cepat", onClick = {}, modifier = Modifier.weight(1f))
                    androidx.compose.material3.OutlinedButton(onClick = {}, modifier = Modifier.weight(1f)) { androidx.compose.material3.Text("+ Proyek baru") }
                }
                ForgeSectionLabel("Proyek Anda")
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    ProjectRow(
                        project = com.nextcraft.forge.model.Project(name = "Aplikasi Batrei", description = "General coding workspace", language = "Multibahasa", slug = "aplikasi-batrei", updatedAtMillis = now - 120_000),
                        active = true, locked = false, canDelete = true, onOpen = {}, onDelete = {}, onRename = {},
                    )
                    ProjectRow(
                        project = com.nextcraft.forge.model.Project(name = "Forge Sandbox", description = "Ruang kerja bawaan di dalam Ubuntu ARM64 Root Chroot", language = "Kotlin / TypeScript / Python", slug = "forge-sandbox", updatedAtMillis = now - 39 * 60_000),
                        active = false, locked = false, canDelete = true, onOpen = {}, onDelete = {}, onRename = {},
                    )
                }
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {}
                ForgeBottomBar(navEntries, ForgeTab.PROJECTS, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("projects")
    }

    @Test
    fun chat() {
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "Claude Code · Tets", onBack = {}, actions = {
                    ForgeGlyphPillIcon(ForgeGlyph.HISTORY, "history", onClick = {})
                })
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.weight(1f).padding(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    ChatMessageCard(com.nextcraft.forge.model.ChatMessage(fromUser = true, text = "Tambahkan tombol hapus di daftar tugas lalu jalankan semua tes"), "Claude Code") {}
                    ChatMessageCard(
                        com.nextcraft.forge.model.ChatMessage(
                            fromUser = false,
                            text = "Selesai. Tombol hapus ditambahkan di `TodoList.kt` dan semua 12 tes lulus. Jalankan `./gradlew testDebugUnitTest` untuk memeriksa ulang di perangkat Anda.",
                            workedMillis = 102_000,
                        ),
                        "Claude Code",
                    ) {}
                    ChatMessageCard(com.nextcraft.forge.model.ChatMessage(fromUser = true, text = "Ok"), "Claude Code") {}
                }
                ForgeBottomBar(projectEntries, ForgeTab.AGENT, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("chat")
    }

    @Test
    fun settings() {
        rule.setContent {
            Frame {
                ForgeLargeTitle(title = "Pengaturan", subtitle = "Preferensi & konfigurasi")
                ForgeSectionLabel("Umum")
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                    DisclosureCard(
                        icon = ForgeGlyph.SLIDERS, title = "Tampilan", subtitle = "Linear (Gelap)", expanded = false, onToggle = {},
                    ) {}
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(10.dp))
                    ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                        ForgeToggleRow(
                            title = "Persetujuan perintah berisiko",
                            subtitle = "Tanya sebelum menjalankan perintah shell atau sistem yang merusak",
                            checked = false, onCheckedChange = {},
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                        ForgeToggleRow(
                            title = "Setujui semua alat otomatis",
                            subtitle = "Lewati konfirmasi untuk semua eksekusi alat",
                            checked = true, onCheckedChange = {},
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                }
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {}
                ForgeBottomBar(navEntries, ForgeTab.SETTINGS, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("settings")
    }
    @Test
    fun chatBatchB() {
        val items = listOf(
            com.nextcraft.forge.model.ActivityItem("Think", "Perlu menambah tombol hapus di TodoList", true),
            com.nextcraft.forge.model.ActivityItem("Bash", "git status --short", true, isCommand = true),
            com.nextcraft.forge.model.ActivityItem("Read", "app/src/main/java/TodoList.kt", true),
            com.nextcraft.forge.model.ActivityItem("Write", "app/src/main/java/TodoList.kt", true),
            com.nextcraft.forge.model.ActivityItem("Bash", "./gradlew testDebugUnitTest", false, isCommand = true),
        )
        val diff = listOf(
            com.nextcraft.forge.model.DiffLine(com.nextcraft.forge.model.DiffLineType.CONTEXT, "fun TodoRow(todo: Todo) {", 10, 10),
            com.nextcraft.forge.model.DiffLine(com.nextcraft.forge.model.DiffLineType.DELETION, "    Text(todo.title)", 11, null),
            com.nextcraft.forge.model.DiffLine(com.nextcraft.forge.model.DiffLineType.ADDITION, "    Text(todo.title, modifier = Modifier.weight(1f))", null, 11),
            com.nextcraft.forge.model.DiffLine(com.nextcraft.forge.model.DiffLineType.ADDITION, "    IconButton(onClick = { onDelete(todo) }) {", null, 12),
            com.nextcraft.forge.model.DiffLine(com.nextcraft.forge.model.DiffLineType.ADDITION, "        Icon(Icons.Outlined.Delete, null)", null, 13),
            com.nextcraft.forge.model.DiffLine(com.nextcraft.forge.model.DiffLineType.ADDITION, "    }", null, 14),
            com.nextcraft.forge.model.DiffLine(com.nextcraft.forge.model.DiffLineType.CONTEXT, "}", 12, 15),
        )
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "Claude Code · Tambah tombol hapus", onBack = {}, actions = {
                    ForgeGlyphPillIcon(ForgeGlyph.HISTORY, "history", onClick = {})
                })
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.weight(1f).padding(12.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                ) {
                    ChatMessageCard(com.nextcraft.forge.model.ChatMessage(fromUser = true, text = "Tambahkan tombol hapus di daftar tugas lalu jalankan semua tes"), "Claude Code") {}
                    ToolStepGroup(items)
                    ChatMessageCard(
                        com.nextcraft.forge.model.ChatMessage(fromUser = false, text = "Tombol hapus sudah ditambahkan. Tes sedang berjalan.", workedMillis = 102_000),
                        "Claude Code",
                    ) {}
                    ChangedFilesCard(
                        changes = listOf(
                            com.nextcraft.forge.model.ChangeItem("app/src/main/java/com/example/todo/TodoList.kt", 4, 1, diff),
                            com.nextcraft.forge.model.ChangeItem("app/src/main/java/com/example/todo/TodoItem.kt", 2, 0, emptyList()),
                        ),
                        enabled = true, onOpenFile = {}, onKeepFile = {}, onRevertFile = {}, onKeepAll = {}, onRevertAll = {},
                    )
                }
                QuickActionRow(listOf(
                    QuickChip("Tinjau 2 perubahan", ForgeGlyph.FILE) {},
                    QuickChip("Jelaskan perubahan", ForgeGlyph.SPARK) {},
                    QuickChip("Jalankan tes", ForgeGlyph.CODE) {},
                ))
                ForgeBottomBar(projectEntries, ForgeTab.AGENT, changedCount = 2, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        rule.onAllNodesWithText("TodoList.kt")[0].performClick()
        save("chatB")
    }

    @Test
    fun approval() {
        rule.setContent {
            Frame {
                // Text behind the sheet: a see-through sheet shows it mixed into the sheet's own text (the Glass bug).
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(16.dp)) {
                        repeat(18) { Text("Daftar pesan error mentah $it: bash: /nextcraft-bridge/../opt/pocket", fontSize = 13.sp) }
                    }
                }
                androidx.compose.material3.Surface(color = forgeSheetColor(), shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), modifier = Modifier.offset(y = (-360).dp)) {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(top = 20.dp)) {
                        ToolApprovalContent(
                            request = com.nextcraft.forge.model.ToolRequest(
                                sessionId = "s", toolName = "Bash",
                                explanation = "Menghapus folder build dan membangun ulang seluruh proyek dari awal.",
                                affectedPaths = listOf("app/build", "app/src/main/java/com/example/todo/TodoList.kt"),
                                commandPreview = "rm -rf app/build && ./gradlew clean assembleDebug",
                                risk = com.nextcraft.forge.model.RiskLevel.HIGH,
                            ),
                            onApprove = {}, onReject = {},
                        )
                    }
                }
            }
        }
        save("approval")
    }

    /**
     * Diagnostics-style tool screen with an EMPTY log box. The empty LogBox once had no upper height bound inside a
     * vertically scrolling screen, which crashed Diagnostics, Build Matrix, SSH and ROM Studio: if that returns,
     * this scenario fails to render instead of producing a picture.
     */
    @Test
    fun diagnosticsEmptyLog() {
        rule.setContent {
            Frame {
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                    ToolScaffold(title = "Diagnostik Sistem", subtitle = "Pantau CPU, RAM, baterai, dan runtime Linux", onBack = {}) {
                        ToolCard {
                            KeyValueRow("Perangkat", "Xiaomi 21121210G")
                            KeyValueRow("RAM", "6,0 GB kosong dari 10,9 GB")
                            KeyValueRow("Stack", "Web (JavaScript / TypeScript), Python, Android (Gradle), C/C++, PHP")
                        }
                        LogBox(text = "", placeholder = "Jalankan pemeriksaan kesehatan untuk menguji mount, jaringan, Node, agen, dan toolchain di chroot.")
                    }
                }
                ForgeBottomBar(navEntries, ForgeTab.TOOLS, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("diagnostics")
    }

    /** Syntax-coloured diff with line numbers (chat change review, Files > Changes and the Tools code viewer). */
    @Test
    fun codeViewerDiff() {
        fun line(kind: com.nextcraft.forge.model.DiffLineType, text: String, old: Int?, new: Int?) =
            com.nextcraft.forge.model.DiffLine(kind, text, old, new)
        val context = com.nextcraft.forge.model.DiffLineType.CONTEXT
        val added = com.nextcraft.forge.model.DiffLineType.ADDITION
        val removed = com.nextcraft.forge.model.DiffLineType.DELETION
        val lines = listOf(
            line(context, "fun TodoRow(todo: Todo) {", 10, 10),
            line(removed, "    Text(todo.title) // plain", 11, null),
            line(added, "    Text(todo.title, maxLines = 1) // truncate \"long\" titles", null, 11),
            line(added, "    val done = todo.done || false", null, 12),
            line(context, "}", 12, 13),
        )
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Pembaca kode", subtitle = "Aplikasi Batrei", onBack = {})
                androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f).padding(14.dp)) {
                    DiffCodeView(lines = lines, path = "TodoRow.kt")
                }
                ForgeBottomBar(navEntries, ForgeTab.TOOLS, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("code-viewer")
    }
    @Test
    fun tabsAndStatus() {
        val now = System.currentTimeMillis()
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "File", onBack = {})
                ForgePillTabs(items = listOf("Explorer (4)", "nextcraft-forge-dmesg.txt", "Perubahan", "Riwayat"), selectedIndex = 1, onSelect = {})
                androidx.compose.foundation.layout.Column(modifier = Modifier.padding(horizontal = 14.dp).weight(1f)) {
                    ProjectRow(
                        project = com.nextcraft.forge.model.Project(name = "Aplikasi Batrei", description = "General coding workspace", language = "Multibahasa", slug = "aplikasi-batrei", updatedAtMillis = now - 120_000),
                        active = true, locked = false, canDelete = true, onOpen = {}, onDelete = {}, onRename = {}, changedCount = 3, running = true,
                    )
                }
                ForgeBottomBar(navEntries, ForgeTab.PROJECTS, changedCount = 3, agentRunning = true, terminalBusy = false, onSelectTab = {})
            }
        }
        save("tabsStatus")
    }
    @Test
    fun composerStates() {
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "Claude Code", onBack = {}, actions = {
                    ForgeGlyphPillIcon(ForgeGlyph.HISTORY, "history", onClick = {})
                })
                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {}
                QuickActionRow(listOf(
                    QuickChip("Jelaskan struktur proyek ini", ForgeGlyph.SPARK) {},
                    QuickChip("Cari dan perbaiki error build", ForgeGlyph.BUILD) {},
                    QuickChip("Tambahkan fitur baru: ", ForgeGlyph.PLUS) {},
                ))
                ChatComposer("", {}, "Claude Code", running = false, canSend = false, captureEnabled = true, onAttachCamera = {}, onAttachFile = {}, onCapture = {}, onSend = {}, onStop = {})
                ChatComposer("Tambahkan tombol hapus di daftar tugas lalu jalankan semua tes sekarang juga", {}, "Claude Code", running = false, canSend = true, captureEnabled = true, onAttachCamera = {}, onAttachFile = {}, onCapture = {}, onSend = {}, onStop = {})
                ChatComposer("", {}, "Claude Code", running = true, canSend = false, captureEnabled = true, onAttachCamera = {}, onAttachFile = {}, onCapture = {}, onSend = {}, onStop = {})
                ForgeBottomBar(projectEntries, ForgeTab.AGENT, changedCount = 0, agentRunning = true, terminalBusy = false, onSelectTab = {})
            }
        }
        save("composer")
    }

    @Test
    fun terminalBottom() {
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "/workspace/aplikasi-batrei \u00B7 Ubuntu root chroot", onBack = {}, actions = {
                    ForgeGlyphPillIcon(ForgeGlyph.FILE, "copy", onClick = {})
                    ForgeGlyphPillIcon(ForgeGlyph.TRASH, "clear", onClick = {})
                })
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)
                        .background(LocalForgeThemeTokens.current.terminalBackground, androidx.compose.foundation.shape.RoundedCornerShape(14.dp)),
                ) {}
                TerminalKeyRow(modifiers = TerminalModifierKeys(), holder = TerminalViewHolder(), enabled = true)
                TerminalCommandBar(enabled = true, onSend = {})
                ForgeBottomBar(projectEntries, ForgeTab.TERMINAL, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("terminal")
    }

    @Test
    fun onboarding() {
        rule.setContent {
            NextCraftForgeTheme(theme) {
                RuntimeOnboardingScreen(
                    state = ForgeUiState(),
                    onToggleStack = {}, onStartSetup = {}, onPauseSetup = {}, onRepairRootfs = {}, onRefreshStatus = {}, onEnterWorkspaceAnyway = {},
                )
            }
        }
        save("onboarding")
    }

    private fun fmEntries(): List<com.nextcraft.forge.model.WorkspaceEntry> {
        fun d(path: String, n: Int) = com.nextcraft.forge.model.WorkspaceEntry(path, path.substringAfterLast('/'), true, 0, childCount = n, modifiedMillis = System.currentTimeMillis() - 3_600_000)
        fun f(path: String, size: Long) = com.nextcraft.forge.model.WorkspaceEntry(path, path.substringAfterLast('/'), false, 0, size, modifiedMillis = System.currentTimeMillis() - 86_400_000)
        return listOf(d("app/src/main", 4), d("app/src/test", 2), f("app/src/Main.kt", 2048), f("app/src/util-strings-and-formatting-helpers.kt", 18_400), f("app/build.gradle.kts", 1_200_000))
    }

    @Test
    fun filesManager() {
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "File", onBack = {})
                FilesHeaderCard(
                    title = "5 item", selecting = false, selectedCount = 0, enabled = true, sort = FileSort.NAME, showHidden = false,
                    onSort = {}, onToggleHidden = {}, onNewFile = {}, onNewFolder = {}, onUpload = {}, onToggleSelect = {}, onRefresh = {},
                )
                FileBreadcrumbs(rootName = "Aplikasi Batrei", dir = "app/src", onNavigate = {})
                WorkspaceFolderList(
                    entries = fmEntries(), loading = false, atRoot = false, truncated = false, selectedPath = null, selecting = false,
                    selectedPaths = mutableListOf(), enabled = true, onOpenFolder = {}, onSelectFile = {}, onNewFileIn = {}, onNewFolderIn = {},
                    onUploadIn = {}, onRename = {}, onSaveCopy = {}, onEditInTerminal = {}, onAnalyzeWithRea = {}, onInstallApk = {}, onDelete = {},
                    modifier = Modifier.weight(1f),
                )
                ForgeBottomBar(projectEntries, ForgeTab.FILES, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("filesManager")
    }

    @Test
    fun filesSearch() {
        val all = fmEntries()
        val result = searchWorkspaceEntries(all, "main")
        rule.setContent {
            Frame {
                ForgeProjectHeader(title = "Aplikasi Batrei", subtitle = "File", onBack = {})
                FilesHeaderCard(
                    title = "5 item", selecting = false, selectedCount = 0, enabled = true, sort = FileSort.NAME, showHidden = false,
                    onSort = {}, onToggleHidden = {}, onNewFile = {}, onNewFolder = {}, onUpload = {}, onToggleSelect = {}, onRefresh = {},
                    searching = true,
                )
                FileSearchBar(query = "main", onQueryChange = {}, resultCount = result.matches.size)
                WorkspaceFolderList(
                    entries = result.entries.filter { it.path in result.matches }, loading = false, atRoot = false, truncated = false, selectedPath = null,
                    selecting = false, selectedPaths = mutableListOf(), enabled = true, onOpenFolder = {}, onSelectFile = {}, onNewFileIn = {},
                    onNewFolderIn = {}, onUploadIn = {}, onRename = {}, onSaveCopy = {}, onEditInTerminal = {}, onAnalyzeWithRea = {}, onInstallApk = {}, onDelete = {},
                    modifier = Modifier.weight(1f),
                )
                ForgeBottomBar(projectEntries, ForgeTab.FILES, changedCount = 0, agentRunning = false, terminalBusy = false, onSelectTab = {})
            }
        }
        save("filesSearch")
    }

    @Test
    fun layoutFixes() {
        val chats = listOf(
            com.nextcraft.forge.model.ProjectChat(title = "Test 2", updatedAtMillis = System.currentTimeMillis() - 3 * 3_600_000),
            com.nextcraft.forge.model.ProjectChat(title = "Tes", updatedAtMillis = System.currentTimeMillis() - 12 * 3_600_000),
            com.nextcraft.forge.model.ProjectChat(title = "Perbaiki error build di Launch", updatedAtMillis = System.currentTimeMillis() - 26 * 3_600_000),
        )
        rule.setContent {
            Frame {
                ForgeLargeTitle(title = "Pengaturan", subtitle = "Preferensi & konfigurasi", horizontalPadding = 20.dp)
                ForgeSectionLabel("Umum", horizontalPadding = 20.dp, topPadding = 8.dp)
                ThemeModeGrid(active = ForgeThemeMode.DARK, onSelect = {})
                Text("Kedalaman penalaran", modifier = Modifier.padding(horizontal = 20.dp), fontSize = ForgeType.secondary)
                Text("Paling dalam, jawaban lebih lambat", modifier = Modifier.padding(horizontal = 20.dp), fontSize = ForgeType.secondary, color = MaterialTheme.colorScheme.primary)
                ForgePillTabs(items = listOf("Rendah", "Sedang", "Tinggi"), selectedIndex = 2, onSelect = {}, equalWidth = true)
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    Text("Forge Sandbox", fontSize = ForgeType.screenTitle, modifier = Modifier.padding(horizontal = 8.dp))
                    ForgePrimaryButton(text = "Obrolan baru", onClick = {}, modifier = Modifier.fillMaxWidth())
                    chats.forEachIndexed { i, c -> ChatDrawerRow(chat = c, current = i == 0, running = i == 2, canDelete = true, onOpen = {}, onDelete = {}) }
                }
            }
        }
        save("layoutFixes")
    }
}

@Config(sdk = [34], qualifiers = "in-w360dp-h800dp-xxhdpi") class Shots61 : NextCraftScreenshots("6.1in-360x800")
@Config(sdk = [34], qualifiers = "in-w412dp-h923dp-xxhdpi") class Shots63 : NextCraftScreenshots("6.3in-412x923")
@Config(sdk = [34], qualifiers = "in-w393dp-h873dp-xxhdpi") class Shots667 : NextCraftScreenshots("6.67in-393x873")
@Config(sdk = [34], qualifiers = "in-w412dp-h915dp-xxhdpi") class Shots68 : NextCraftScreenshots("6.8in-412x915")
@Config(sdk = [34], qualifiers = "in-w440dp-h956dp-xxhdpi") class Shots69 : NextCraftScreenshots("6.9in-440x956")

@Config(sdk = [34], qualifiers = "in-w393dp-h873dp-xxhdpi") class ShotsWhite : NextCraftScreenshots("white-393x873", "white")
@Config(sdk = [34], qualifiers = "in-w393dp-h873dp-xxhdpi") class ShotsGlass : NextCraftScreenshots("glass-393x873", "glass")
@Config(sdk = [34], qualifiers = "in-w932dp-h430dp-land-xxhdpi") class ShotsLandscape : NextCraftScreenshots("land-932x430")
