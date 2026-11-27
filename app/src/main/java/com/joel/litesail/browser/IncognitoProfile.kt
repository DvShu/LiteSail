package com.joel.litesail.browser

import android.annotation.SuppressLint
import android.content.Context
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
        private const val CLEANUP_PENDING = "cleanup_pending"
        private var previousSessionsCleanupAttempted = false
        private var cleanupMarkedForProcess = false

        val isSupported: Boolean by lazy {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) &&
                WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)
        }

        fun create(context: Context): IncognitoProfile? {
            if (!isSupported) return null
            clearPreviousSessionsIfNeeded(context)
            if (!cleanupMarkedForProcess) {
                // Persist the marker before WebView can create a profile on disk.
                if (!preferences(context).edit().putBoolean(CLEANUP_PENDING, true).commit()) return null
                cleanupMarkedForProcess = true
            }
            return IncognitoProfile()
        }

        fun clearPreviousSessionsIfNeeded(context: Context) {
            if (previousSessionsCleanupAttempted) return
            previousSessionsCleanupAttempted = true
            val preferences = preferences(context)
            val cleanupPending = if (preferences.contains(CLEANUP_PENDING)) {
                preferences.getBoolean(CLEANUP_PENDING, false)
            } else {
                // On upgrade, scan once for profiles written before we tracked pending cleanup.
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                (info.firstInstallTime != info.lastUpdateTime).also { updated ->
                    if (!updated) preferences.edit().putBoolean(CLEANUP_PENDING, false).apply()
                }
            }
            if (!cleanupPending || !WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return

            val store = ProfileStore.getInstance()
            store.allProfileNames.filter { it.startsWith(PROFILE_PREFIX) }.forEach(::deleteProfile)
            if (store.allProfileNames.none { it.startsWith(PROFILE_PREFIX) }) {
                preferences.edit().putBoolean(CLEANUP_PENDING, false).apply()
            }
        }

        private fun preferences(context: Context) =
            context.getSharedPreferences("incognito_profiles", Context.MODE_PRIVATE)

        private fun deleteProfile(name: String) {
            try {
                ProfileStore.getInstance().deleteProfile(name)
            } catch (_: IllegalStateException) {
                // Retry after the process restarts and creates its first WebView.
            } catch (_: UnsupportedOperationException) {
                // Keep the profiles for the next WebView provider that supports deletion.
            }
        }
    }
}
