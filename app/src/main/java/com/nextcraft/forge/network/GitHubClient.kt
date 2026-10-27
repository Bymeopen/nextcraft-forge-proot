package com.nextcraft.forge.network

import android.content.Context
import com.nextcraft.forge.model.stringOrEmpty
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubAccount(
    val login: String,
    val avatarUrl: String,
)

data class GitHubRepository(
    val fullName: String,
    val cloneUrl: String,
    val private: Boolean,
    val defaultBranch: String,
    val description: String,
    val updatedAt: String,
)

sealed interface GitHubTokenPoll {
    data class Success(val accessToken: String) : GitHubTokenPoll
    data class Pending(val slowDown: Boolean = false) : GitHubTokenPoll
    data class Failure(val message: String, val reason: Reason = Reason.OTHER) : GitHubTokenPoll {
        enum class Reason { EXPIRED, DENIED, OTHER }
    }
}

data class GitHubWorkflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String,
)

data class GitHubWorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val branch: String,
    val event: String,
    val createdAt: String,
    val url: String,
)

data class GitHubPullRequest(
    val number: Int,
    val title: String,
    val headBranch: String,
    val baseBranch: String,
    val state: String,
    val url: String,
    val mergeable: Boolean = true,
)

class GitHubClient(@Suppress("UNUSED_PARAMETER") private val context: Context? = null) {

    fun requestDeviceCode(
        clientId: String = DEFAULT_CLIENT_ID,
        scope: String = DEFAULT_SCOPES,
    ): GitHubDeviceCode {
        val effectiveClient = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val effectiveScopes = scope.trim().ifEmpty { DEFAULT_SCOPES }
        val resp = sendFormUrlEncoded(
            uri = "https://github.com/login/device/code",
            fields = mapOf("client_id" to effectiveClient, "scope" to effectiveScopes),
        )
        return GitHubDeviceCode(
            deviceCode = resp.getString("device_code"),
            userCode = resp.getString("user_code"),
            verificationUri = resp.stringOrEmpty("verification_uri").ifBlank { "https://github.com/login/device" },
            expiresInSeconds = resp.optLong("expires_in", 900L),
            intervalSeconds = resp.optLong("interval", 5L).coerceAtLeast(5L),
        )
    }

    fun pollDeviceToken(clientId: String = DEFAULT_CLIENT_ID, deviceCode: String): GitHubTokenPoll {
        val effectiveClient = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val resp = sendFormUrlEncoded(
            uri = "https://github.com/login/oauth/access_token",
            fields = mapOf(
                "client_id" to effectiveClient,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        val grantedToken = resp.stringOrEmpty("access_token")
        if (grantedToken.isNotBlank()) return GitHubTokenPoll.Success(grantedToken)

        return when (val errCode = resp.stringOrEmpty("error")) {
            "authorization_pending" -> GitHubTokenPoll.Pending(slowDown = false)
            "slow_down" -> GitHubTokenPoll.Pending(slowDown = true)
            "access_denied" -> GitHubTokenPoll.Failure(
                "GitHub authorization was cancelled",
                GitHubTokenPoll.Failure.Reason.DENIED,
            )
            "expired_token" -> GitHubTokenPoll.Failure(
                "The GitHub sign-in code expired. Try again.",
                GitHubTokenPoll.Failure.Reason.EXPIRED,
            )
            else -> GitHubTokenPoll.Failure(
                resp.stringOrEmpty("error_description").ifBlank { errCode.ifBlank { "GitHub sign-in failed" } },
            )
        }
    }

    fun account(token: String): GitHubAccount {
        val node = executeRestJson("GET", "$API_ROOT/user", token) as? JSONObject
            ?: error("Unexpected GitHub user profile response")
        return GitHubAccount(
            login = node.getString("login"),
            avatarUrl = node.stringOrEmpty("avatar_url"),
        )
    }

    fun createRepository(
        token: String,
        name: String,
        description: String = "",
        private: Boolean = true,
        autoInit: Boolean = false,
    ): GitHubRepository {
        val repoTitle = name.trim()
        require(repoTitle.isNotEmpty()) { "Repository name must not be empty" }
        val reqBody = JSONObject()
            .put("name", repoTitle)
            .put("description", description.trim())
            .put("private", private)
            .put("auto_init", autoInit)
        val node = executeRestJson("POST", "$API_ROOT/user/repos", token, reqBody) as? JSONObject
            ?: error("Unexpected GitHub create repository response")
        return decodeRepoNode(node, defaultPrivate = private)
    }

    fun repositories(token: String): List<GitHubRepository> {
        val byFullName = LinkedHashMap<String, GitHubRepository>()
        for (pageIndex in 1..10) {
            val pageUri = "$API_ROOT/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$pageIndex"
            val pageArr = executeRestJson("GET", pageUri, token) as? JSONArray
                ?: error("Unexpected GitHub repository list response")
            for (idx in 0 until pageArr.length()) {
                val node = pageArr.optJSONObject(idx) ?: continue
                val full = node.stringOrEmpty("full_name")
                val clone = node.stringOrEmpty("clone_url")
                if (full.isNotBlank() && clone.isNotBlank()) {
                    byFullName[full] = decodeRepoNode(node, defaultPrivate = false)
                }
            }
            if (pageArr.length() < 100) break
        }
        return byFullName.values.toList()
    }

    private fun decodeRepoNode(node: JSONObject, defaultPrivate: Boolean): GitHubRepository =
        GitHubRepository(
            fullName = node.getString("full_name"),
            cloneUrl = node.getString("clone_url"),
            private = node.optBoolean("private", defaultPrivate),
            defaultBranch = node.stringOrEmpty("default_branch", "main").ifBlank { "main" },
            description = node.stringOrEmpty("description"),
            updatedAt = node.stringOrEmpty("updated_at"),
        )

    fun workflows(token: String, owner: String, repo: String): List<GitHubWorkflow> {
        val uri = "$API_ROOT/repos/${checkSlugSegment(owner)}/${checkSlugSegment(repo)}/actions/workflows?per_page=50"
        val root = executeRestJson("GET", uri, token) as? JSONObject ?: return emptyList()
        val items = root.optJSONArray("workflows") ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val wf = items.optJSONObject(i) ?: continue
                val wfId = wf.optLong("id")
                if (wfId != 0L) {
                    add(
                        GitHubWorkflow(
                            id = wfId,
                            name = wf.optString("name"),
                            path = wf.optString("path"),
                            state = wf.optString("state"),
                        ),
                    )
                }
            }
        }
    }

