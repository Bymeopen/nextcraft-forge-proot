package com.nextcraft.forge.ui

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.RuntimeExecutionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Opt-in (-PforgeScreenshots). Starts the real foreground service and reads the notifications it posts, so the
 * wording is checked as the user sees it, in the system language and in a different in-app language.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "in", application = android.app.Application::class)
class NotificationLanguageTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun setInAppLanguage(code: String) {
        app.getSharedPreferences("nextcraft_preferences", Context.MODE_PRIVATE).edit().putString("language_code", code).commit()
    }

    private fun send(service: RuntimeExecutionService, build: Intent.() -> Unit): Notification {
        val manager = app.getSystemService(NotificationManager::class.java)
        val before = shadowOf(manager).allNotifications.size
        service.onStartCommand(Intent(app, RuntimeExecutionService::class.java).apply(build), 0, 1)
        return shadowOf(manager).allNotifications.last().also {
            assertTrue("a notification was posted", shadowOf(manager).allNotifications.size >= before)
        }
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    @Test
    fun serviceTextFollowsSystemLanguageWhenAppLanguageIsDefault() {
        setInAppLanguage("system")
        val service = Robolectric.buildService(RuntimeExecutionService::class.java).create().get()

        val running = send(service) { action = RuntimeExecutionService.ACTION_START; putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, "demo") }
        assertEquals("Forge Runtime aktif", running.title())
        assertEquals("Bekerja di demo", running.text())

        val done = send(service) {
            action = RuntimeExecutionService.ACTION_COMPLETE
            putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, "demo")
            putExtra(RuntimeExecutionService.EXTRA_DETAIL_RES, R.string.notif_agent_finished_in)
            putExtra(RuntimeExecutionService.EXTRA_DETAIL_ARGS, arrayOf("Claude Code", "demo"))
        }
        assertEquals("Tugas selesai", done.title())
        assertEquals("Claude Code selesai di demo", done.text())

        val manager = app.getSystemService(NotificationManager::class.java)
        assertEquals("Tugas pemrograman aktif", manager.getNotificationChannel("runtime").name.toString())
        assertEquals("Hasil tugas", manager.getNotificationChannel("task-results").name.toString())
    }

    @Test
    fun serviceTextFollowsTheInAppLanguageNotTheSystemOne() {
        setInAppLanguage("en") // system locale is Indonesian (qualifiers = "in")
        val service = Robolectric.buildService(RuntimeExecutionService::class.java).create().get()
        send(service) { action = RuntimeExecutionService.ACTION_START; putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, "demo") }
        val failed = send(service) {
            action = RuntimeExecutionService.ACTION_FAILED
            putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, "demo")
        }
        assertEquals("Task needs attention", failed.title())
        assertEquals("Could not finish the active task.", failed.text())
    }

    @Test
    fun localeHelperMakesApplicationLevelTextFollowTheInAppLanguage() {
        setInAppLanguage("en")
        assertEquals("Agent", LocaleHelper.wrapContext(app).getString(R.string.agent_title))
        setInAppLanguage("in")
        assertEquals("Agen", LocaleHelper.wrapContext(app).getString(R.string.agent_title))
    }
}

/** Opt-in: known English sentences really come out in Indonesian through the same paths the screens use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "in", application = android.app.Application::class)
class MessageLanguageTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun providerAndBuildSentencesAreTranslated() {
        assertEquals("Periksa kunci API ini atau pilih kunci tersimpan lain.", MessageText.localize(app, "Check this API key or select another saved key."))
        assertEquals("Ditolak", MessageText.localize(app, "Rejected"))
        assertEquals("Build Gradle gagal (kode keluar 137).", MessageText.localize(app, "Gradle build failed (exit code 137)."))
        assertEquals("… 12 baris tidak berubah …", MessageText.localize(app, "… 12 unchanged lines …"))
        assertEquals("com.example.app dipasang dan dijalankan", MessageText.localize(app, "Installed & launched com.example.app"))
    }

    @Test
    fun rawUiTextIsTranslatedWhenItIsAKnownSentenceAndLeftAloneOtherwise() {
        assertEquals("Port harus 1-65535.", UiText.Raw("Port must be 1-65535.").asString(app))
        assertEquals("HTTP 500 from some server", UiText.Raw("HTTP 500 from some server").asString(app))
        assertEquals("kept as is", UiText.Raw("kept as is").asString(null))
    }

    @Test
    fun installerSentencesUseTheExistingRuntimeTable() {
        val (id, args) = RuntimeText.match("Installing Claude Code 2.1.0…")!!
        assertEquals("Memasang Claude Code 2.1.0…", app.getString(id, *args.toTypedArray()))
        val (id2, _) = RuntimeText.match("Preparing your private coding workspace")!!
        assertEquals("Menyiapkan ruang kerja pribadi Anda", app.getString(id2))
    }
}
