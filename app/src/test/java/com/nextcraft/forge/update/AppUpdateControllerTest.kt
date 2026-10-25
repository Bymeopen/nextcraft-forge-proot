package com.nextcraft.forge.update

import com.nextcraft.forge.R
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The update flow with a fake source and no waiting: switches, the silent check, the manual check and the download. */
class AppUpdateControllerTest {
    private val info = AppUpdateInfo(versionName = "1.2.3", versionCode = 123, apkUrl = "u", sha256 = "", notes = "", sizeBytes = 0L)

    private class Source : AppUpdateSource {
        var result: () -> AppUpdateInfo? = { null }
        var downloadSteps: List<Pair<Long, Long>> = emptyList()
        var downloadFails: Throwable? = null
        var installFails: Throwable? = null
        val checks = mutableListOf<String>()
        var installed: File? = null
        override fun check(manifestUrlOverride: String): AppUpdateInfo? { checks += manifestUrlOverride; return result() }
        override fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File {
            downloadFails?.let { throw it }
            downloadSteps.forEach { (d, t) -> progress(d, t) }
            return File("/tmp/app.apk")
        }
        override fun install(apk: File) { installFails?.let { throw it }; installed = apk }
    }

    private class Prefs(override var notifications: Boolean = true, override var autoInstall: Boolean = true) : UpdatePrefs

