package com.nextcraft.forge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Restored settings are loaded through the live SharedPreferences, so the XML parse must be exact (C5). */
class PrefsXmlTest {
    private val xml = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
        <map>
            <string name="theme">dark &amp; "quoted"</string>
            <boolean name="android_host_tools" value="true" />
            <boolean name="auto_approve_all_tools" value="true" />
            <boolean name="interactive_high_risk_approvals" value="false" />
            <int name="jobs" value="4" />
            <long name="last_run" value="1700000000000" />
            <float name="scale" value="1.5" />
            <set name="tags"><string>a</string><string>b</string></set>
            <boolean name="broken" value="maybe" />
            <unknown name="x" value="1" />
        </map>"""

    @Test
    fun parsesEveryValueType() {
        val values = PrefsXml.parse(xml)
        assertEquals("dark & \"quoted\"", values["theme"])
        assertEquals(true, values["android_host_tools"])
        assertEquals(4, values["jobs"])
        assertEquals(1700000000000L, values["last_run"])
        assertEquals(1.5f, values["scale"])
        assertEquals(setOf("a", "b"), values["tags"])
        assertTrue("broken" !in values && "x" !in values)
    }

    @Test
    fun privilegedFlagsAreResetAfterRestore() {
        val restored = PrefsXml.withPrivilegedReset(PrefsXml.parse(xml))
        assertEquals(false, restored["android_host_tools"])
        assertEquals(false, restored["auto_approve_all_tools"])
        assertEquals(true, restored["interactive_high_risk_approvals"])
        assertEquals("dark & \"quoted\"", restored["theme"])
    }

    @Test
    fun rejectsOtherDocumentsAndDoctypes() {
        try {
            PrefsXml.parse("<settings/>")
            fail()
        } catch (_: IllegalArgumentException) {
        }
        try {
            PrefsXml.parse("""<!DOCTYPE map [<!ENTITY x SYSTEM "file:///etc/hostname">]><map><string name="a">&x;</string></map>""")
            fail()
        } catch (_: Exception) {
        }
    }
}
