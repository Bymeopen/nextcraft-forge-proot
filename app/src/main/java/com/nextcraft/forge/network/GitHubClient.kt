package com.nextcraft.forge.network

import android.content.Context
import com.nextcraft.forge.model.stringOrEmpty
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Long,
    val intervalSeconds: Long,
)

data class GitHubAccount(val login: String, val avatarUrl: String)

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

data class GitHubWorkflow(val id: Long, val name: String, val path: String, val state: String)

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
        val resolvedClientId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val resolvedScope = scope.trim().ifEmpty { DEFAULT_SCOPES }
        val json = postForm(
            "https://github.com/login/device/code",
            mapOf(
                "client_id" to resolvedClientId,
                "scope" to resolvedScope,
            ),
        )
        return GitHubDeviceCode(
            deviceCode = json.getString("device_code"),
            userCode = json.getString("user_code"),
            verificationUri = json.stringOrEmpty("verification_uri").ifBlank { "https://github.com/login/device" },
            expiresInSeconds = json.optLong("expires_in", 900L),
            intervalSeconds = json.optLong("interval", 5L).coerceAtLeast(5L),
        )
    }

    fun pollDeviceToken(clientId: String = DEFAULT_CLIENT_ID, deviceCode: String): GitHubTokenPoll {
        val resolvedClientId = clientId.trim().ifEmpty { DEFAULT_CLIENT_ID }
        val json = postForm(
            "https://github.com/login/oauth/access_token",
            mapOf(
                "client_id" to resolvedClientId,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        json.stringOrEmpty("access_token").takeIf(String::isNotBlank)?.let { return GitHubTokenPoll.Success(it) }
        return when (val error = json.stringOrEmpty("error")) {
            "authorization_pending" -> GitHubTokenPoll.Pending()
            "slow_down" -> GitHubTokenPoll.Pending(slowDown = true)
            "access_denied" -> GitHubTokenPoll.Failure("GitHub authorization was cancelled", GitHubTokenPoll.Failure.Reason.DENIED)
            "expired_token" -> GitHubTokenPoll.Failure("The GitHub sign-in code expired. Try again.", GitHubTokenPoll.Failure.Reason.EXPIRED)
            else -> GitHubTokenPoll.Failure(
                json.stringOrEmpty("error_description").ifBlank {
                    error.ifBlank { "GitHub sign-in failed" }
                },
            )
        }
    }

    fun account(token: String): GitHubAccount {
        val json = getJson("https://api.github.com/user", token) as? JSONObject
            ?: error("Unexpected GitHub user profile response")
        return GitHubAccount(json.getString("login"), json.stringOrEmpty("avatar_url"))
    }

    fun createRepository(
        token: String,
        name: String,
        description: String = "",
        private: Boolean = true,
        autoInit: Boolean = false,
    ): GitHubRepository {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Repository name must not be empty" }
        val payload = JSONObject()
            .put("name", cleanName)
            .put("description", description.trim())
            .put("private", private)
            .put("auto_init", autoInit)
        val item = postJson("https://api.github.com/user/repos", token, payload) as? JSONObject
            ?: error("Unexpected GitHub create repository response")
        return GitHubRepository(
            fullName = item.getString("full_name"),
            cloneUrl = item.getString("clone_url"),
            private = item.optBoolean("private", private),
            defaultBranch = item.stringOrEmpty("default_branch", "main").ifBlank { "main" },
            description = item.stringOrEmpty("description"),
            updatedAt = item.stringOrEmpty("updated_at"),
        )
    }

    fun repositories(token: String): List<GitHubRepository> {
        val result = LinkedHashMap<String, GitHubRepository>()
        var page = 1
        while (page <= 10) {
            val array = getJson(
                "https://api.github.com/user/repos?visibility=all&affiliation=owner,collaborator,organization_member&sort=updated&per_page=100&page=$page",
                token,
            ) as? JSONArray ?: error("Unexpected GitHub repository list response")
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val fullName = item.stringOrEmpty("full_name")
                val cloneUrl = item.stringOrEmpty("clone_url")
                if (fullName.isBlank() || cloneUrl.isBlank()) continue
                val repo = GitHubRepository(
                    fullName = fullName,
                    cloneUrl = cloneUrl,
                    private = item.optBoolean("private"),
                    defaultBranch = item.stringOrEmpty("default_branch", "main").ifBlank { "main" },
                    description = item.stringOrEmpty("description"),
                    updatedAt = item.stringOrEmpty("updated_at"),
                )
                result[repo.fullName] = repo
            }
            if (array.length() < 100) break
            page++
        }
        return result.values.toList()
    }

    private fun postForm(endpoint: String, values: Map<String, String>): JSONObject {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection) as? JSONObject ?: error("Unexpected GitHub OAuth response")
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(endpoint: String, token: String, payload: JSONObject): Any {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun getJson(endpoint: String, token: String): Any {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            buildString {
                val buffer = CharArray(8 * 1024)
                while (length < MAX_GITHUB_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_GITHUB_RESPONSE_CHARS - length))
                    if (count <= 0) break
                    append(buffer, 0, count)
                }
            }
        }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).stringOrEmpty("message") }.getOrNull()
            error(message?.takeIf(String::isNotBlank) ?: "GitHub returned HTTP $status")
        }
        // 204 No Content (workflow dispatch) has no body.
        if (text.isBlank()) return JSONObject()
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    fun workflows(token: String, owner: String, repo: String): List<GitHubWorkflow> {
        val json = getJson("https://api.github.com/repos/${segment(owner)}/${segment(repo)}/actions/workflows?per_page=50", token) as? JSONObject
        val array = json?.optJSONArray("workflows") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                GitHubWorkflow(it.optLong("id"), it.optString("name"), it.optString("path"), it.optString("state"))
            }
        }.filter { it.id != 0L }
    }

    fun workflowRuns(token: String, owner: String, repo: String, perPage: Int = 15): List<GitHubWorkflowRun> {
        val json = getJson(
            "https://api.github.com/repos/${segment(owner)}/${segment(repo)}/actions/runs?per_page=${perPage.coerceIn(1, 50)}",
            token,
        ) as? JSONObject
        val array = json?.optJSONArray("workflow_runs") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let {
                GitHubWorkflowRun(
                    id = it.optLong("id"),
                    // org.json turns a JSON null into the text "null"; head_branch and name can be null.
                    name = it.stringOrEmpty("name").ifBlank { it.stringOrEmpty("display_title") },
                    status = it.optString("status"),
                    conclusion = it.optString("conclusion").takeIf { c -> c.isNotBlank() && c != "null" },
                    branch = it.stringOrEmpty("head_branch"),
                    event = it.optString("event"),
                    createdAt = it.optString("created_at"),
                    url = it.optString("html_url"),
                )
            }
        }
    }

    /** Starts a workflow that declares `workflow_dispatch`; GitHub answers 204 and the run appears a moment later. */
    fun dispatchWorkflow(token: String, owner: String, repo: String, workflowId: Long, ref: String) {
        require(workflowId > 0) { "Invalid workflow" }
        require(Regex("[A-Za-z0-9._/-]{1,200}").matches(ref)) { "Invalid branch name" }
        postJson(
            "https://api.github.com/repos/${segment(owner)}/${segment(repo)}/actions/workflows/$workflowId/dispatches",
            token,
            JSONObject().put("ref", ref),
        )
    }

    fun pullRequests(token: String, owner: String, repo: String, state: String = "open", perPage: Int = 15): List<GitHubPullRequest> {
        val json = getJson(
            "https://api.github.com/repos/${segment(owner)}/${segment(repo)}/pulls?state=${URLEncoder.encode(state, "UTF-8")}&per_page=${perPage.coerceIn(1, 50)}",
            token,
        ) as? JSONArray ?: return emptyList()
        return (0 until json.length()).mapNotNull { i ->
            json.optJSONObject(i)?.let { item ->
                val head = item.optJSONObject("head")?.optString("ref").orEmpty()
                val base = item.optJSONObject("base")?.optString("ref").orEmpty().ifBlank { "main" }
                val mergeableState = item.optString("mergeable_state", "")
                val isMergeable = if (item.isNull("mergeable")) {
                    mergeableState != "dirty" && mergeableState != "conflicting"
                } else {
                    item.optBoolean("mergeable", true)
                }
                GitHubPullRequest(
                    number = item.optInt("number"),
                    title = item.optString("title"),
                    headBranch = head,
                    baseBranch = base,
                    state = item.optString("state", "open"),
                    url = item.optString("html_url"),
                    mergeable = isMergeable,
                )
            }
        }.filter { it.number > 0 }
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
        val cleanHead = head.trim()
        val cleanBase = base.trim().ifBlank { "main" }
        require(cleanHead.isNotEmpty() && cleanHead != cleanBase) {
            "Head branch ($cleanHead) must differ from base branch ($cleanBase)"
        }
        val payload = JSONObject()
            .put("title", title.trim())
            .put("head", cleanHead)
            .put("base", cleanBase)
            .put("body", body.trim())
        val item = postJson(
            "https://api.github.com/repos/${segment(owner)}/${segment(repo)}/pulls",
            token,
            payload,
        ) as? JSONObject ?: error("Unexpected GitHub create PR response")
        return GitHubPullRequest(
            number = item.optInt("number"),
            title = item.optString("title"),
            headBranch = item.optJSONObject("head")?.optString("ref").orEmpty().ifBlank { head },
            baseBranch = item.optJSONObject("base")?.optString("ref").orEmpty().ifBlank { base },
            state = item.optString("state", "open"),
            url = item.optString("html_url"),
        )
    }

    fun mergePullRequest(token: String, owner: String, repo: String, number: Int, mergeMethod: String = "squash") {
        require(number > 0) { "Invalid PR number" }
        val payload = JSONObject().put("merge_method", mergeMethod)
        val endpoint = "https://api.github.com/repos/${segment(owner)}/${segment(repo)}/pulls/$number/merge"
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.outputStream.use { it.write(body) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun segment(value: String): String {
        require(Regex("[A-Za-z0-9._-]{1,100}").matches(value)) { "Invalid repository name" }
        return value
    }

    companion object {
        /** Default GitHub OAuth Device Flow client ID; overridable per caller. */
        const val DEFAULT_CLIENT_ID = "178c6fc778ccc68e1d6a"
        /**
         * Minimum the client uses: `repo` (private repos, PR merge) and `workflow` (workflow dispatch and pushing
         * `.github/workflows`). The profile call only reads the public login/avatar, so no user scopes.
         */
        const val DEFAULT_SCOPES = "repo workflow"
        const val USER_AGENT = "NextCraft-Forge-Android/1.0"
        private const val MAX_GITHUB_RESPONSE_CHARS = 4 * 1024 * 1024
    }
}
