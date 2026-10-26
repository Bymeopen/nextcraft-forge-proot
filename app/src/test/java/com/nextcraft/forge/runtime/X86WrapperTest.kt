package com.nextcraft.forge.runtime

import org.junit.Assert.assertTrue
import org.junit.Test

class X86WrapperTest {
    @Test
    fun scriptIsPosixShellThatRunsQemu() {
        val script = X86Wrapper.SCRIPT
        assertTrue(script.startsWith("#!/bin/sh\n"))
        assertTrue("qemu-x86_64" in script)
        assertTrue("apt-get install -y qemu-user" in script)
        assertTrue("Kotlin escapes must be resolved", "{'" !in script)
    }
}
