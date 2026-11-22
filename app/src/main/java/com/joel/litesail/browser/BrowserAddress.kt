package com.joel.litesail.browser

import java.net.URI
import java.net.URLEncoder

internal object BrowserAddress {
    const val HOME = "https://www.bing.com/"
    const val BING_SEARCH_URL_PREFIX = "https://www.bing.com/search?q="
    private val scheme = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val hostWithPort = Regex("^[^/:?#\\s]+:[0-9]+(?:[/?#]|$)")

    fun resolve(input: String, searchUrlPrefix: String = "https://www.baidu.com/s?wd="): String? {
        val value = input.trim()
        if (value.isEmpty()) return null

        val explicitScheme = explicitScheme(value)
        return webUrl(value, explicitScheme)
            ?: if (acceptsBareAddress(value, explicitScheme)) searchUrl(value, searchUrlPrefix) else null
    }

    fun scannedWebUrl(input: String): String? {
        val value = input.trim()
        return webUrl(value, explicitScheme(value))
    }

    fun bingSearchUrl(input: String): String = searchUrl(input.trim(), BING_SEARCH_URL_PREFIX)

    fun isWebScheme(scheme: String?): Boolean = scheme == "http" || scheme == "https"

    fun isWebUrl(url: String): Boolean = isWebScheme(parseWebUrl(url)?.scheme?.lowercase())

    private fun explicitScheme(value: String): String? = scheme.find(value)?.value?.dropLast(1)?.lowercase()

    private fun webUrl(value: String, explicitScheme: String?): String? = when {
        explicitScheme != null && isWebScheme(explicitScheme) ->
            parseWebUrl(explicitScheme + value.substring(explicitScheme.length))?.toASCIIString()
        acceptsBareAddress(value, explicitScheme) -> bareWebUrl(value)
        else -> null
    }

    private fun acceptsBareAddress(value: String, explicitScheme: String?): Boolean =
        explicitScheme == null || (!isWebScheme(explicitScheme) && hostWithPort.containsMatchIn(value))

    private fun bareWebUrl(value: String): String? {
        val candidate = parseWebUrl("https://$value") ?: return null
        val host = candidate.host
        return if (host.equals("localhost", ignoreCase = true) || '.' in host || host.startsWith('[')) {
            candidate.toASCIIString()
        } else {
            null
        }
    }

    private fun searchUrl(value: String, prefix: String): String = prefix + URLEncoder.encode(value, "UTF-8")

    private fun parseWebUrl(value: String): URI? =
        runCatching { URI(value) }.getOrNull()?.takeIf {
            !it.host.isNullOrEmpty() && it.rawUserInfo == null && it.port in -1..65535
        }
}
