package com.joel.litesail

import java.net.URI
import java.net.URLEncoder

internal object BrowserAddress {
    const val HOME = "https://www.baidu.com/"
    const val BING_SEARCH_URL_PREFIX = "https://www.bing.com/search?q="
    private val scheme = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)
    private val hostWithPort = Regex("^[^/:?#\\s]+:[0-9]+(?:[/?#]|$)")

    fun resolve(input: String, searchUrlPrefix: String = "https://www.baidu.com/s?wd="): String? {
        val value = input.trim()
        if (value.isEmpty()) return null

        val explicitScheme = scheme.find(value)?.value?.dropLast(1)
        val normalizedScheme = explicitScheme?.lowercase()
        if (normalizedScheme == "http" || normalizedScheme == "https") {
            return parseWebUrl(normalizedScheme + value.substring(normalizedScheme.length))
                ?.toASCIIString()
        }
        if (explicitScheme != null && !hostWithPort.containsMatchIn(value)) return null

        val candidate = parseWebUrl("https://$value")
        val host = candidate?.host.orEmpty()
        if (host.equals("localhost", ignoreCase = true) || '.' in host || host.startsWith('[')) {
            return candidate?.toASCIIString()
        }
        return searchUrlPrefix + URLEncoder.encode(value, "UTF-8")
    }

    fun isWebScheme(scheme: String?): Boolean = scheme == "http" || scheme == "https"

    private fun parseWebUrl(value: String): URI? =
        runCatching { URI(value) }.getOrNull()?.takeIf {
            !it.host.isNullOrEmpty() && it.rawUserInfo == null && it.port in -1..65535
        }
}
