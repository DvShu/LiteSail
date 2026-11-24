package com.joel.litesail.update

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val REPOSITORY = "DvShu/LiteSail"
private const val REPOSITORY_API_URL = "https://api.github.com/repos/$REPOSITORY"
private const val PROXY_URL = "https://gh-proxy.org/"
private const val REQUEST_TIMEOUT_MS = 4_000
private val releaseTag = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")

internal class AppUpdate private constructor(tag: String, val releaseNotes: String?) {
    val versionName = tag.removePrefix("v")
    val fileName = "LiteSail-$tag.apk"
    val downloadUrl = "${PROXY_URL}https://github.com/$REPOSITORY/releases/download/$tag/$fileName"

    companion object {
        fun fromTag(
            tag: String,
            currentVersion: String,
            loadReleaseNotes: (String) -> String? = { null }
        ): AppUpdate? {
            if (!releaseTag.matches(tag) || tag.removePrefix("v") == currentVersion.removePrefix("v")) {
                return null
            }
            return AppUpdate(tag, loadReleaseNotes(tag))
        }
    }
}

internal object GitHubUpdates {
    fun check(currentVersion: String): AppUpdate? {
        val tags = fetchJson("$REPOSITORY_API_URL/tags?per_page=1", ::JSONArray) ?: return null
        val tag = tags.optJSONObject(0)?.optString("name") ?: return null
        return AppUpdate.fromTag(tag, currentVersion, ::fetchReleaseNotes)
    }

    private fun fetchReleaseNotes(tag: String): String? {
        val release = fetchJson("$REPOSITORY_API_URL/releases/tags/$tag", ::JSONObject) ?: return null
        if (release.optString("tag_name") != tag) return null
        return (release.opt("body") as? String)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun <T> fetchJson(url: String, parse: (String) -> T): T? =
        fetchJsonResponse(url, parse) ?: fetchJsonResponse(PROXY_URL + url, parse)

    private fun <T> fetchJsonResponse(url: String, parse: (String) -> T): T? {
        val connection = URL(url).openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "LiteSail")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { parse(it.readText()) }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
