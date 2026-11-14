package com.nyx.ai.workbench

import com.nyx.ai.autonomy.policy.AutonomyMode
import com.nyx.ai.network.FetchedPage
import com.nyx.ai.network.NetworkDecision
import com.nyx.ai.network.NetworkPolicy
import com.nyx.ai.network.NetworkTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Hard limits are validated, not silently widened by model/tool input. */
data class ResearchBudget(
    val maxSources: Int = 6,
    val maxBytesPerSource: Long = 512 * 1024,
    val maxTotalBytes: Long = 2 * 1024 * 1024,
    val concurrency: Int = 2,
    val perHostDelayMs: Long = 750,
    val maxLinksPerSource: Int = 12,
    val maxContextChars: Int = 24_000,
) {
    internal fun validate(testFixture: Boolean) {
        require(maxSources in 1..20 && maxBytesPerSource in 1..524_288 && maxTotalBytes in 1..8_388_608)
        require(concurrency in 1..4 && perHostDelayMs in (if (testFixture) 0L else 100L)..10_000L)
        require(maxLinksPerSource in 0..40 && maxContextChars in 256..100_000)
    }
}

data class ResearchPage(
    val source: ResearchSource,
    val document: ExtractedDocument,
    val truncated: Boolean,
    val duplicateOf: String? = null,
) {
    fun asUntrustedBlock(maxChars: Int = 12_000): String = UntrustedEvidence.wrap(
        "Source: ${source.url}\nFinal URL: ${source.finalUrl}\nTitle: ${document.title}\nFetched at: ${source.fetchedAt}\n" +
            "Published (unverified): ${document.publishedAt}\nModified (unverified): ${document.modifiedAt}\nSHA-256: ${source.sha256}",
        "Description: ${document.description}\n\n${document.text}", maxChars,
    )
}

data class ResearchResult(
    val sources: List<ResearchSource>,
    val pages: List<ResearchPage>,
    val notes: List<String> = emptyList(),
    val contextLimit: Int = 24_000,
) {
    fun asUntrustedBlock(maxChars: Int = contextLimit): String {
        val cap = maxChars.coerceIn(256, minOf(contextLimit, 100_000))
        val content = buildString {
            pages.filter { it.duplicateOf == null }.forEachIndexed { index, page ->
                if (length < cap) {
                    appendLine("[S${index + 1}] ${page.source.url} | ${page.document.title} | fetched ${page.source.fetchedAt}")
                    appendLine("Published (unverified): ${page.document.publishedAt}; modified (unverified): ${page.document.modifiedAt}")
                    appendLine("Description: ${page.document.description}")
                    appendLine(page.document.text.take((cap - length).coerceAtLeast(0)))
                }
            }
        }
        return UntrustedEvidence.wrap("Read-only selected URL research. ${pages.size} fetched; ${sources.count { it.status == "FAILED" }} failed. " +
            "Links were not navigated. Duplicated content is omitted. Evidence may be truncated.", content, cap)
    }
}

/**
 * Caller authorization is independent of requested/model-supplied URLs. Production has no local
 * network bypass; the existing NetworkPolicy is assessed again at every request and redirect.
 */
