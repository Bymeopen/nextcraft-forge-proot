package com.nextcraft.forge.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every modal bottom sheet goes through ForgeModalSheet (ForgeGlass.kt): opaque container colour and, in Glass, a blurred
 * page behind it. The card colour is translucent in Glass, so a sheet painted with it lets the chat behind show through
 * the sheet's own text. Calling ModalBottomSheet directly skips both.
 */
class SheetSurfaceGuardTest {
    private val uiFiles: List<File> by lazy {
        val dir = File("src/main/java/com/nextcraft/forge/ui")
        assertTrue("UI source dir not found: ${dir.absolutePath}", dir.isDirectory)
        dir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
    }

    private fun callsOf(text: String, name: String) = Regex("""\b$name\(""").findAll(text)
        .filter { m -> !text.startsWith("import", text.lastIndexOf('\n', m.range.first) + 1) }.toList()

    @Test
    fun modalBottomSheet_isOnlyCalledByTheForgeWrapper() {
        val offenders = uiFiles.filter { it.name != "ForgeGlass.kt" }
            .flatMap { f -> callsOf(f.readText(), "ModalBottomSheet").map { "${f.name}: offset ${it.range.first}" } }
        assertTrue("call ForgeModalSheet instead of ModalBottomSheet:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun alertDialog_isOnlyCalledByTheForgeWrapper() {
        val offenders = uiFiles.filter { it.name != "ForgeGlass.kt" }
            .flatMap { f -> Regex("""(?<![\w.])AlertDialog\(""").findAll(f.readText()).map { "${f.name}: offset ${it.range.first}" } }
        assertTrue("call ForgeAlertDialog instead of AlertDialog:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun forgeWrapper_usesTheOpaqueColour_andCountsOpenSheets() {
        val src = File("src/main/java/com/nextcraft/forge/ui/ForgeGlass.kt").readText()
        val call = callsOf(src, "ModalBottomSheet").single()
        val body = src.substring(call.range.first, minOf(src.length, call.range.first + 400))
        assertTrue("wrapper must use forgeSheetColor()", "forgeSheetColor()" in body)
        assertTrue("wrapper must count open sheets so the page can blur", "LocalForgeSheetDepth" in src.substring(src.indexOf("fun ForgeModalSheet")))
    }
}
