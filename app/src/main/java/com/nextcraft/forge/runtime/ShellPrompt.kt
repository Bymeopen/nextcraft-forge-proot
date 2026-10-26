package com.nextcraft.forge.runtime

/**
 * The coloured prompt of the terminal. Ubuntu's `/root/.bashrc` sets no colours, so bash falls back to the plain
 * `user@host:path#` of `/etc/bash.bashrc`. The app keeps one marked block at the end of `/root/.bashrc` (last, so it
 * wins over earlier PS1 lines) and leaves everything else in the file alone.
 */
internal object ShellPrompt {
    private const val START = "# >>> nextcraft prompt >>>"
    private const val END = "# <<< nextcraft prompt <<<"

    /**
     * user = bold red (root is privileged, so it should be noticeable), `@` grey, host = bold green, path = bold blue,
     * `#` red. Only for interactive shells on a terminal that can show colour; `ls` and `grep` follow.
     */
    val block: String = """
        $START
        # Managed by NextCraft Forge (v1). Edit outside this block; the app rewrites what is inside.
        case ${'$'}- in
          *i*)
            if [ "${'$'}{TERM:-dumb}" != "dumb" ]; then
              PS1='\[\e[1;91m\]\u\[\e[0;90m\]@\[\e[1;92m\]\h\[\e[0m\]:\[\e[1;94m\]\w\[\e[1;91m\]\${'$'}\[\e[0m\] '
              alias ls='ls --color=auto'
              alias grep='grep --color=auto'
            fi
            ;;
        esac
        $END
    """.trimIndent()

    /** [existing] with the block replaced (or appended at the end); the result is the same if applied twice. */
    fun apply(existing: String): String {
        val start = existing.indexOf(START)
        val end = existing.indexOf(END)
        val without = if (start >= 0 && end > start) {
            existing.removeRange(start, end + END.length)
        } else {
            existing
        }
        val body = without.trimEnd()
        return if (body.isEmpty()) block + "\n" else body + "\n\n" + block + "\n"
    }
}
