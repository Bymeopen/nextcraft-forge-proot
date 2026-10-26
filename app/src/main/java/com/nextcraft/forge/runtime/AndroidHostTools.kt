package com.nextcraft.forge.runtime

import java.io.File

/**
 * Optional bridge from the Ubuntu chroot to Android's own root shell, so agents can use `screencap`, `dumpsys`, `pm`,
 * `am`, `input` and `logcat`. The chroot has no binder and no Bionic libraries, so these tools cannot run inside it;
 * `nsenter -t 1 -m` joins init's mount namespace, where /system and /apex are the real ones and binder works.
 *
 * Off by default (Settings → Android system access): it hands agents full root on Android, and every call is
 * classified HIGH risk so Claude Code asks first.
 */
internal object AndroidHostTools {
    const val GUEST_DIR = "opt/nextcraft/bin"
    private const val SHELL = "android-shell"

    /** Guest file name to script. */
    val SCRIPTS: Map<String, String> = linkedMapOf(
        SHELL to """#!/bin/sh
# Runs a command in Android's root shell, outside the Ubuntu chroot (NextCraft: Settings → Android system access).
#   android-shell 'dumpsys battery'      android-shell 'pm list packages -3'
[ -n "${'$'}1" ] || { echo "usage: android-shell 'command'" >&2; exit 2; }
# Android tools need init's environment (BOOTCLASSPATH for am/pm, ANDROID_* roots); the chroot removed it.
ENV=${'$'}(tr '\0' '\n' < /proc/1/environ 2>/dev/null | grep -E '^(PATH|ANDROID_[A-Z_]+|BOOTCLASSPATH|DEX2OATBOOTCLASSPATH|SYSTEMSERVERCLASSPATH|STANDALONE_SYSTEMSERVER_JARS|EXTERNAL_STORAGE)=')
[ -n "${'$'}ENV" ] || ENV="PATH=/product/bin:/apex/com.android.runtime/bin:/apex/com.android.art/bin:/system_ext/bin:/system/bin:/system/xbin:/odm/bin:/vendor/bin:/vendor/xbin"
# shellcheck disable=SC2086 # one VAR=value per word; init's values contain no spaces
exec env -i ${'$'}ENV nsenter -t 1 -m -- /system/bin/sh -c "${'$'}*"
""",
        "android-screencap" to """#!/bin/sh
# Saves a PNG of the current phone screen: android-screencap [file.png]
out="${'$'}{1:-screenshot-${'$'}(date +%Y%m%d-%H%M%S).png}"
android-shell 'screencap -p' > "${'$'}out" && [ -s "${'$'}out" ] && echo "${'$'}out"
""",
        "android-dumpsys" to """#!/bin/sh
# Android service state: android-dumpsys [service] [args], e.g. android-dumpsys package com.example.app
exec android-shell "dumpsys ${'$'}*"
""",
    )

    /** Installs or removes the helpers in the guest so that the setting is the only switch. */
    fun apply(rootfs: File, enabled: Boolean, write: (File, String) -> Unit) {
        val dir = File(rootfs, GUEST_DIR)
        SCRIPTS.forEach { (name, body) ->
            val file = File(dir, name)
            if (enabled) write(file, body) else file.delete()
        }
    }

    private val TRIGGER = Regex(
        "screen ?shot|screencap|tangkapan layar|dumpsys|logcat|\\badb\\b|\\bpm (list|install|path|clear|grant)|am start|input (tap|swipe|text)|" +
            "(test|uji|cek|periksa)\\w* (aplikasi|app|apk)",
        RegexOption.IGNORE_CASE,
    )

    /** Tells the agent about the helpers when the message is about testing or inspecting the phone. */
    fun augment(prompt: String, enabled: Boolean): String {
        if (!enabled || !TRIGGER.containsMatchIn(prompt)) return prompt
        return prompt + "\n\n[NextCraft] Android system access is on. From the Ubuntu shell use `android-shell '<cmd>'` " +
            "to run Android commands as root (pm, am, input, dumpsys, logcat, screencap), `android-screencap file.png` " +
            "for a screenshot and `android-dumpsys <service>`. Plain pm/dumpsys/screencap do not work inside the chroot."
    }
}
