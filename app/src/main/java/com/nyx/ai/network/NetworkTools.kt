package com.nyx.ai.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** One fetched, bounded text page. */
data class FetchedPage(
    val url: String,
    val finalUrl: String,
    val status: Int,
    val contentType: String?,
    val text: String,
    val truncated: Boolean,
    val fetchedAt: Long = System.currentTimeMillis(),
) {
    /** The only supported way to place external text into a model prompt. */
    fun asUntrustedBlock(maxChars: Int = 12_000): String = com.nyx.ai.workbench.UntrustedEvidence.wrap(
        "Source: $finalUrl", text, maxChars.coerceIn(256, 200_000),
    )
}

data class DownloadResult(val url: String, val file: File, val bytes: Long, val sha256: String, val contentType: String?)

/**
 * Network access for objectives and tools. Every redirect is re-validated, private DNS results are
 * refused, bodies are size-bounded, and downloads are written to a temporary file before an atomic
 * rename so a cancelled or oversized transfer never leaves a partly valid artifact.
 */
class NetworkTools private constructor(
    client: OkHttpClient,
    private val maxTextBytes: Long,
    private val loopbackFixture: Boolean,
) {
    constructor(client: OkHttpClient = defaultClient(), maxTextBytes: Long = 512 * 1024) : this(client, maxTextBytes, false)
    private val client = NetworkTransportGuard.publicClient(client, loopbackFixture)

    suspend fun fetchText(url: String, maxBytes: Long = maxTextBytes): FetchedPage = withContext(Dispatchers.IO) {
        val cap = maxBytes.coerceIn(1, 4L * 1024 * 1024)
        var current = validate(url)
        var redirects = 0
        while (true) {
            coroutineContext.ensureActive()
            val request = Request.Builder()
                .url(current)
                .header("Accept", "text/*, application/json, application/xml;q=0.9")
                .header("User-Agent", "NYX/0.7 (local-first Android)")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    if (redirects >= MAX_REDIRECTS) throw IOException("Too many redirects.")
                    val location = response.header("Location") ?: throw IOException("Redirect without Location.")
                    val next = current.toHttpUrlOrNull()?.resolve(location)?.toString()
                        ?: throw IOException("Invalid redirect target.")
                    current = validate(next)
                    redirects++
                    return@use
                }
                if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${response.request.url.host}.")
                val type = response.header("Content-Type")
                if (!isTextual(type)) throw IOException("Refused non-text content type: ${type ?: "unknown"}.")
                val source = response.body?.byteStream() ?: throw IOException("Empty response body.")
                val buffer = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                var total = 0L
                var truncated = false
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > cap) {
                        val allowed = (cap - buffer.size()).toInt().coerceAtLeast(0)
                        if (allowed > 0) buffer.write(chunk, 0, minOf(read, allowed))
                        truncated = true
                        break
                    }
                    buffer.write(chunk, 0, read)
                }
                val charset = response.body?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                return@withContext FetchedPage(
                    url = url,
                    finalUrl = current,
                    status = response.code,
                    contentType = type,
                    text = buffer.toByteArray().toString(charset),
                    truncated = truncated,
                )
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("Redirect loop ended without a response.")
    }

    suspend fun download(url: String, destination: File, maxBytes: Long = 64L * 1024 * 1024): DownloadResult =
        withContext(Dispatchers.IO) {
            val cap = maxBytes.coerceIn(1, 512L * 1024 * 1024)
            val parent = destination.absoluteFile.parentFile ?: throw IOException("Destination has no parent directory.")
            if (!parent.exists() && !parent.mkdirs()) throw IOException("Cannot create destination directory.")
            var current = validate(url)
            var redirects = 0
            while (true) {
                coroutineContext.ensureActive()
                val request = Request.Builder().url(current).get().build()
                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        if (redirects >= MAX_REDIRECTS) throw IOException("Too many redirects.")
                        val location = response.header("Location") ?: throw IOException("Redirect without Location.")
                        val next = current.toHttpUrlOrNull()?.resolve(location)?.toString()
                            ?: throw IOException("Invalid redirect target.")
                        current = validate(next)
                        redirects++
                        return@use
                    }
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code} from ${response.request.url.host}.")
                    val body = response.body ?: throw IOException("Empty response body.")
                    val temp = File.createTempFile(".nyx-download-", ".part", parent)
                    var completed = false
                    try {
                        val digest = MessageDigest.getInstance("SHA-256")
                        var total = 0L
                        FileOutputStream(temp).use { output ->
                            body.byteStream().use { input ->
                                val chunk = ByteArray(64 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val read = input.read(chunk)
                                    if (read < 0) break
                                    total += read
                                    if (total > cap) throw IOException("Download exceeds the $cap byte limit.")
                                    digest.update(chunk, 0, read)
                                    output.write(chunk, 0, read)
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                        if (total <= 0) throw IOException("Refused an empty download.")
                        if (!temp.renameTo(destination)) {
                            temp.copyTo(destination, overwrite = true)
                            temp.delete()
                        }
                        completed = true
                        return@withContext DownloadResult(
                            url = url,
                            file = destination,
                            bytes = total,
                            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                            contentType = response.header("Content-Type"),
                        )
                    } finally {
                        if (!completed) temp.delete()
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("Redirect loop ended without a response.")
        }

    private fun validate(url: String): String {
        val parsed = url.trim().toHttpUrlOrNull() ?: throw IOException("Malformed URL.")
        if (!(loopbackFixture && NetworkPolicy.isLoopbackHost(parsed.host))) {
            NetworkPolicy.publicUrlProblem(url)?.let { throw IOException(it) }
            NetworkPolicy.publicUrlProblem(parsed.toString())?.let { throw IOException(it) }
        } else {
            try { InferenceEndpointPolicy.parse(url) } catch (_: IllegalArgumentException) { throw IOException("Fixture URL refused.") }
        }
        return parsed.toString()
    }

    private fun isTextual(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type.startsWith("text/") || type == "application/json" || type == "application/xml" ||
            type.endsWith("+json") || type.endsWith("+xml") || type == "application/xhtml+xml"
    }

    companion object {
        private const val MAX_REDIRECTS = 3

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** JVM local-server seam only. Production constructors always enforce public-only access. */
        internal fun forTests(client: OkHttpClient = defaultClient(), maxTextBytes: Long = 512 * 1024): NetworkTools =
            NetworkTools(client, maxTextBytes, true)
    }
}
