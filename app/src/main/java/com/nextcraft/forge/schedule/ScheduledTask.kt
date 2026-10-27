package com.nextcraft.forge.schedule

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A prompt that is sent again to one chat every [intervalMinutes]. Created by the user or by the agent itself. */
data class ScheduledTask(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String,
    val chatId: String,
    val prompt: String,
    val intervalMinutes: Int,
    val nextRunAtMillis: Long,
    val enabled: Boolean = true,
    val byAgent: Boolean = false,
    val runCount: Int = 0,
    val lastRunAtMillis: Long = 0L,
) {
    fun isDue(now: Long): Boolean = enabled && nextRunAtMillis <= now
}

/** Rules shared by the user's `/loop` command and the agent's `[[schedule: …]]` directive. */
object ScheduleRules {
    const val MIN_INTERVAL_MINUTES = 5
    const val MAX_INTERVAL_MINUTES = 7 * 24 * 60
    /** An agent cannot fill the app with timers: at most this many agent-made tasks per chat. */
    const val MAX_AGENT_TASKS_PER_CHAT = 3
    const val MAX_PROMPT_LENGTH = 2_000

    /** "30m", "2h", "1d" or a bare number of minutes; null when it is not a usable interval. */
    fun parseInterval(text: String): Int? {
        val match = Regex("""^\s*(\d{1,5})\s*([mhd]?)\s*$""", RegexOption.IGNORE_CASE).matchEntire(text) ?: return null
        val number = match.groupValues[1].toLong()
        val minutes = when (match.groupValues[2].lowercase()) {
            "h" -> number * 60
            "d" -> number * 24 * 60
            else -> number
        }
        return minutes.takeIf { it in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES }?.toInt()
    }

    fun label(minutes: Int): String = when {
        minutes % (24 * 60) == 0 -> "${minutes / (24 * 60)}d"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes}m"
    }
}

/** What the agent asked for in its reply. */
sealed interface ScheduleDirective {
    data class Schedule(val intervalMinutes: Int, val prompt: String) : ScheduleDirective
    /** `[[unschedule]]`: stop every task the agent made in this chat. */
    data object Unschedule : ScheduleDirective
}

/**
 * Reads `[[schedule: 30m | check the build and report]]` and `[[unschedule]]` from an agent reply. Anything that does not
 * parse (bad interval, empty prompt) is ignored, so a reply can never create a broken task.
 */
object ScheduleDirectives {
    private val SCHEDULE = Regex("""\[\[\s*schedule\s*:\s*([^|\]]+?)\s*\|\s*([^\]]+?)\s*\]\]""", RegexOption.IGNORE_CASE)
    private val UNSCHEDULE = Regex("""\[\[\s*unschedule\s*\]\]""", RegexOption.IGNORE_CASE)

    private val KEYWORDS = Regex(
        "\\b(loop|schedule[ds]?|every|recurring|periodic\\w*|remind\\w*|jadwal\\w*|setiap|tiap|berkala|periodik|ulangi|ingatkan|pantau|monitor)\\b",
        RegexOption.IGNORE_CASE,
    )

    const val NOTE: String = """

[Scheduling note] To repeat a task later, end your reply with one line `[[schedule: <interval> | <prompt to send back to this chat>]]`, e.g. `[[schedule: 30m | Check the CI result and report only changes]]` (interval 5m–7d; at most 3 per chat). Write `[[unschedule]]` to stop them. Only do this when the user asked for something to be repeated or monitored."""

