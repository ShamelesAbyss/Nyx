package com.nyx.ai.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores API credentials as AES-GCM ciphertext in app-private storage. The AES key is non-exportable
 * and backed by Android Keystore; only an opaque credential reference belongs in Room/preferences.
 * This is a device-at-rest protection boundary, not protection against a compromised/rooted OS.
 */
class CredentialVault(context: Context) {
    private val directory = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }
    private val keyAlias = "com.nyx.ai.credentials.aes.v1"
    private val keyStoreType = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val ivLength = 12
    private val tagLengthBits = 128

    fun save(reference: String, secret: String) {
        requireValid(reference)
        require(secret.isNotBlank()) { "Credential cannot be blank." }
        require(secret.length <= 4096 && secret.none { it == '\r' || it == '\n' }) { "Credential format is invalid." }
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(reference.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        check(iv.size == ivLength) { "Unexpected AES-GCM IV length." }
        val blob = byteArrayOf(1) + iv + encrypted
        val file = atomicFile(reference)
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(blob)
            file.finishWrite(output)
        } catch (e: Exception) {
            output?.let(file::failWrite)
            throw e
        }
    }

    fun read(reference: String): String? {
        requireValid(reference)
        val file = atomicFile(reference)
        if (!file.baseFile.exists()) return null
        val blob = file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(1024)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                require(output.size() + count <= 16_413) { "Credential data exceeds its size limit." }
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        require(blob.size > 1 + ivLength + 16 && blob[0].toInt() == 1) { "Credential data is invalid." }
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(tagLengthBits, iv))
        cipher.updateAAD(reference.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, 1 + ivLength, blob.size - 1 - ivLength).toString(Charsets.UTF_8)
    }

    fun delete(reference: String) {
        requireValid(reference)
        atomicFile(reference).delete()
    }

    private fun atomicFile(reference: String) = AtomicFile(File(directory, "${CredentialReferencePolicy.fileStem(reference)}.bin"))

    private fun requireValid(reference: String) {
        CredentialReferencePolicy.fileStem(reference)
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(keyStoreType).apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreType)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
