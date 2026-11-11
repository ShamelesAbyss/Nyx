package com.nyx.ai.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialVaultInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun encryptedCredentialRoundTripsAndCanBeDeleted() {
        val reference = "test-${System.nanoTime()}"
        val vault = CredentialVault(context)
        vault.save(reference, "test-secret-never-log")
        assertEquals("test-secret-never-log", vault.read(reference))
        vault.delete(reference)
        assertNull(vault.read(reference))
    }

    @Test
    fun ciphertextTamperingIsRejected() {
        val reference = "test-${System.nanoTime()}"
        val vault = CredentialVault(context)
        vault.save(reference, "tamper-test-secret")
        val file = File(context.noBackupFilesDir, "credentials/$reference.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
        assertThrows(Exception::class.java) { vault.read(reference) }
        vault.delete(reference)
    }
}
