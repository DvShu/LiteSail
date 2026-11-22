package com.joel.litesail.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

internal class BrowserRequestFilter(adBlockingEnabled: Boolean) {
    @Volatile
    var adBlockingEnabled = adBlockingEnabled

    fun blocksNavigation(scheme: String?): Boolean = !BrowserAddress.isWebScheme(scheme)

    fun blocksResource(scheme: String?, host: String?, isMainFrame: Boolean): Boolean =
        adBlockingEnabled && !isMainFrame && BrowserAddress.isWebScheme(scheme) &&
            host?.removeSuffix(".")?.lowercase() in adHosts

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (!blocksResource(url.scheme, url.host, request.isForMainFrame)) return null

        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            blockedHeaders,
            ByteArrayInputStream(emptyBody)
        )
    }

    private companion object {
        val blockedHeaders = mapOf("Cache-Control" to "no-store")
        val emptyBody = ByteArray(0)

        // Selected exact hosts from AdAway. Attribution is in assets/licenses/adaway-notice.txt.
        val adHosts = setOf(
            "acdn.adnxs.com",
            "b.adnxs.com",
            "cdn.adnxs.com",
            "dcdn.adnxs.com",
            "ib.adnxs.com",
            "m.adnxs.com",
            "mediation.adnxs.com",
            "p.adnxs.com",
            "prebid.adnxs.com",
            "secure.adnxs.com",
            "vcdn.adnxs.com",
            "doubleclick.net",
            "ad.doubleclick.net",
            "ad-emea.doubleclick.net",
            "g.doubleclick.net",
            "adx.g.doubleclick.net",
            "bid.g.doubleclick.net",
            "googleads.g.doubleclick.net",
            "googleads4.g.doubleclick.net",
            "pubads.g.doubleclick.net",
            "securepubads.g.doubleclick.net",
            "static.doubleclick.net",
            "adservice.google.com",
            "googleadservices.com",
            "pagead2.googleadservices.com",
            "www.googleadservices.com",
            "static.googleadsserving.cn",
            "googlesyndication.com",
            "ade.googlesyndication.com",
            "pagead2.googlesyndication.com",
            "tpc.googlesyndication.com",
            "displayads-formats.googleusercontent.com",
            "sdk.e.qq.com",
            "mi.gdt.qq.com",
            "v.gdt.qq.com"
        )
    }
}
