package com.nextcraft.forge.runtime

/**
 * `run-x86`, installed in the guest as /opt/nextcraft/bin/run-x86. Runs an x86_64 Linux binary (for example the output
 * of a cross build from the Build matrix) through qemu-user without registering a binfmt handler, which many Android
 * kernels do not offer. The handler is only a convenience; this wrapper works wherever qemu-user itself runs.
 */
internal object X86Wrapper {
    const val GUEST_PATH = "opt/nextcraft/bin/run-x86"

    val SCRIPT = """
        #!/bin/sh
        if [ "${'$'}#" -eq 0 ]; then
            echo "Usage: run-x86 <x86_64-binary> [args...]" >&2
            exit 2
        fi
        qemu=${'$'}(command -v qemu-x86_64-static || command -v qemu-x86_64) || {
            echo "qemu-x86_64 is missing. Install it: apt-get install -y qemu-user" >&2
            exit 127
        }
        # Dynamically linked binaries need an x86_64 libc; the cross package puts it in /usr/x86_64-linux-gnu.
        if [ -d /usr/x86_64-linux-gnu/lib ]; then
            exec "${'$'}qemu" -L /usr/x86_64-linux-gnu "${'$'}@"
        fi
        exec "${'$'}qemu" "${'$'}@"
    """.trimIndent() + "\n"
}
