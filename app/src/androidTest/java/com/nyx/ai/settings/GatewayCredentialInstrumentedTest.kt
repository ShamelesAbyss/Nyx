package com.nyx.ai.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Requires Android Keystore. Compilation by the parent is not execution on a physical device. */
@RunWith(AndroidJUnit4::class)
class GatewayCredentialInstrumentedTest {
    @Test fun gatewayLogicalReferenceRoundTripsDeletesAndCannotSwapAad() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVault(context)
        val reference = "gateway:test-${System.nanoTime()}"
        val other = "$reference-other"
        try {
            vault.save(reference, "gateway-fixture-secret-never-log")
            assertEquals("gateway-fixture-secret-never-log", vault.read(reference))
            val original = File(context.noBackupFilesDir, "credentials/${CredentialReferencePolicy.fileStem(reference)}.bin")
            val swapped = File(context.noBackupFilesDir, "credentials/${CredentialReferencePolicy.fileStem(other)}.bin")
            swapped.writeBytes(original.readBytes())
            assertThrows(Exception::class.java) { vault.read(other) }
            vault.delete(reference)
            assertNull(vault.read(reference))
        } finally {
            // Only this fixture's uniquely named references are deleted; never delete the Keystore key.
            vault.delete(reference)
            vault.delete(other)
        }
    }
}
