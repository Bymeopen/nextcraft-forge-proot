package com.nextcraft.forge

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source guards for the battery work: things that quietly cost power if someone reintroduces them. */
class BatteryGuardTest {
    private fun source(path: String) = File("src/main/java/com/nextcraft/forge/$path").readText()

    @Test
    fun scheduleTick_isNotEnqueuedUnconditionally() {
        // A 15-minute periodic job for every user, scheduled or not, wakes the phone 96 times a day for nothing.
        val offenders = File("src/main/java/com/nextcraft/forge").walkTopDown()
            .filter { it.extension == "kt" && it.name != "ScheduleTickWorker.kt" }
            .filter { Regex("""ScheduleTickWorker\.schedule\(""").containsMatchIn(it.readText()) }
            .map { it.name }.toList()
        assertTrue("use ScheduleTickWorker.sync(context, hasEnabledTasks):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun scheduleStore_keepsTheTickInStepWithItsTasks() {
        assertTrue(source("schedule/ScheduledTask.kt").contains("ScheduleTickWorker.sync("))
        assertTrue(source("ForgeApplication.kt").contains("ScheduleTickWorker.sync("))
    }

    @Test
    fun androidBuildLog_isPublishedInBatches() {
        val vm = File("src/main/java/com/nextcraft/forge/ui")
            .listFiles { f -> f.name.startsWith("MainViewModel") && f.extension == "kt" }
            .orEmpty()
            .joinToString("\n") { it.readText() }
        assertTrue("build log must be throttled", vm.contains("LOG_PUBLISH_MS") && vm.contains("fun publishLog("))
    }
}
