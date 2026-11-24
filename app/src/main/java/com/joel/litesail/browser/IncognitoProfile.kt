package com.joel.litesail.browser

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

// The private constructor is only reached when both isolation and cleanup are supported.
@SuppressLint("RequiresFeature")
internal class IncognitoProfile private constructor() {
    private val name = PROFILE_PREFIX + UUID.randomUUID()
    private var profile: Profile? = null

    fun attach(view: WebView): Boolean = try {
        WebViewCompat.setProfile(view, name)
        if (profile == null) profile = WebViewCompat.getProfile(view)
        true
    } catch (_: IllegalStateException) {
        false
    } catch (_: UnsupportedOperationException) {
        false
    }

    fun clear() {
        val session = profile
        profile = null
        if (session == null) {
            deleteProfile(name)
            return
        }
        try {
            WebStorageCompat.deleteBrowsingData(session.webStorage) { deleteProfile(name) }
            session.geolocationPermissions.clearAll()
        } catch (_: IllegalStateException) {
            deleteProfile(name)
        } catch (_: UnsupportedOperationException) {
            deleteProfile(name)
        }
    }

    companion object {
        private const val PROFILE_PREFIX = "litesail-incognito-"
        private var startupCleanupAttempted = false

        val isSupported: Boolean by lazy {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        }

        fun create(): IncognitoProfile? {
            if (!isSupported) return null
            clearPreviousSessions()
            return IncognitoProfile()
        }

        fun clearPreviousSessions() {
            if (startupCleanupAttempted || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
            startupCleanupAttempted = true
            ProfileStore.getInstance().allProfileNames.forEach { name ->
                if (name.startsWith(PROFILE_PREFIX)) deleteProfile(name)
            }
        }

        private fun deleteProfile(name: String) {
            try {
                ProfileStore.getInstance().deleteProfile(name)
            } catch (_: IllegalStateException) {
                // A profile loaded in this process can be removed on the next cold start.
            } catch (_: UnsupportedOperationException) {
                // A WebView provider change can leave profiles for the next supported startup.
            }
        }
    }
}