    private class Rig(val source: Source = Source(), val prefs: Prefs = Prefs()) {
        var scheduled = 0
        var cancelled = 0
        val announced = mutableListOf<AppUpdateInfo>()
        var clock = 10_000_000L
        val controller = AppUpdateController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            source = source,
            prefs = prefs,
            scheduleBackgroundCheck = { scheduled++ },
            cancelBackgroundCheck = { cancelled++ },
            announce = { announced += it },
            text = { id, args -> "$id:${args.joinToString("|")}" },
            localize = { "L($it)" },
            io = Dispatchers.Unconfined,
            now = { clock },
        )
        val state get() = controller.state.value
    }

    private fun msg(id: Int, vararg args: Any) = "$id:${args.joinToString("|")}"

    @Test
    fun startsFromTheSavedSwitches() {
        val r = Rig(prefs = Prefs(notifications = false, autoInstall = true))
        assertFalse(r.state.notifications)
        assertTrue(r.state.autoInstall)
    }

    @Test
    fun notificationsOn_schedulesTheWorker_andChecksAtOnce() {
        val r = Rig(Source().apply { result = { info } }, Prefs(notifications = false, autoInstall = false))
        r.controller.setNotifications(true)
        assertTrue(r.prefs.notifications)
        assertEquals(1, r.scheduled)
        assertEquals(info, r.state.info)
        assertEquals(listOf(info), r.announced)
    }

    @Test
    fun notificationsOff_cancelsTheWorkerOnlyIfAutoInstallIsOffToo() {
        val both = Rig(prefs = Prefs(notifications = true, autoInstall = false))
        both.controller.setNotifications(false)
        assertEquals(1, both.cancelled)
        val keep = Rig(prefs = Prefs(notifications = true, autoInstall = true))
        keep.controller.setNotifications(false)
        assertEquals(0, keep.cancelled)
    }

    @Test
    fun autoInstall_mirrorsTheSameRule() {
        val on = Rig(prefs = Prefs(notifications = false, autoInstall = false))
        on.controller.setAutoInstall(true)
        assertEquals(1, on.scheduled)
        val offKeep = Rig(prefs = Prefs(notifications = true, autoInstall = true))
        offKeep.controller.setAutoInstall(false)
        assertEquals(0, offKeep.cancelled)
        val offCancel = Rig(prefs = Prefs(notifications = false, autoInstall = true))
        offCancel.controller.setAutoInstall(false)
        assertEquals(1, offCancel.cancelled)
    }

    @Test
    fun silentCheck_doesNothingWhenNotificationsAreOff_unlessForced() {
        val r = Rig(Source().apply { result = { info } }, Prefs(notifications = false))
        r.controller.silentCheck()
        assertTrue(r.source.checks.isEmpty())
        r.controller.silentCheck(force = true)
        assertEquals(1, r.source.checks.size)
    }

    @Test
    fun silentCheck_runsAtMostOncePerHour() {
        val r = Rig(Source().apply { result = { null } })
        r.controller.silentCheck()
        r.clock += 59 * 60_000L
        r.controller.silentCheck()
        assertEquals(1, r.source.checks.size)
        r.clock += 2 * 60_000L
        r.controller.silentCheck()
        assertEquals(2, r.source.checks.size)
    }

    @Test
    fun silentCheck_ignoresErrors_andKeepsAnInfoThatIsAlreadyShown() {
        val r = Rig(Source().apply { result = { throw IOException("offline") } })
        r.controller.silentCheck()
        assertNull(r.state.info)
        assertNull(r.state.statusMessage)
        r.source.result = { info }
        r.controller.silentCheck(force = true)
        val other = info.copy(versionName = "9.9.9")
        r.source.result = { other }
        r.controller.silentCheck(force = true)
        assertEquals(info, r.state.info) // the first newer build stays; it is still announced
        assertEquals(listOf(info, other), r.announced)
    }

    @Test
    fun manualCheck_reportsEachOutcome() {
        val newer = Rig(Source().apply { result = { info } })
        newer.controller.checkNow("  https://x/m.json ")
        assertEquals(listOf("https://x/m.json"), newer.source.checks)
        assertEquals(info, newer.state.info)
        assertEquals(msg(R.string.upd_available, "1.2.3", 123), newer.state.statusMessage)
        assertFalse(newer.state.checking)

        val same = Rig(Source().apply { result = { null } })
        same.controller.checkNow()
        assertEquals(msg(R.string.upd_up_to_date), same.state.statusMessage)

        val none = Rig(Source().apply { result = { throw NoPublishedReleaseException() } })
        none.controller.checkNow()
        assertEquals(msg(R.string.upd_no_release), none.state.statusMessage)

        val offline = Rig(Source().apply { result = { throw UnknownHostException("h") } })
        offline.controller.checkNow()
        assertEquals(msg(R.string.upd_no_internet), offline.state.statusMessage)

        val broken = Rig(Source().apply { result = { throw IOException("bad json") } })
        broken.controller.checkNow()
        assertEquals(msg(R.string.upd_check_failed, "L(bad json)"), broken.state.statusMessage)
        assertNull(broken.state.info)
    }

    @Test
    fun download_reportsWholePercentSteps_andInstalls() {
        val src = Source().apply { downloadSteps = listOf(0L to 1000L, 1L to 1000L, 5L to 1000L, 5L to 1000L, 1000L to 1000L) }
        val r = Rig(src)
        r.source.result = { info }
        r.controller.checkNow()
        r.controller.downloadAndInstall()
        assertEquals(File("/tmp/app.apk"), src.installed)
        assertNull(r.state.progress)
        assertEquals(msg(R.string.upd_installing, "1.2.3"), r.state.statusMessage)
    }

    @Test
    fun download_withoutANewerBuild_doesNothing() {
        val r = Rig()
        r.controller.downloadAndInstall()
        assertNull(r.state.progress)
        assertNull(r.state.statusMessage)
    }

    @Test
    fun download_orInstall_failuresAreReported_andProgressCleared() {
        val dl = Rig(Source().apply { result = { info }; downloadFails = IOException("disk full") })
        dl.controller.checkNow()
        dl.controller.downloadAndInstall()
        assertNull(dl.state.progress)
        assertEquals(msg(R.string.upd_install_failed, "L(disk full)"), dl.state.statusMessage)

        val ins = Rig(Source().apply { result = { info }; installFails = IllegalStateException("no root") })
        ins.controller.checkNow()
        ins.controller.downloadAndInstall()
        assertEquals(msg(R.string.upd_install_failed, "L(no root)"), ins.state.statusMessage)
    }
}