class ResearchEngine private constructor(
    private val store: WorkbenchStore,
    authorizedUrls: Set<String>,
    private val modeProvider: () -> AutonomyMode,
    private val client: OkHttpClient,
    private val testFixture: Boolean,
) {
    constructor(store: WorkbenchStore, authorizedUrls: Set<String>, modeProvider: () -> AutonomyMode) :
        this(store, authorizedUrls, modeProvider, NetworkTools.defaultClient(), false)

    private val authorized = authorizedUrls.map { canonical(it) }.toSet().also {
        require(it.size in 1..60) { "Select between 1 and 60 explicit public HTTPS URLs." }
    }
    private data class HostPace(val mutex: Mutex = Mutex(), var nextNanos: Long = 0)
    private val hostPaces = ConcurrentHashMap<String, HostPace>()
    // Limits concurrent whole batches too: multiple calls cannot multiply the engine's HTTP cap.
    private val batchMutex = Mutex()

    suspend fun research(urls: List<String> = authorized.toList(), budget: ResearchBudget = ResearchBudget()): ResearchResult = batchMutex.withLock {
        budget.validate(testFixture)
        require(urls.size <= 60) { "Too many requested URLs." }
        coroutineContext.ensureActive()
        val notes = mutableListOf("Link navigation deferred: discovered candidates are not fetched. Separate explicit URL approval and robots review are required.")
        val selected = linkedSetOf<String>()
        urls.forEach { input ->
            // Invalid/credential-containing input is not reflected into durable metadata or errors.
            val url = canonical(input)
            require(url in authorized) { "Requested URL was not explicitly authorized by the user." }
            selected += url
        }
        if (selected.size > budget.maxSources) notes += "Source-count budget reached; remaining selected URLs were not fetched."
        var available = budget.maxTotalBytes
        val work = selected.take(budget.maxSources).map { url ->
            val reserved = minOf(available, budget.maxBytesPerSource)
            available -= reserved
            url to reserved
        }
        val semaphore = Semaphore(budget.concurrency)
        val outcomes = coroutineScope {
            work.map { (url, cap) -> async {
                semaphore.withPermit {
                    coroutineContext.ensureActive()
                    if (cap <= 0) return@withPermit failure(url, "Total byte budget exhausted; no request made.")
                    try {
                        val raw = fetchGuarded(url, cap, budget.perHostDelayMs)
                        coroutineContext.ensureActive()
                        val extracted = HtmlTextExtractor.extract(raw.text, raw.contentType, raw.finalUrl,
                            maxChars = minOf(cap, 200_000).toInt(), maxLinks = budget.maxLinksPerSource)
                        if (extracted.text.isBlank()) throw IOException("No readable text was extracted; JavaScript rendering is not supported.")
                        val bytes = boundedUtf8(extracted.text, minOf(cap, 524_288).toInt())
                        val readable = bytes.toString(Charsets.UTF_8)
                        if (readable.isBlank()) throw IOException("Byte budget could not retain readable UTF-8 text.")
                        val truncated = raw.truncated || readable != extracted.text || extracted.text.length >= minOf(cap, 200_000)
                        val document = extracted.copy(text = readable)
                        val note = buildString {
                            if (truncated) append("Content truncated within byte/extraction budget. ")
                            if (document.publishedAt.isNotBlank()) append("Published (unverified): ${document.publishedAt}. ")
                            if (document.modifiedAt.isNotBlank()) append("Modified (unverified): ${document.modifiedAt}. ")
                            append("Link navigation deferred; robots not requested.")
                            if (document.description.isNotBlank()) append(" Description: ${document.description.take(120)}")
                        }.take(512)
                        coroutineContext.ensureActive()
                        val source = withContext(Dispatchers.IO) { store.recordSource(ResearchSource(
                            url, raw.finalUrl, document.title, raw.fetchedAt, raw.contentType.orEmpty(), status = "FETCHED", note = note,
                        ), bytes) }
                        Outcome(source, ResearchPage(source, document, truncated))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        coroutineContext.ensureActive()
                        failure(url, safeFailure(error))
                    }
                }
            } }.awaitAll()
        }
        val hashes = mutableMapOf<String, String>()
        val pages = outcomes.mapNotNull { it.page }.map { page ->
            val digest = sha256(page.document.text.replace(Regex("\\s+"), " ").trim().toByteArray(Charsets.UTF_8))
            val previous = hashes.putIfAbsent(digest, page.source.id.ifBlank { page.source.url })
            page.copy(duplicateOf = previous)
        }
        ResearchResult(outcomes.map { it.source }, pages, notes, budget.maxContextChars)
    }

    private data class Outcome(val source: ResearchSource, val page: ResearchPage? = null)

    private suspend fun failure(url: String, reason: String): Outcome {
        coroutineContext.ensureActive()
        val source = withContext(Dispatchers.IO) { store.recordSource(ResearchSource(url, status = "FAILED", note = reason.take(500))) }
        return Outcome(source)
    }

    private suspend fun fetchGuarded(url: String, cap: Long, delayMs: Long): FetchedPage = coroutineScope {
        assess(url)
        val parsed = url.toHttpUrlOrNull() ?: throw IOException("Invalid URL.")
        val calls = ConcurrentHashMap.newKeySet<Call>()
        val activeContext = coroutineContext
        // Cancellation watcher runs independently of the thread blocked in OkHttp execute().
        val watcher = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { calls.forEach(Call::cancel) }
        }
        var previousHopNanos = 0L
        val guarded = client.newBuilder()
            .followRedirects(false).followSslRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = client.dns.lookup(hostname).also { answers ->
                    if (!(testFixture && NetworkPolicy.isLoopbackHost(hostname)) && (answers.isEmpty() || answers.any { !publicAddress(it) })) {
                        throw IOException("DNS resolved to a non-public address.")
                    }
                }
            })
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) { calls += call; if (!activeContext[kotlinx.coroutines.Job]!!.isActive) call.cancel() }
                override fun callEnd(call: Call) { calls -= call }
                override fun callFailed(call: Call, ioe: IOException) { calls -= call }
            })
            .addInterceptor { chain ->
                val target = chain.request().url
                val normalized = canonical(target.toString())
                if (normalized !in authorized || !sameOrigin(parsed, target)) throw IOException("Redirect/request outside the explicitly authorized URL and origin scope.")
                assess(normalized)
                // Redirects are new requests too. Pacing stays cancellable in bounded sleep slices.
                if (previousHopNanos != 0L) {
                    val deadline = previousHopNanos + delayMs * 1_000_000L
                    while (System.nanoTime() < deadline) {
                        activeContext.ensureActive()
                        if (chain.call().isCanceled()) throw IOException("Research cancelled.")
                        Thread.sleep(minOf(50L, ((deadline - System.nanoTime()) / 1_000_000L + 1).coerceAtLeast(1)))
                    }
                }
                activeContext.ensureActive()
                previousHopNanos = System.nanoTime()
                chain.proceed(chain.request().newBuilder().removeHeader("Authorization").removeHeader("Cookie").removeHeader("Proxy-Authorization").build())
            }
            .build()
        try {
            // Hold only this host's pace lock through the fetch, so every redirect hop is serialized
            // and repeated calls cannot race pacing. Different public hosts still run concurrently.
            val pace = hostPaces.computeIfAbsent(parsed.host) { HostPace() }
            pace.mutex.withLock {
                val remaining = pace.nextNanos - System.nanoTime()
                if (remaining > 0) delay((remaining / 1_000_000L) + 1)
                coroutineContext.ensureActive()
                try { NetworkTools(guarded).fetchText(url, cap) }
                finally { pace.nextNanos = System.nanoTime() + delayMs * 1_000_000L }
            }
        } finally {
            watcher.cancel()
            calls.forEach(Call::cancel)
        }
    }

    private fun assess(url: String) {
        val assessment = NetworkPolicy.assess(url, upload = false, mode = modeProvider())
        if (assessment.decision != NetworkDecision.ALLOW) throw IOException("Network ${assessment.decision.name.lowercase()}: ${assessment.reason}")
    }

    private fun canonical(input: String): String {
        require(input.length <= 4_096 && input.none { it.isISOControl() || it == '\\' }) { "Invalid selected URL." }
        val structural = runCatching { java.net.URI(input.trim()) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid selected URL.")
        require(structural.rawUserInfo == null) { "Credentials in URLs are refused." }
        val url = input.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid selected URL.")
        require(url.username.isEmpty() && url.password.isEmpty() && !url.encodedPath.contains("\\")) { "Credentials or invalid URL path refused." }
        val local = NetworkPolicy.isLoopbackHost(url.host)
        require((testFixture && local) || (!local && url.scheme == "https" && !NetworkPolicy.isPrivateHost(url.host) && url.port in setOf(80, 443))) {
            "Research requires a public HTTPS URL; loopback/private hosts are refused."
        }
        // NetworkPolicy has deliberately broad fc/fd hostname detection; preserve it, not bypass it.
        require(!local || testFixture)
        return url.newBuilder().fragment(null).build().toString()
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean = a.scheme == b.scheme && a.host == b.host && a.port == b.port

    private fun publicAddress(address: InetAddress): Boolean = !address.isAnyLocalAddress && !address.isLoopbackAddress &&
        !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress &&
        !NetworkPolicy.isPrivateHost(address.hostAddress.orEmpty())

    private fun safeFailure(error: Exception): String = when {
        error is IllegalArgumentException -> "URL or response refused by research safety checks."
        else -> error.message.orEmpty().take(300).replace(Regex("https?://\\S+"), "[URL]").ifBlank { "Fetch failed (${error.javaClass.simpleName})." }
    }

    companion object {
        /** Only JVM fixture code may use local HTTP. Never wire this factory into Android runtime. */
        internal fun forTests(store: WorkbenchStore, authorizedUrls: Set<String>, modeProvider: () -> AutonomyMode = { AutonomyMode.SMART },
            client: OkHttpClient = NetworkTools.defaultClient()): ResearchEngine = ResearchEngine(store, authorizedUrls, modeProvider, client, true)

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        internal fun boundedUtf8(text: String, cap: Int): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= cap) return bytes
            var end = cap
            // A UTF-8 prefix must not terminate in the middle of a multi-byte scalar.
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            return bytes.copyOf(end)
        }
    }
}
