package com.nyx.ai.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutableOriginTest {

    private val termuxBin = "/data/data/com.termux/files/usr/bin"

    @Test
    fun `termux host resolves every absolute path to the host origin`() {
        assertEquals(ExecutableOrigin.TERMUX_HOST, ExecutableClassifier.classify("termux_host", "$termuxBin/git"))
        assertEquals(ExecutableOrigin.TERMUX_HOST, ExecutableClassifier.classify("termux_host", "/system/bin/sh"))
        assertEquals(
            ExecutableOrigin.TERMUX_HOST,
            ExecutableClassifier.classify(" Termux_Host ", "/usr/local/bin/make"),
        )
    }

    @Test
    fun `guest binaries under the termux prefix are inherited host tools`() {
        assertEquals(
            ExecutableOrigin.GUEST_INHERITED_TERMUX,
            ExecutableClassifier.classify("proot:debian", "$termuxBin/git"),
        )
        assertEquals(
            ExecutableOrigin.GUEST_INHERITED_TERMUX,
            ExecutableClassifier.classify("guest", "$termuxBin/clang"),
        )
        assertEquals(
            ExecutableOrigin.GUEST_INHERITED_TERMUX,
            ExecutableClassifier.classify("PROOT:Ubuntu", "/data/data/com.termux/files/usr"),
        )
    }

    @Test
    fun `guest distribution binaries are the native guest origin`() {
        assertEquals(ExecutableOrigin.GUEST_NATIVE, ExecutableClassifier.classify("proot:debian", "/usr/bin/git"))
        assertEquals(
            ExecutableOrigin.GUEST_NATIVE,
            ExecutableClassifier.classify("proot:debian", "/usr/local/bin/node"),
        )
        assertEquals(ExecutableOrigin.GUEST_NATIVE, ExecutableClassifier.classify("guest:debian", "/bin/sh"))
    }

    @Test
    fun `android platform binaries are classified separately inside a guest`() {
        assertEquals(ExecutableOrigin.ANDROID_NATIVE, ExecutableClassifier.classify("proot:debian", "/system/bin/sh"))
        assertEquals(
            ExecutableOrigin.ANDROID_NATIVE,
            ExecutableClassifier.classify("guest:debian", "/apex/com.android.runtime/bin/dalvikvm"),
        )
    }

    @Test
    fun `missing path evidence is unknown`() {
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("proot:debian", null))
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("proot:debian", ""))
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("proot:debian", "   "))
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("container", null))
    }

    @Test
    fun `unresolved relative paths are never guest native`() {
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("proot:debian", "git"))
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("proot:debian", "bin/git"))
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("proot:debian", "./git"))
    }

    @Test
    fun `unknown environment only recognizes app private binaries`() {
        assertEquals(
            ExecutableOrigin.ANDROID_NATIVE,
            ExecutableClassifier.classify("container", "/data/user/0/com.nyx.ai/files/bin/tool"),
        )
        assertEquals(
            ExecutableOrigin.ANDROID_NATIVE,
            ExecutableClassifier.classify("", "/data/data/com.nyx.ai/files/nyx-helper"),
        )
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("container", "/usr/bin/git"))
        assertEquals(ExecutableOrigin.UNKNOWN, ExecutableClassifier.classify("container", "$termuxBin/git"))
    }

    @Test
    fun `termux lookalike prefixes stay guest native`() {
        assertEquals(
            ExecutableOrigin.GUEST_NATIVE,
            ExecutableClassifier.classify("proot:debian", "/data/data/com.termux/files/usrs/bin/git"),
        )
        assertEquals(
            ExecutableOrigin.GUEST_NATIVE,
            ExecutableClassifier.classify("proot:debian", "/data/data/com.termux.files/usr/bin/git"),
        )
    }

    @Test
    fun `only guest native origin is usable inside a guest`() {
        assertTrue(ExecutableClassifier.isUsableInGuest(ExecutableOrigin.GUEST_NATIVE))
        assertFalse(ExecutableClassifier.isUsableInGuest(ExecutableOrigin.GUEST_INHERITED_TERMUX))
        assertFalse(ExecutableClassifier.isUsableInGuest(ExecutableOrigin.TERMUX_HOST))
        assertFalse(ExecutableClassifier.isUsableInGuest(ExecutableOrigin.ANDROID_NATIVE))
        assertFalse(ExecutableClassifier.isUsableInGuest(ExecutableOrigin.UNKNOWN))
    }

    @Test
    fun `classification depends on the path not the tool name`() {
        assertEquals(
            ExecutableOrigin.GUEST_INHERITED_TERMUX,
            ExecutableClassifier.classify("proot:debian", "$termuxBin/pkg"),
        )
        assertEquals(
            ExecutableOrigin.GUEST_INHERITED_TERMUX,
            ExecutableClassifier.classify("proot:debian", "$termuxBin/python3"),
        )
        assertEquals(
            ExecutableOrigin.GUEST_NATIVE,
            ExecutableClassifier.classify("proot:debian", "/usr/bin/python3"),
        )
    }
}
