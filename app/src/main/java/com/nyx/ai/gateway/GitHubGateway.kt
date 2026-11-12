package com.nyx.ai.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Native GitHub REST v3 integration. It is deliberately not a generic HTTP proxy: every tool maps to
 * one endpoint, the base URL is fixed to `https://api.github.com` (a test base URL may be injected),
 * and the credential only ever appears in the `Authorization` header.
 *
 * Publishing tools are separate names from reading tools so the host UI and the autonomy policy can
 * gate them independently. Force pushes, branch deletion, repository deletion, and review approvals
 * are not implemented at all.
 */
class GitHubGateway(
    private val config: GatewayServerConfig,
    private val tokenProvider: GatewayTokenProvider,
    baseUrl: String = DEFAULT_BASE_URL,
    httpClient: OkHttpClient? = null,
) : GatewayToolExecutor {
    private val base: HttpUrl = runValidatedBase(baseUrl)
    private val ownedDispatcher: Dispatcher? = if (httpClient == null) Dispatcher() else null
    private val httpClient: OkHttpClient = httpClient ?: defaultClient(config.timeoutSeconds, ownedDispatcher!!)
    private val activeCalls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())

    @Volatile private var closed = false

    override suspend fun listTools(): List<GatewayTool> = if (config.allowedTools.isEmpty()) {
        TOOL_CATALOG
    } else {
        TOOL_CATALOG.filter { it.name in config.allowedTools }
    }

    override suspend fun callTool(name: String, argumentsJson: String): GatewayToolResult {
        if (closed) throw GatewayException("GitHub gateway is closed.")
        if (config.allowedTools.isNotEmpty() && name !in config.allowedTools) {
            throw GatewayException("Tool '$name' is not allowed by gateway '${config.id}'.")
        }
        if (argumentsJson.length > GatewayLimits.MAX_ARGUMENT_CHARS) {
            throw GatewayException("Tool arguments exceed ${GatewayLimits.MAX_ARGUMENT_CHARS} characters.")
        }
        val args = if (argumentsJson.isBlank()) JSONObject() else runCatching { JSONObject(argumentsJson) }.getOrNull()
            ?: throw GatewayException("Tool arguments must be a JSON object.")
        val started = System.nanoTime()
        val content = try {
            dispatch(name, args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        val latencyMs = (System.nanoTime() - started) / 1_000_000
        return GatewayToolResult(
            success = true,
            content = GatewayLimits.truncate(content, config.maxResponseChars),
            isError = false,
            latencyMs = latencyMs,
            boundedRaw = GatewayLimits.truncate(content, minOf(config.maxResponseChars, GatewayLimits.MAX_LOG_RESULT_CHARS * 8)),
        )
    }

    override suspend fun health(): GatewayHealth {
        if (closed) return GatewayHealth(GatewayHealthState.MISCONFIGURED, "Gateway is closed.", 0)
        if (config.credentialRef.isNullOrBlank()) {
            return GatewayHealth(GatewayHealthState.MISCONFIGURED, "GitHub credential reference is missing.", TOOL_CATALOG.size)
        }
        return try {
            val response = request(
                method = "GET",
                segments = listOf("rate_limit"),
                accept = JSON_ACCEPT,
            )
            val remaining = runCatching {
                JSONObject(response.body).optJSONObject("resources")?.optJSONObject("core")?.optInt("remaining", -1)
            }.getOrNull()
            GatewayHealth(
                state = GatewayHealthState.HEALTHY,
                detail = if (remaining != null && remaining >= 0) "GitHub API reachable; $remaining core requests remaining." else "GitHub API reachable.",
                toolCount = TOOL_CATALOG.size,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            GatewayHealth(
                state = if (failure is GatewayException && failure.message.orEmpty().contains("credential")) {
                    GatewayHealthState.MISCONFIGURED
                } else {
                    GatewayHealthState.UNREACHABLE
                },
                detail = GatewayLimits.redact(failure.message ?: "GitHub API unreachable.").take(300),
                toolCount = TOOL_CATALOG.size,
            )
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val snapshot = synchronized(activeCalls) { activeCalls.toList() }
        snapshot.forEach { call -> runCatching { call.cancel() } }
        synchronized(activeCalls) { activeCalls.clear() }
        ownedDispatcher?.executorService?.shutdown()
    }

    // ------------------------------------------------------------------ dispatch

    private suspend fun dispatch(name: String, args: JSONObject): String = when (name) {
        TOOL_REPOSITORY -> {
            val (owner, repo) = requireRepo(args)
            request("GET", listOf("repos", owner, repo), JSON_ACCEPT).body
        }

        TOOL_BRANCHES -> {
            val (owner, repo) = requireRepo(args)
            val query = linkedMapOf("per_page" to optionalInt(args, "per_page", 1, 100, 30).toString())
            optionalString(args, "protected", 8)?.let { query["protected"] = it }
            request("GET", listOf("repos", owner, repo, "branches"), JSON_ACCEPT, query = query).body
        }

        TOOL_COMMITS -> {
            val (owner, repo) = requireRepo(args)
            val query = linkedMapOf("per_page" to optionalInt(args, "per_page", 1, 100, 20).toString())
            optionalString(args, "sha", 100)?.let { query["sha"] = it }
            optionalString(args, "path", 400)?.let { query["path"] = it }
            request("GET", listOf("repos", owner, repo, "commits"), JSON_ACCEPT, query = query).body
        }

        TOOL_CONTENTS -> {
            val (owner, repo) = requireRepo(args)
            val path = optionalString(args, "path", 400).orEmpty().trim('/')
            validateRelativePath(path)
            val segments = buildList {
                add("repos"); add(owner); add(repo); add("contents")
                if (path.isNotEmpty()) addAll(path.split('/'))
            }
            val query = linkedMapOf<String, String>()
            optionalString(args, "ref", 200)?.let { query["ref"] = it }
            decodeFile(request("GET", segments, JSON_ACCEPT, query = query).body)
        }

        TOOL_TREE -> {
            val (owner, repo) = requireRepo(args)
            val ref = optionalString(args, "ref", 200) ?: optionalString(args, "sha", 200)
                ?: throw GatewayException("github_tree requires 'ref' (branch, tag, or commit sha).")
            validateRef(ref)
            val recursive = args.optBoolean("recursive", false)
            val query = if (recursive) linkedMapOf("recursive" to "1") else emptyMap()
            request("GET", listOf("repos", owner, repo, "git", "trees", ref), JSON_ACCEPT, query = query).body
        }

        TOOL_ISSUES -> {
            val (owner, repo) = requireRepo(args)
            val state = enumValue(args, "state", setOf("open", "closed", "all"), "open")
            val query = linkedMapOf(
                "state" to state,
                "per_page" to optionalInt(args, "per_page", 1, 100, 20).toString(),
            )
            request("GET", listOf("repos", owner, repo, "issues"), JSON_ACCEPT, query = query).body
        }

        TOOL_PULL_REQUESTS -> {
            val (owner, repo) = requireRepo(args)
            val state = enumValue(args, "state", setOf("open", "closed", "all"), "open")
            val query = linkedMapOf(
                "state" to state,
                "per_page" to optionalInt(args, "per_page", 1, 100, 20).toString(),
            )
            request("GET", listOf("repos", owner, repo, "pulls"), JSON_ACCEPT, query = query).body
        }

        TOOL_DIFF -> {
            val (owner, repo) = requireRepo(args)
            val number = requireInt(args, "number", 1, 1_000_000)
            request(
                method = "GET",
                segments = listOf("repos", owner, repo, "pulls", number.toString()),
                accept = DIFF_ACCEPT,
                maxBytes = MAX_DIFF_BYTES,
            ).body
        }

        TOOL_CREATE_ISSUE -> {
            val (owner, repo) = requireRepo(args)
            val title = requireString(args, "title", 256)
            val body = JSONObject().put("title", title)
            optionalString(args, "body", MAX_BODY_CHARS)?.let { body.put("body", it) }
            optionalStringArray(args, "labels", 20, 50)?.let { body.put("labels", it) }
            request("POST", listOf("repos", owner, repo, "issues"), JSON_ACCEPT, payload = body).body
        }

        TOOL_COMMENT -> {
            val (owner, repo) = requireRepo(args)
            val number = requireInt(args, "number", 1, 1_000_000)
            val body = requireString(args, "body", MAX_BODY_CHARS)
            request(
                "POST",
                listOf("repos", owner, repo, "issues", number.toString(), "comments"),
                JSON_ACCEPT,
                payload = JSONObject().put("body", body),
            ).body
        }

        TOOL_CREATE_PULL_REQUEST -> {
            val (owner, repo) = requireRepo(args)
            val title = requireString(args, "title", 256)
            val head = requireString(args, "head", 200)
            val baseBranch = requireString(args, "base", 200)
            validateRef(head)
            validateRef(baseBranch)
            if (head == baseBranch) throw GatewayException("head and base must differ.")
            val payload = JSONObject()
                .put("title", title)
                .put("head", head)
                .put("base", baseBranch)
            optionalString(args, "body", MAX_BODY_CHARS)?.let { payload.put("body", it) }
            if (args.has("draft")) payload.put("draft", args.optBoolean("draft", false))
            // No force, merge, or branch-deletion flags are accepted here by design.
            request("POST", listOf("repos", owner, repo, "pulls"), JSON_ACCEPT, payload = payload).body
        }

        else -> throw GatewayException("Unknown GitHub tool '$name'.")
    }

    // ------------------------------------------------------------------ transport

    private suspend fun request(
        method: String,
        segments: List<String>,
        accept: String,
        query: Map<String, String> = emptyMap(),
        payload: JSONObject? = null,
        maxBytes: Int = GatewayLimits.MAX_BODY_BYTES,
    ): GitHubResponse {
        if (closed) throw GatewayException("GitHub gateway is closed.")
        val token = resolveToken() ?: throw GatewayException("GitHub credential is not configured; refusing to call the API.")
        val url = buildUrl(segments, query)
        val builder = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)
            .header("Authorization", "Bearer $token")
        val body = payload?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        val request = if (method == "POST") {
            builder.post(body ?: "{}".toRequestBody(JSON_MEDIA_TYPE)).build()
        } else {
            builder.get().build()
        }
        val call = httpClient.newCall(request)
        activeCalls.add(call)
        try {
            // Cancellation must close the socket even while a large diff body is being read.
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { call.cancel() } }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (!continuation.isActive) return
                        continuation.resumeWith(
                            Result.failure(
                                GatewayException(
                                    "GitHub transport failed: " +
                                        GatewayLimits.redact(e.message ?: "I/O error", listOf(token)),
                                    e,
                                ),
                            ),
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val outcome: Result<GitHubResponse> = try {
                            Result.success(
                                response.use { received ->
                                    val text = readBody(received, maxBytes)
                                    val status = received.code
                                    if (status !in 200..299) throw apiFailure(status, text, token)
                                    GitHubResponse(status, received.header("Content-Type"), text)
                                },
                            )
                        } catch (failure: Throwable) {
                            Result.failure(failure)
                        }
                        if (continuation.isActive) continuation.resumeWith(outcome)
                    }
                })
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    /** Blocking, size-bounded read performed on OkHttp's callback thread. */
    private fun readBody(response: Response, maxBytes: Int): String {
        val source = response.body?.byteStream() ?: return ""
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        var truncated = false
        while (true) {
            val read = source.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) {
                val allowed = (maxBytes - buffer.size()).coerceAtLeast(0)
                if (allowed > 0) buffer.write(chunk, 0, minOf(read, allowed))
                truncated = true
                break
            }
            buffer.write(chunk, 0, read)
        }
        val text = buffer.toByteArray().toString(Charsets.UTF_8)
        return if (truncated) text + GatewayToolResult.TRUNCATION_MARKER else text
    }

    /**
     * GitHub returns file bodies base64-encoded with newlines. Decoding text files here keeps the
     * tool genuinely useful for browsing while leaving binary payloads untouched.
     */
    private fun decodeFile(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body
        if (!json.optString("type").equals("file", ignoreCase = true)) return body
        if (!json.optString("encoding").equals("base64", ignoreCase = true)) return body
        val encoded = json.optString("content").filterNot { it == '\n' || it == '\r' }
        if (encoded.isBlank()) return body
        val bytes = runCatching { Base64.getMimeDecoder().decode(encoded) }.getOrNull() ?: return body
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return body
        val text = GatewayLimits.truncate(bytes.toString(Charsets.UTF_8), MAX_FILE_CHARS)
        return JSONObject(body)
            .put("content", text)
            .put("encoding", "utf-8")
            .put("decoded", true)
            .toString()
    }

    private fun apiFailure(status: Int, body: String, token: String): GatewayException {
        val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
        val detail = GatewayLimits.redact(message.ifBlank { body }, listOf(token))
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
        val reason = when (status) {
            401 -> "GitHub rejected the credential (HTTP 401)."
            403 -> "GitHub refused the request (HTTP 403), possibly a scope or rate limit."
            404 -> "GitHub resource not found (HTTP 404)."
            422 -> "GitHub rejected the payload (HTTP 422)."
            429 -> "GitHub rate limited the request (HTTP 429)."
            else -> "GitHub API error (HTTP $status)."
        }
        return GatewayException(if (detail.isEmpty()) reason else "$reason $detail")
    }

    private fun buildUrl(segments: List<String>, query: Map<String, String>): HttpUrl {
        val builder = base.newBuilder()
        for (segment in segments) {
            if (segment.isBlank()) throw GatewayException("GitHub request path contains an empty segment.")
            builder.addPathSegment(segment)
        }
        for ((key, value) in query) builder.addQueryParameter(key, value)
        return builder.build()
    }

    private suspend fun resolveToken(): String? {
        val reference = config.credentialRef?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { tokenProvider(reference) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------ argument helpers

    private fun requireRepo(args: JSONObject): Pair<String, String> {
        val owner = requireString(args, "owner", 100)
        val repo = requireString(args, "repo", 100)
        if (!OWNER_PATTERN.matches(owner)) throw GatewayException("'owner' contains unsupported characters.")
        if (!REPO_PATTERN.matches(repo)) throw GatewayException("'repo' contains unsupported characters.")
        return owner to repo
    }

    private fun requireString(args: JSONObject, key: String, maxChars: Int): String {
        val value = args.optString(key).trim()
        if (value.isEmpty()) throw GatewayException("'$key' is required.")
        if (value.length > maxChars) throw GatewayException("'$key' exceeds $maxChars characters.")
        return value
    }

    private fun optionalString(args: JSONObject, key: String, maxChars: Int): String? {
        val value = args.optString(key).trim()
        if (value.isEmpty()) return null
        if (value.length > maxChars) throw GatewayException("'$key' exceeds $maxChars characters.")
        return value
    }

    private fun requireInt(args: JSONObject, key: String, min: Int, max: Int): Int {
        if (!args.has(key)) throw GatewayException("'$key' is required.")
        val value = args.optInt(key, Int.MIN_VALUE)
        if (value !in min..max) throw GatewayException("'$key' must be in $min..$max.")
        return value
    }

    private fun optionalInt(args: JSONObject, key: String, min: Int, max: Int, fallback: Int): Int {
        if (!args.has(key)) return fallback
        val value = args.optInt(key, Int.MIN_VALUE)
        if (value !in min..max) throw GatewayException("'$key' must be in $min..$max.")
        return value
    }

    private fun optionalStringArray(args: JSONObject, key: String, maxItems: Int, maxLength: Int): JSONArray? {
        val array = args.optJSONArray(key) ?: return null
        if (array.length() > maxItems) throw GatewayException("'$key' must contain at most $maxItems entries.")
        val out = JSONArray()
        for (index in 0 until array.length()) {
            val value = array.optString(index).trim()
            if (value.isEmpty() || value.length > maxLength) throw GatewayException("'$key' entries must be 1..$maxLength characters.")
            out.put(value)
        }
        return out
    }

    private fun enumValue(args: JSONObject, key: String, allowed: Set<String>, fallback: String): String {
        val value = optionalString(args, key, 16) ?: return fallback
        val normalized = value.lowercase(Locale.ROOT)
        if (normalized !in allowed) throw GatewayException("'$key' must be one of ${allowed.joinToString(", ")}.")
        return normalized
    }

    private fun validateRelativePath(path: String) {
        if (path.length > 400) throw GatewayException("'path' exceeds 400 characters.")
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.size > 64) throw GatewayException("'path' has too many segments.")
        for (segment in segments) {
            if (segment == "." || segment == "..") throw GatewayException("'path' must not contain relative segments.")
            if (segment.any { it == '\\' || it == '\u0000' || it.isISOControl() }) {
                throw GatewayException("'path' contains unsupported characters.")
            }
        }
    }

    private fun validateRef(ref: String) {
        if (!REF_PATTERN.matches(ref) || ref.contains("..")) throw GatewayException("'ref' is not a valid branch, tag, or commit reference.")
    }

    private data class GitHubResponse(val status: Int, val contentType: String?, val body: String)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.github.com"
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "NYX/0.7.5 (Android GitHub gateway)"
        const val MAX_DIFF_BYTES = 256 * 1024
        const val MAX_BODY_CHARS = 60_000
        const val MAX_FILE_CHARS = 64 * 1024

        const val TOOL_REPOSITORY = "github_repository"
        const val TOOL_BRANCHES = "github_branches"
        const val TOOL_COMMITS = "github_commits"
        const val TOOL_CONTENTS = "github_contents"
        const val TOOL_TREE = "github_tree"
        const val TOOL_ISSUES = "github_issues"
        const val TOOL_PULL_REQUESTS = "github_pull_requests"
        const val TOOL_DIFF = "github_diff"
        const val TOOL_CREATE_ISSUE = "github_create_issue"
        const val TOOL_COMMENT = "github_comment"
        const val TOOL_CREATE_PULL_REQUEST = "github_create_pull_request"

        val READ_TOOLS: Set<String> = setOf(
            TOOL_REPOSITORY, TOOL_BRANCHES, TOOL_COMMITS, TOOL_CONTENTS,
            TOOL_TREE, TOOL_ISSUES, TOOL_PULL_REQUESTS, TOOL_DIFF,
        )

        /** Separate names so approval policy can gate publishing without blocking reads. */
        val PUBLISH_TOOLS: Set<String> = setOf(TOOL_CREATE_ISSUE, TOOL_COMMENT, TOOL_CREATE_PULL_REQUEST)

        private val JSON_ACCEPT = "application/vnd.github+json"
        private val DIFF_ACCEPT = "application/vnd.github.diff"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OWNER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
        private val REPO_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
        private val REF_PATTERN = Regex("[A-Za-z0-9._/\\-]{1,200}")

        val TOOL_CATALOG: List<GatewayTool> = listOf(
            tool(
                TOOL_REPOSITORY,
                "Read repository metadata: default branch, description, visibility, stars, open issue counts.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner (user or organization)."))
                        .put("repo", prop("string", "Repository name.")),
                    required = listOf("owner", "repo"),
                ),
            ),
            tool(
                TOOL_BRANCHES,
                "List branches of a repository.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("per_page", prop("integer", "Results per page (1-100).", minimum = 1, maximum = 100))
                        .put("protected", prop("string", "Filter by protection state: true or false.")),
                    required = listOf("owner", "repo"),
                ),
            ),
            tool(
                TOOL_COMMITS,
                "List commits, optionally scoped to a branch/sha and a path.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("sha", prop("string", "Branch name or commit SHA."))
                        .put("path", prop("string", "Only commits touching this path."))
                        .put("per_page", prop("integer", "Results per page (1-100).", minimum = 1, maximum = 100)),
                    required = listOf("owner", "repo"),
                ),
            ),
            tool(
                TOOL_CONTENTS,
                "Read a file's metadata/content or list a directory at a ref (GitHub contents API).",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("path", prop("string", "Repository-relative path; omit for the root."))
                        .put("ref", prop("string", "Branch, tag, or commit SHA.")),
                    required = listOf("owner", "repo"),
                ),
            ),
            tool(
                TOOL_TREE,
                "Read the full recursive git tree of a ref (paths, modes, blob sizes).",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("ref", prop("string", "Branch, tag, or commit SHA."))
                        .put("recursive", prop("boolean", "Return the entire tree recursively.")),
                    required = listOf("owner", "repo", "ref"),
                ),
            ),
            tool(
                TOOL_ISSUES,
                "List issues (pull requests are included by the GitHub API unless filtered).",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("state", prop("string", "Issue state.", enumValues = listOf("open", "closed", "all")))
                        .put("per_page", prop("integer", "Results per page (1-100).", minimum = 1, maximum = 100)),
                    required = listOf("owner", "repo"),
                ),
            ),
            tool(
                TOOL_PULL_REQUESTS,
                "List pull requests.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("state", prop("string", "Pull request state.", enumValues = listOf("open", "closed", "all")))
                        .put("per_page", prop("integer", "Results per page (1-100).", minimum = 1, maximum = 100)),
                    required = listOf("owner", "repo"),
                ),
            ),
            tool(
                TOOL_DIFF,
                "Read the unified diff of a pull request; the response is capped at ${MAX_DIFF_BYTES / 1024} KB.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("number", prop("integer", "Pull request number.", minimum = 1)),
                    required = listOf("owner", "repo", "number"),
                ),
            ),
            tool(
                TOOL_CREATE_ISSUE,
                "Publish: create an issue. Requires an approved write credential.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("title", prop("string", "Issue title."))
                        .put("body", prop("string", "Issue body in GitHub-flavoured Markdown."))
                        .put("labels", prop("array", "Labels to apply.", typeItems = "string")),
                    required = listOf("owner", "repo", "title"),
                ),
            ),
            tool(
                TOOL_COMMENT,
                "Publish: comment on an issue or pull request. Requires an approved write credential.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("number", prop("integer", "Issue or pull request number.", minimum = 1))
                        .put("body", prop("string", "Comment body in GitHub-flavoured Markdown.")),
                    required = listOf("owner", "repo", "number", "body"),
                ),
            ),
            tool(
                TOOL_CREATE_PULL_REQUEST,
                "Publish: open a pull request from an existing branch. Never force pushes or deletes.",
                schema(
                    props = JSONObject()
                        .put("owner", prop("string", "Repository owner."))
                        .put("repo", prop("string", "Repository name."))
                        .put("title", prop("string", "Pull request title."))
                        .put("head", prop("string", "Source branch or owner:branch."))
                        .put("base", prop("string", "Target branch, for example main."))
                        .put("body", prop("string", "Pull request description."))
                        .put("draft", prop("boolean", "Open as a draft.")),
                    required = listOf("owner", "repo", "title", "head", "base"),
                ),
            ),
        )

        private fun tool(name: String, description: String, inputSchemaJson: String): GatewayTool =
            GatewayTool(name, description, inputSchemaJson)

        private fun schema(props: JSONObject, required: List<String>): String = JSONObject()
            .put("type", "object")
            .put("properties", props)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
            .toString()

        private fun prop(
            type: String,
            description: String,
            minimum: Int? = null,
            maximum: Int? = null,
            enumValues: List<String>? = null,
            typeItems: String? = null,
        ): JSONObject = JSONObject().apply {
            put("type", type)
            put("description", description)
            if (minimum != null) put("minimum", minimum)
            if (maximum != null) put("maximum", maximum)
            if (enumValues != null) put("enum", JSONArray(enumValues))
            if (typeItems != null) put("items", JSONObject().put("type", typeItems))
        }

        private fun runValidatedBase(baseUrl: String): HttpUrl {
            val normalized = McpEndpointPolicy.validate(baseUrl.trimEnd('/'))
            return normalized.trimEnd('/').toHttpUrlOrNull()
                ?: throw GatewayException("GitHub base URL is not a valid HTTP URL.")
        }

        fun defaultClient(timeoutSeconds: Int, dispatcher: Dispatcher): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .connectTimeout(minOf(10, timeoutSeconds).toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds.toLong() + 5, TimeUnit.SECONDS)
            .build()
    }
}
