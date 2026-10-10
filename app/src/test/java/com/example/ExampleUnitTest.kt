package com.example

import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun testShellPathEscaping() {
        val path1 = "/sdcard/Download/test.txt"
        assertEquals("'/sdcard/Download/test.txt'", Replacer.escapeShellPath(path1))

        val pathWithQuote = "/sdcard/Download/John's File.txt"
        assertEquals("'/sdcard/Download/John'\\''s File.txt'", Replacer.escapeShellPath(pathWithQuote))
    }

    @Test
    fun testRestrictedPathDetection() {
        assertTrue(Replacer.isRestrictedPath("/sdcard/Android/data/com.game/files/data.bin"))
        assertTrue(Replacer.isRestrictedPath("/storage/emulated/0/Android/obb/com.game/main.obb"))
        assertFalse(Replacer.isRestrictedPath("/sdcard/Download/mod.pak"))
        assertFalse(Replacer.isRestrictedPath("/sdcard/QuickReplacer/test.xml"))
    }

    @Test
    fun testExtractPackageName() {
        val dest1 = "/sdcard/Android/data/com.activision.callofduty.shooter/files/UserCustom.ini"
        assertEquals("com.activision.callofduty.shooter", Replacer.extractPackageName(dest1))

        val dest2 = "/sdcard/Android/obb/com.dts.freefireth/main.obb"
        assertEquals("com.dts.freefireth", Replacer.extractPackageName(dest2))

        val dest3 = "/sdcard/Download/somefile.txt"
        assertEquals("", Replacer.extractPackageName(dest3))
    }

    @Test
    fun testOptionDataClass() {
        val opt = Option(
            name = "Test Replace",
            source = "/sdcard/Download/src.bin",
            destination = "/sdcard/Android/data/com.test.app/files/dst.bin"
        )
        assertEquals("Test Replace", opt.name)
        assertTrue(opt.backup)
        assertTrue(opt.fixPermissions)
        assertTrue(opt.forceStop)
    }
}