    fun workflowRuns(token: String, owner: String, repo: String, perPage: Int = 15): List<GitHubWorkflowRun> {
        val limit = perPage.coerceIn(1, 50)
        val uri = "$API_ROOT/repos/${checkSlugSegment(owner)}/${checkSlugSegment(repo)}/actions/runs?per_page=$limit"
        val root = executeRestJson("GET", uri, token) as? JSONObject ?: return emptyList()
        val items = root.optJSONArray("workflow_runs") ?: return emptyList()
        return buildList(items.length()) {
            for (i in 0 until items.length()) {
                val runNode = items.optJSONObject(i) ?: continue
                val rawConclusion = runNode.optString("conclusion")
                add(
                    GitHubWorkflowRun(
                        id = runNode.optLong("id"),
                        name = runNode.optString("name").ifBlank { runNode.optString("display_title") },
                        status = runNode.optString("status"),
                        conclusion = rawConclusion.takeIf { it.isNotBlank() && it != "null" },
                        branch = runNode.optString("head_branch"),
                        event = runNode.optString("event"),
                        createdAt = runNode.optString("created_at"),
                        url = runNode.optString("html_url"),
                    ),
                )
            }
        }
    }

    fun dispatchWorkflow(token: String, owner: String, repo: String, workflowId: Long, ref: String) {
        require(workflowId > 0L) { "Invalid workflow" }
        require(BRANCH_REF_PATTERN.matches(ref)) { "Invalid branch name" }
        val uri = "$API_ROOT/repos/${checkSlugSegment(owner)}/${checkSlugSegment(repo)}/actions/workflows/$workflowId/dispatches"
        executeRestJson("POST", uri, token, JSONObject().put("ref", ref))
    }

    fun pullRequests(
        token: String,
        owner: String,
        repo: String,
        state: String = "open",
        perPage: Int = 15,
    ): List<GitHubPullRequest> {
        val encodedState = URLEncoder.encode(state, "UTF-8")
        val limit = perPage.coerceIn(1, 50)
        val uri = "$API_ROOT/repos/${checkSlugSegment(owner)}/${checkSlugSegment(repo)}/pulls?state=$encodedState&per_page=$limit"
        val items = executeRestJson("GET", uri, token) as? JSONArray ?: return emptyList()
        return buildList {
            for (i in 0 until items.length()) {
                val prNode = items.optJSONObject(i) ?: continue
                val prNum = prNode.optInt("number")
                if (prNum <= 0) continue
                val headRef = prNode.optJSONObject("head")?.optString("ref").orEmpty()
                val baseRef = prNode.optJSONObject("base")?.optString("ref").orEmpty().ifBlank { "main" }
                val mState = prNode.optString("mergeable_state", "")
                val canMerge = if (prNode.isNull("mergeable")) {
                    mState != "dirty" && mState != "conflicting"
                } else {
                    prNode.optBoolean("mergeable", true)
                }
                add(
                    GitHubPullRequest(
                        number = prNum,
                        title = prNode.optString("title"),
                        headBranch = headRef,
                        baseBranch = baseRef,
                        state = prNode.optString("state", "open"),
                        url = prNode.optString("html_url"),
                        mergeable = canMerge,
                    ),
                )
            }
        }
    }

