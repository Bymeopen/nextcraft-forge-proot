package com.nextcraft.forge.runtime

import com.nextcraft.forge.model.ChatAttachment

/**
 * Tells the chat agent that the REA toolkit exists. Every provider receives the same text appended to the prompt, because
 * only Claude Code reads a per-user memory file. The note is added only when REA is installed and the message is about
 * binaries (an attached file of a binary type, or a request that names one), so ordinary coding chats pay nothing.
 */
internal object ReaChatHint {
    private val BINARY_EXTENSIONS = setOf("apk", "dex", "so", "elf", "bin", "img", "exe", "dll", "o", "a", "ko", "aab", "jar", "squashfs")
    private val KEYWORDS = Regex(
        "\\b(apk|dex|elf|firmware|disassembl\\w*|decompil\\w*|reverse[- ]engineer\\w*|malware|binary|biner|\\.so|rea)\\b",
        RegexOption.IGNORE_CASE,
    )

    const val NOTE: String = """

[Tool note] The `rea` reverse-engineering toolkit is installed in this environment. Run commands with `--json` and read the last stdout line.
- `rea id <file>` identify; `rea analyze <file> --strings --interesting` strings, headers, hardening; `rea analyze <file> --disasm main` disassembly
- `rea apk <file.apk>` manifest, permissions, components; `rea dex <file> --filter com.pkg`; `rea decompile <file>` (jadx/apktool)
- `rea firmware <image> --extract` firmware images; `rea report <file>` full report
- `rea run <file>` and `rea emu <file>` execute the file: only do this when the user asked, and tell them first."""

    /** True when [prompt] or one of [attachments] concerns a binary that REA can examine. */
    fun relevant(prompt: String, attachments: List<ChatAttachment>): Boolean {
        if (attachments.any { it.displayName.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS }) return true
        return KEYWORDS.containsMatchIn(prompt)
    }

    /** [prompt] with [NOTE] appended when [reaInstalled] and the message is about binaries; otherwise unchanged. */
    fun augment(prompt: String, attachments: List<ChatAttachment>, reaInstalled: Boolean): String =
        if (reaInstalled && relevant(prompt, attachments)) prompt + NOTE else prompt
}
