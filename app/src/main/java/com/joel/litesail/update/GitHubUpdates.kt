package com.joel.litesail.update

import org.json.JSONArray
import org.json.JSONException
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private const val REPOSITORY = "DvShu/LiteSail"
private const val TAGS_URL = "https://api.github.com/repos/$REPOSITORY/tags?per_page=1"
private const val PROXY_URL = "https://gh-proxy.org/"
private const val REQUEST_TIMEOUT_MS = 4_000
private val releaseTag = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")

internal class AppUpdate private constructor(val tag: String) {
    val fileName = "LiteSail-$tag.apk"
    val downloadUrl = "${PROXY_URL}https://github.com/$REPOSITORY/releases/download/$tag/$fileName"

    companion object {
        fun fromTag(tag: String, currentVersion: String): AppUpdate? {
            if (!releaseTag.matches(tag) || tag.removePrefix("v") == currentVersion.removePrefix("v")) {
                return null
            }
            return AppUpdate(tag)
        }
    }
}

internal object GitHubUpdates {
    fun check(currentVersion: String): AppUpdate? {
        val tags = fetchTags(TAGS_URL) ?: fetchTags(PROXY_URL + TAGS_URL) ?: return null
        val tag = tags.optJSONObject(0)?.optString("name") ?: return null
        return AppUpdate.fromTag(tag, currentVersion)
    }

    private fun fetchTags(url: String): JSONArray? {
        val connection = URL(url).openConnection() as HttpsURLConnection
        return try {
            connection.connectTimeout = REQUEST_TIMEOUT_MS
            connection.readTimeout = REQUEST_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "LiteSail")
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }
        } catch (_: IOException) {
            null
        } catch (_: JSONException) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