    fun createPullRequest(
        token: String,
        owner: String,
        repo: String,
        title: String,
        head: String,
        base: String = "main",
        body: String = "",
    ): GitHubPullRequest {
        val headRef = head.trim()
        val baseRef = base.trim().ifBlank { "main" }
        require(headRef.isNotEmpty() && headRef != baseRef) {
            "Head branch ($headRef) must differ from base branch ($baseRef)"
        }
        val reqBody = JSONObject()
            .put("title", title.trim())
            .put("head", headRef)
            .put("base", baseRef)
            .put("body", body.trim())
        val uri = "$API_ROOT/repos/${checkSlugSegment(owner)}/${checkSlugSegment(repo)}/pulls"
        val prNode = executeRestJson("POST", uri, token, reqBody) as? JSONObject
            ?: error("Unexpected GitHub create PR response")
        return GitHubPullRequest(
            number = prNode.optInt("number"),
            title = prNode.optString("title"),
            headBranch = prNode.optJSONObject("head")?.optString("ref").orEmpty().ifBlank { head },
            baseBranch = prNode.optJSONObject("base")?.optString("ref").orEmpty().ifBlank { base },
            state = prNode.optString("state", "open"),
            url = prNode.optString("html_url"),
        )
    }

    fun mergePullRequest(
        token: String,
        owner: String,
        repo: String,
        number: Int,
        mergeMethod: String = "squash",
    ) {
        require(number > 0) { "Invalid PR number" }
        val uri = "$API_ROOT/repos/${checkSlugSegment(owner)}/${checkSlugSegment(repo)}/pulls/$number/merge"
        executeRestJson("PUT", uri, token, JSONObject().put("merge_method", mergeMethod))
    }

    private fun sendFormUrlEncoded(uri: String, fields: Map<String, String>): JSONObject {
        val encodedForm = fields.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }.encodeToByteArray()

        val conn = (URL(uri).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            conn.outputStream.use { it.write(encodedForm) }
            decodeJsonResponse(conn) as? JSONObject ?: error("Unexpected GitHub OAuth response")
        } finally {
            conn.disconnect()
        }
    }

    private fun executeRestJson(
        verb: String,
        uri: String,
        bearerToken: String,
        jsonPayload: JSONObject? = null,
    ): Any {
        val conn = (URL(uri).openConnection() as HttpURLConnection).apply {
            requestMethod = verb
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            setRequestProperty("User-Agent", USER_AGENT)
            if (jsonPayload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (jsonPayload != null) {
                val rawBytes = jsonPayload.toString().encodeToByteArray()
                conn.outputStream.use { it.write(rawBytes) }
            }
            decodeJsonResponse(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeJsonResponse(conn: HttpURLConnection): Any {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bodyStr = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val sb = StringBuilder()
            val buf = CharArray(8 * 1024)
            while (sb.length < RESPONSE_CHAR_CAP) {
                val maxRead = minOf(buf.size, RESPONSE_CHAR_CAP - sb.length)
                val n = reader.read(buf, 0, maxRead)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }.orEmpty()

        if (code !in 200..299) {
            val apiError = runCatching { JSONObject(bodyStr).stringOrEmpty("message") }.getOrNull()
            error(apiError?.takeIf { it.isNotBlank() } ?: "GitHub returned HTTP $code")
        }
        if (bodyStr.isBlank()) return JSONObject()
        return if (bodyStr.trimStart().startsWith("[")) JSONArray(bodyStr) else JSONObject(bodyStr)
    }

    private fun checkSlugSegment(segment: String): String {
        require(REPO_SEGMENT_PATTERN.matches(segment)) { "Invalid repository name" }
        return segment
    }

    companion object {
        const val DEFAULT_CLIENT_ID = "178c6fc778ccc68e1d6a"
        const val DEFAULT_SCOPES = "repo read:user user:email workflow"
        const val USER_AGENT = "NextCraft-Forge-Android/1.0"
        private const val API_ROOT = "https://api.github.com"
        private const val GITHUB_API_VERSION = "2022-11-28"
        private const val RESPONSE_CHAR_CAP = 4 * 1024 * 1024
        private val REPO_SEGMENT_PATTERN = Regex("[A-Za-z0-9._-]{1,100}")
        private val BRANCH_REF_PATTERN = Regex("[A-Za-z0-9._/-]{1,200}")
    }
}