    fun parse(reply: String): List<ScheduleDirective> {
        val found = mutableListOf<Pair<Int, ScheduleDirective>>()
        SCHEDULE.findAll(reply).forEach { m ->
            val minutes = ScheduleRules.parseInterval(m.groupValues[1]) ?: return@forEach
            val prompt = m.groupValues[2].trim().take(ScheduleRules.MAX_PROMPT_LENGTH)
            if (prompt.isNotEmpty()) found += m.range.first to ScheduleDirective.Schedule(minutes, prompt)
        }
        UNSCHEDULE.findAll(reply).forEach { found += it.range.first to ScheduleDirective.Unschedule }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** True when the message is about repeating or monitoring, so only those messages carry [NOTE]. */
    fun relevant(prompt: String): Boolean = KEYWORDS.containsMatchIn(prompt)

    fun augment(prompt: String, enabled: Boolean): String = if (enabled && relevant(prompt)) prompt + NOTE else prompt
}

/** Pure bookkeeping on the task list; [ScheduleStore] only saves the result. */
object ScheduleLogic {
    /** Tasks that are due and are not [busyChats]; each is moved to its next slot (never into the past). */
    fun takeDue(tasks: List<ScheduledTask>, now: Long, accept: (ScheduledTask) -> Boolean): Pair<List<ScheduledTask>, List<ScheduledTask>> {
        val started = mutableListOf<ScheduledTask>()
        val updated = tasks.map { task ->
            if (task.isDue(now) && accept(task)) {
                val next = task.copy(
                    nextRunAtMillis = now + task.intervalMinutes * 60_000L,
                    lastRunAtMillis = now,
                    runCount = task.runCount + 1,
                )
                started += task
                next
            } else task
        }
        return updated to started
    }

    /**
     * Applies the agent's directives for one chat; returns the new list. A new timer created by `[[schedule: …]]`
     * starts enabled so background wakeups (such as 15m CI/PR checks) run automatically while the foreground chat
     * stays idle, and a re-issued directive preserves whatever enabled state the user has set on the switch.
     * `[[unschedule]]` removes all agent-created timers in that chat immediately.
     */
    fun applyDirectives(
        tasks: List<ScheduledTask>,
        directives: List<ScheduleDirective>,
        projectId: String,
        chatId: String,
        now: Long,
    ): List<ScheduledTask> {
        var result = tasks
        for (d in directives) {
            result = when (d) {
                ScheduleDirective.Unschedule -> result.filterNot { it.projectId == projectId && it.chatId == chatId && it.byAgent }
                is ScheduleDirective.Schedule -> {
                    val mine = result.filter { it.projectId == projectId && it.chatId == chatId && it.byAgent }
                    when {
                        // The same prompt again replaces its timer instead of stacking a second one.
                        mine.any { it.prompt == d.prompt } -> result.map {
                            if (it.projectId == projectId && it.chatId == chatId && it.byAgent && it.prompt == d.prompt)
                                it.copy(intervalMinutes = d.intervalMinutes, nextRunAtMillis = now + d.intervalMinutes * 60_000L)
                            else it
                        }
                        mine.size >= ScheduleRules.MAX_AGENT_TASKS_PER_CHAT -> result
                        else -> result + ScheduledTask(
                            projectId = projectId, chatId = chatId, prompt = d.prompt,
                            intervalMinutes = d.intervalMinutes, nextRunAtMillis = now + d.intervalMinutes * 60_000L, byAgent = true,
                            enabled = true,
                        )
                    }
                }
            }
        }
        return result
    }
}

/** Keeps the task list in app storage (plain JSON, no secrets). */
class ScheduleStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("nextcraft_schedule", Context.MODE_PRIVATE)

    @Synchronized
    fun load(): List<ScheduledTask> = runCatching {
        val arr = JSONArray(prefs.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ScheduledTask(
                id = o.getString("id"), projectId = o.getString("projectId"), chatId = o.getString("chatId"),
                prompt = o.getString("prompt"), intervalMinutes = o.getInt("intervalMinutes"),
                nextRunAtMillis = o.getLong("next"), enabled = o.optBoolean("enabled", true),
                byAgent = o.optBoolean("byAgent", false), runCount = o.optInt("runCount", 0),
                lastRunAtMillis = o.optLong("last", 0L),
            )
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun save(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(JSONObject().put("id", t.id).put("projectId", t.projectId).put("chatId", t.chatId)
                .put("prompt", t.prompt).put("intervalMinutes", t.intervalMinutes).put("next", t.nextRunAtMillis)
                .put("enabled", t.enabled).put("byAgent", t.byAgent).put("runCount", t.runCount).put("last", t.lastRunAtMillis))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
        // The periodic check exists only while there is a task to check.
        ScheduleTickWorker.sync(appContext, tasks.any { it.enabled })
    }

    private companion object { const val KEY = "tasks" }
}
