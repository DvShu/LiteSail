package com.joel.litesail.main

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.text.Selection
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.viewbinding.ViewBinding
import androidx.webkit.ScriptHandler
import com.joel.litesail.R
import com.joel.litesail.browser.BookmarkEditorDialog
import com.joel.litesail.browser.BrowserAddress
import com.joel.litesail.browser.BrowserBookmark
import com.joel.litesail.browser.BrowserBookmarks
import com.joel.litesail.browser.BrowserDownloads
import com.joel.litesail.browser.BrowserHistory
import com.joel.litesail.browser.BrowserLibraryActivity
import com.joel.litesail.browser.BrowserMenuDialog
import com.joel.litesail.browser.BrowserRequestFilter
import com.joel.litesail.browser.BrowserVisit
import com.joel.litesail.browser.BrowserWindowManager
import com.joel.litesail.browser.BrowserWindows
import com.joel.litesail.browser.DownloadsActivity
import com.joel.litesail.browser.Eruda
import com.joel.litesail.browser.IncognitoProfile
import com.joel.litesail.browser.WebPageQrCodeShare
import com.joel.litesail.databinding.ActivityMainBinding
import com.joel.litesail.databinding.ViewBrowserBinding
import com.joel.litesail.databinding.ViewBrowserNavigationBinding
import com.joel.litesail.databinding.ViewBrowserToolbarBinding
import com.joel.litesail.databinding.ViewBrowserWindowsBinding
import com.joel.litesail.databinding.ViewQrResultBinding
import com.joel.litesail.databinding.ViewSearchPageBinding
import com.joel.litesail.databinding.ViewStartPageBinding
import com.joel.litesail.scan.ui.QrScanActivity
import com.joel.litesail.search.SearchHistory
import com.joel.litesail.ui.AppearanceActivity
import com.joel.litesail.search.SearchHistoryAdapter
import com.joel.litesail.ui.BottomSheetDialog
import com.joel.litesail.update.AppUpdates
import java.text.NumberFormat

private const val ACTIVE_PAGE = "active_page"
private const val BROWSER_URL = "browser_url"
private const val SEARCH_FROM_BROWSER = "search_from_browser"
private const val START_PAGE_VISIBLE = "start_page_visible"
private const val BROWSER_WINDOWS_STATE = "browser_windows"
private const val WINDOW_MANAGER_VISIBLE = "window_manager_visible"
private const val MAX_WINDOW_PREVIEW_SIDE = 320
private const val MAX_WINDOW_HISTORY_ENTRIES = 24
private const val MAX_WINDOW_HISTORY_BYTES = 24 * 1024
private const val QR_CONTENT = "qr_content"
private const val QR_REQUEST = 101
private const val LIBRARY_REQUEST = 105
private const val INCOGNITO_QR_REQUEST = 106
private const val INCOGNITO_LIBRARY_REQUEST = 107
private const val WEB_DEBUG_ENABLED = "web_debug_enabled"
private const val AD_BLOCK_ENABLED = "ad_block_enabled"
private const val EXIT_CONFIRMATION_MS = 2_000L
private const val EXIT_HINT_FADE_MS = 150L
private val HTTP_PREFIX = Regex("^\\s*https?://", RegexOption.IGNORE_CASE)

class MainActivity : AppearanceActivity() {
    private enum class Page { START, SEARCH, BROWSER, QR_RESULT }
    private class IncognitoSession {
        val windows = BrowserWindows()
        private val profiles = mutableMapOf<BrowserWindows.Window, IncognitoProfile>()

        fun attach(view: WebView): Boolean {
            val profile = profiles.getOrPut(windows.selected) {
                IncognitoProfile.create(view.context) ?: return false
            }
            return profile.attach(view)
        }

        fun clearProfile(window: BrowserWindows.Window) {
            profiles.remove(window)?.clear()
        }

        fun clearProfiles() {
            profiles.values.forEach(IncognitoProfile::clear)
            profiles.clear()
        }
    }
    private enum class ConnectionStatus(val icon: Int, val description: Int) {
        ENCRYPTED(R.drawable.ic_connection_encrypted, R.string.browser_connection_encrypted),
        UNENCRYPTED(R.drawable.ic_connection_unencrypted, R.string.browser_connection_unencrypted)
    }

    private lateinit var layout: ActivityMainBinding
    private val browserView = lazy(LazyThreadSafetyMode.NONE) {
        ViewBrowserBinding.bind(layout.browserStub.inflate()).also(::bindBrowserControls)
    }
    private val binding by browserView
    private val startView = lazy(LazyThreadSafetyMode.NONE) {
        ViewStartPageBinding.bind(layout.startPageStub.inflate()).also(::bindStartControls)
    }
    private val searchView = lazy(LazyThreadSafetyMode.NONE) {
        ViewSearchPageBinding.bind(layout.searchPageStub.inflate()).also { search ->
            search.root.applyWindowInsets(includeIme = true)
            bindSearchControls(search)
            updateSearchIncognitoAppearance(search)
            search.currentPageIcon.setPageIcon(siteIcon)
        }
    }
    private val searchPage by searchView
    private val qrResultView = lazy(LazyThreadSafetyMode.NONE) {
        ViewQrResultBinding.bind(layout.qrResultStub.inflate()).also { result ->
            result.root.applyWindowInsets()
            result.closeQrResult.setOnClickListener { showStartPage() }
        }
    }
    private val qrResultPage by qrResultView
    private val toolbarView = lazy(LazyThreadSafetyMode.NONE) {
        ViewBrowserToolbarBinding.bind(layout.browserToolbarStub.inflate()).also(::bindToolbarControls)
    }
    private val toolbar by toolbarView
    private val navigationView = lazy(LazyThreadSafetyMode.NONE) {
        ViewBrowserNavigationBinding.bind(toolbar.browserNavigationStub.inflate()).also { navigation ->
            navigation.back.setOnClickListener { goBackFromBrowserPage() }
            navigation.forward.setOnClickListener { webView?.takeIf(WebView::canGoForward)?.goForward() }
            navigation.home.setOnClickListener { showStartPage() }
        }
    }
    private val windowsView = lazy(LazyThreadSafetyMode.NONE) {
        val windows = ViewBrowserWindowsBinding.bind(layout.windowManagerStub.inflate())
        windows.root.applyWindowInsets()
        BrowserWindowManager(
            windows,
            onBack = ::hideWindows,
            onSelect = ::selectWindow,
            onClose = ::closeWindow,
            onAdd = ::addWindow,
            onCloseAll = ::confirmCloseAllWindows,
            onModeChange = { incognito -> if (incognito != isIncognito) toggleIncognito() }
        )
    }
    private val isWindowManagerShowing get() = windowsView.ifInitialized()?.isShowing == true
    private val exitHint by lazy(LazyThreadSafetyMode.NONE) { layout.exitHintStub.inflate() }
    private val searchHistory by lazy(LazyThreadSafetyMode.NONE) { SearchHistory(this) }
    private lateinit var historyAdapter: SearchHistoryAdapter
    private var activePage = Page.START
    private val regularWindows = BrowserWindows()
    private var incognitoSession: IncognitoSession? = null
    private var retainIncognitoOnRecreate = false
    private val isIncognito get() = incognitoSession != null
    private val windows get() = incognitoSession?.windows ?: regularWindows
    private val downloads: BrowserDownloads = BrowserDownloads(this) { id -> updates.onDownloadQueued(id) }
    private val updates = AppUpdates(this, downloads)
    private val pageQrShare = WebPageQrCodeShare(this)
    private val bookmarks by lazy { BrowserBookmarks(this) }
    private var closeWindowsDialog: BottomSheetDialog? = null
    private var bookmarkEditor: BookmarkEditorDialog? = null
    private var webView: WebView? = null
    private var webDebugScript: ScriptHandler? = null
    private var recordedVisit: BrowserVisit? = null
    private var browserUrl: String? = null
    private var siteIcon: Bitmap? = null
    private var connectionStatus: ConnectionStatus? = null
    private var searchFromBrowser = false
    private var failedUrl: String? = null
    private var scannedContent: String? = null
    private var menuDialog: BrowserMenuDialog? = null
    private var clearHistoryDialog: BottomSheetDialog? = null
    private var incognitoDownloadDialog: BottomSheetDialog? = null
    private var exitConfirmationDeadline = 0L
    private val hideExitHint = Runnable { clearExitConfirmation() }
    private val browserPreferences by lazy { getSharedPreferences("browser_settings", MODE_PRIVATE) }
    private val requestFilter by lazy {
        BrowserRequestFilter(browserPreferences.getBoolean(AD_BLOCK_ENABLED, true))
    }
    private val webDebugEnabled get() = browserPreferences.getBoolean(WEB_DEBUG_ENABLED, false)

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incognitoSession = lastNonConfigurationInstance as? IncognitoSession
        downloads.restore(savedInstanceState)
        pageQrShare.restore(savedInstanceState)
        layout = ActivityMainBinding.inflate(layoutInflater)
        updateIncognitoAppearance()
        layout.browserShell.applyWindowInsets(includeIme = true)
        setContentView(layout.root)
        val windowState = savedInstanceState?.getBundle(BROWSER_WINDOWS_STATE)
        val savedBrowserUrl = savedInstanceState?.getString(BROWSER_URL)
        if (windowState != null) {
            regularWindows.restore(windowState)
        } else {
            regularWindows.selected.rememberAddress(savedBrowserUrl, null)
        }
        val legacyBrowserVisible = savedInstanceState?.getBoolean(START_PAGE_VISIBLE, savedBrowserUrl == null) == false
        val pageName = savedInstanceState?.getString(ACTIVE_PAGE)
        val page = if (isIncognito) {
            if (windows.selected.home) Page.START else Page.BROWSER
        } else {
            Page.entries.firstOrNull { it.name == pageName }
                ?: if (legacyBrowserVisible) Page.BROWSER else Page.START
        }
        val restoreBrowserSearch = page == Page.SEARCH && savedInstanceState?.getBoolean(SEARCH_FROM_BROWSER) == true
        val needsWebView = page == Page.BROWSER || restoreBrowserSearch
        val legacyState = savedInstanceState.takeIf { windowState == null }
        val restored = needsWebView && restoreWindowContent(legacyState)
        when (page) {
            Page.BROWSER -> showPage(if (restored) Page.BROWSER else Page.START)
            Page.SEARCH -> showSearchPage(fromBrowser = restoreBrowserSearch && restored)
            Page.QR_RESULT -> savedInstanceState?.getString(QR_CONTENT)?.let(::showQrResult) ?: showStartPage()
            else -> showPage(page)
        }
        if (savedInstanceState?.getBoolean(WINDOW_MANAGER_VISIBLE) == true) showWindows()
        updates.checkAfterFirstFrame(layout.root)
    }

    private fun View.applyWindowInsets(includeIme: Boolean = false) {
        val systemTypes = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        val types = if (includeIme) systemTypes or WindowInsets.Type.ime() else systemTypes
        setOnApplyWindowInsetsListener { view, insets ->
            val padding = insets.getInsets(types)
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom)
            insets
        }
        rootWindowInsets?.let(::dispatchApplyWindowInsets)
        requestApplyInsets()
    }

    private fun <T> Lazy<T>.ifInitialized(): T? = if (isInitialized()) value else null

    private fun Lazy<ViewBinding>.showWhen(visible: Boolean) {
        if (visible) value.root.visibility = View.VISIBLE else ifInitialized()?.root?.visibility = View.GONE
    }

    private fun obtainWebView(): WebView? {
        webView?.let { return it }
        IncognitoProfile.clearPreviousSessionsIfNeeded(this)
        // Give each new WebView the selected scheme before restoring its navigation state.
        val webContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val webTheme = if (isDarkMode) R.style.ThemeOverlay_LiteSail_WebView_Dark
                else R.style.ThemeOverlay_LiteSail_WebView_Light
            ContextThemeWrapper(this, webTheme)
        } else {
            this
        }
        return WebView(webContext).also { view ->
            val session = incognitoSession
            if (session != null) {
                if (!session.attach(view)) {
                    view.destroy()
                    Toast.makeText(this, R.string.browser_incognito_open_failed, Toast.LENGTH_LONG).show()
                    return null
                }
                view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            webView = view
            configureWebView(view)
            if (!isIncognito && webDebugEnabled) webDebugScript = Eruda.registerAtDocumentStart(view)
            binding.webContent.addView(
                view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) = with(view) {
        // Website styles control page colors. The WebView theme supplies prefers-color-scheme.
        if (Build.VERSION.SDK_INT >= 29) isForceDarkAllowed = false
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (view !== webView) return@setDownloadListener
            val session = incognitoSession
            if (session == null) {
                downloads.start(url, userAgent, contentDisposition, mimeType)
            } else {
                confirmIncognitoDownload {
                    if (incognitoSession === session) downloads.start(url, userAgent, contentDisposition, mimeType)
                }
            }
            finishDownloadNavigation(view, url)
        }
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
        }
        val filter = requestFilter
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (!filter.blocksNavigation(scheme)) return false
                if (request.isForMainFrame && view === webView && !isIncognito) {
                    val sourceHost = view.url?.let { Uri.parse(it).host } ?: "-"
                    Log.i("LiteSail", "Blocked navigation scheme=${scheme ?: "-"} sourceHost=$sourceHost")
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                filter.intercept(request)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (view !== webView) return
                failedUrl = null
                recordedVisit = null
                setSiteIcon(null)
                setConnectionStatus(null)
                binding.pageError.visibility = View.GONE
                binding.progress.progress = 0
                setLoading(true)
                updateAddress(url)
                updateNavigationButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (view !== webView) return
                updateAddress(url)
                // Same-document navigation can change the URL without starting a new page load.
                if (!isReload && recordedVisit != null) recordBrowserVisit(view, url)
                restoreSiteIcon(view)
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view !== webView) return
                updateAddress(failedUrl ?: view.url ?: url)
                recordBrowserVisit(view, url)
                restoreSiteIcon(view)
                updateNavigationButtons()
                enableWebDebugIfReady(view, url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (view !== webView) return
                recordBrowserVisit(view, url)
                updateCommittedConnection(view, url)
                updateBookmarkButton()
                updateNavigationButtons()
                // Restored history pages can skip onPageFinished.
                enableWebDebugIfReady(view, url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (view !== webView || !request.isForMainFrame || !BrowserAddress.isWebScheme(request.url.scheme)) return
                showPageLoadError(request.url.toString())
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                if (view !== webView) return true
                releaseWebView(stopLoading = false)
                val url = browserUrl
                if (url == null) showStartPage() else showPageLoadError(url)
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (view !== webView) return
                binding.progress.progress = newProgress
                if (newProgress == 100) setLoading(false)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (view !== webView) return
                val pageTitle = title?.takeIf(String::isNotBlank)
                val url = view.url
                if (url != null && url == browserUrl && failedUrl == null) {
                    updateAddressLabel(pageTitle)
                    updateBrowserVisitTitle(url, pageTitle)
                }
                if (!isIncognito && activePage == Page.BROWSER && !isWindowManagerShowing) {
                    this@MainActivity.title = pageTitle ?: getString(R.string.app_name)
                }
                refreshCurrentPageCard()
                refreshManagedWindow()
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                if (view !== webView) return
                val url = view.url ?: return
                if (failedUrl == null && url == browserUrl) showPageFavicon(url, icon)
            }
        }
    }

    private fun finishDownloadNavigation(view: WebView, url: String?) {
        if (url == null || url != browserUrl || failedUrl != null) return
        setLoading(false)
        val pageUrl = view.copyBackForwardList().currentItem?.url
        if (pageUrl != null) {
            updateAddress(pageUrl)
            restoreSiteIcon(view)
            updateCommittedConnection(view, pageUrl)
        } else {
            // An entered file URL has no committed page to restore on window switches.
            releaseWebView()
            browserUrl = null
            windows.selected.rememberAddress(null, null)
            updateAddressLabel()
            searchFromBrowser = false
            refreshCurrentPageCard()
            if (activePage == Page.BROWSER) showStartPage()
        }
        updateNavigationButtons()
    }

    @Suppress("DEPRECATION")
    private fun bindStartControls(page: ViewStartPageBinding) = with(page) {
        startSearchBox.clipToOutline = true
        search.setOnClickListener { showSearchPage() }
        startLibrary.setOnClickListener { openLibrary() }
        startDownloads.setOnClickListener { openDownloads() }
        startBing.setOnClickListener { open(BrowserAddress.HOME) }
        scanQr.setOnClickListener {
            val request = if (isIncognito) INCOGNITO_QR_REQUEST else QR_REQUEST
            startActivityForResult(Intent(this@MainActivity, QrScanActivity::class.java), request)
        }
    }

    private fun bindBrowserControls(page: ViewBrowserBinding) = with(page) {
        browserHeader.clipToOutline = true
        addressBar.setOnClickListener {
            clearSearchInput()
            showSearchPage(fromBrowser = true)
        }
        bookmark.setOnClickListener {
            currentWebPageUrl()?.takeIf(BrowserAddress::isWebUrl)?.let(::bookmarkPage)
        }
        retry.setOnClickListener { failedUrl?.let(::open) }
        reload.setOnClickListener { failedUrl?.let(::open) ?: webView?.reload() }
    }

    private fun bindToolbarControls(controls: ViewBrowserToolbarBinding) = with(controls) {
        root.clipToOutline = true
        windows.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        windows.setOnClickListener { showWindows() }
        openMenu.setOnClickListener {
            dismissKeyboard()
            clearExitConfirmation()
            val dialog = menuDialog ?: BrowserMenuDialog(
                this@MainActivity,
                isDarkMode = isDarkMode,
                onSwitchDisplayMode = {
                    retainIncognitoOnRecreate = isIncognito
                    switchDisplayMode()
                },
                isWebDebugEnabled = { webDebugEnabled },
                onWebDebugChanged = ::setWebDebugEnabled,
                isAdBlockingEnabled = { requestFilter.adBlockingEnabled },
                onAdBlockingChanged = { enabled ->
                    requestFilter.adBlockingEnabled = enabled
                    browserPreferences.edit().putBoolean(AD_BLOCK_ENABLED, enabled).apply()
                },
                currentPageUrl = ::currentWebPageUrl,
                onPageQrCode = pageQrShare::show,
                onSharePage = ::shareWebPage,
                onLibrary = ::openLibrary,
                onDownloads = ::openDownloads,
                onWindows = ::showWindows,
                isIncognito = { isIncognito },
                onToggleIncognito = ::toggleIncognito,
                onExit = ::exitBrowser
            ).also { created ->
                created.setOnDismissListener { openMenu.isActivated = created.isShowing }
                menuDialog = created
            }
            dialog.show()
            openMenu.isActivated = true
        }
    }

    @Suppress("DEPRECATION")
    private fun openLibrary() {
        val request = if (isIncognito) INCOGNITO_LIBRARY_REQUEST else LIBRARY_REQUEST
        val intent = Intent(this, BrowserLibraryActivity::class.java)
            .putExtra(BrowserLibraryActivity.INCOGNITO, isIncognito)
        startActivityForResult(intent, request)
    }

    private fun openDownloads() {
        startActivity(Intent(this, DownloadsActivity::class.java))
    }

    private fun currentWebPageUrl(): String? {
        if (activePage != Page.BROWSER || failedUrl != null) return null
        return webView?.url?.takeIf {
            it == browserUrl && BrowserAddress.isWebScheme(Uri.parse(it).scheme)
        }
    }

    private fun shareWebPage(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            currentWebTitle()?.let { putExtra(Intent.EXTRA_TITLE, it) }
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    private fun recordBrowserVisit(view: WebView, url: String) {
        if (isIncognito || activePage != Page.BROWSER || failedUrl != null || view.url != url || !BrowserAddress.isWebUrl(url)) return
        if (recordedVisit?.url == url) {
            updateBrowserVisitTitle(url, view.title)
            return
        }
        val visit = BrowserVisit(url, historyTitle(view.title) ?: url, System.currentTimeMillis())
        recordedVisit = visit
        BrowserHistory.record(this, visit)
        if (url == browserUrl) siteIcon?.let { BrowserHistory.updateFavicon(this, visit, it) }
    }

    private fun updateBrowserVisitTitle(url: String, title: String?) {
        val visit = recordedVisit?.takeIf { it.url == url } ?: return
        val name = historyTitle(title)?.takeUnless { it == visit.title } ?: return
        val updated = visit.copy(title = name)
        recordedVisit = updated
        BrowserHistory.updateTitle(this, updated)
    }

    private fun historyTitle(title: String?): String? =
        title?.filterNot(Char::isISOControl)?.trim()?.takeIf(String::isNotEmpty)

    private fun bookmarkPage(url: String) {
        val saved = bookmarks[url]
        if (saved != null) {
            editBookmark(saved)
        } else if (bookmarks.add(url, currentWebTitle(), siteIcon.takeUnless { isIncognito })) {
            updateBookmarkButton()
            Toast.makeText(this, R.string.browser_bookmark_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun editBookmark(bookmark: BrowserBookmark) {
        bookmarkEditor?.dismiss()
        val dialog = BookmarkEditorDialog(this, bookmarks, bookmark, ::updateBookmarkButton)
        bookmarkEditor = dialog
        dialog.setOnDismissListener { if (bookmarkEditor === dialog) bookmarkEditor = null }
        dialog.show()
    }

    private fun updateBookmarkButton() {
        val button = browserView.ifInitialized()?.bookmark ?: return
        val url = currentWebPageUrl()?.takeIf(BrowserAddress::isWebUrl)
        button.isEnabled = url != null
        button.isSelected = url?.let(bookmarks::contains) == true
        val label = getString(
            if (button.isSelected) R.string.browser_bookmark_edit else R.string.browser_menu_bookmark
        )
        button.contentDescription = label
        button.tooltipText = label
    }

    private fun toggleIncognito() {
        if (isIncognito) {
            exitIncognito()
            return
        }
        if (!IncognitoProfile.isSupported) {
            renderWindows()
            return
        }
        dismissKeyboard()
        rememberActiveWindow(saveHistory = true, capturePreview = !isWindowManagerShowing)
        releaseWebView()
        incognitoSession = IncognitoSession()
        updateIncognitoAppearance()
        switchToSelectedWindow()
    }

    private fun exitIncognito() {
        val session = incognitoSession ?: return
        dismissKeyboard()
        incognitoDownloadDialog?.dismiss()
        releaseWebView()
        incognitoSession = null
        session.clearProfiles()
        switchToSelectedWindow()
        updateIncognitoAppearance()
    }

    private fun updateIncognitoAppearance() {
        layout.root.isSaveFromParentEnabled = !isIncognito
        if (isIncognito) window.addFlags(FLAG_SECURE) else window.clearFlags(FLAG_SECURE)
        searchView.ifInitialized()?.let(::updateSearchIncognitoAppearance)
    }

    private fun updateSearchIncognitoAppearance(search: ViewSearchPageBinding) = with(search.query) {
        setHint(if (isIncognito) R.string.browser_incognito_search else R.string.start_search_hint)
        imeOptions = if (isIncognito) {
            imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING.inv()
        }
    }

    private fun confirmIncognitoDownload(onConfirm: () -> Unit) {
        incognitoDownloadDialog?.dismiss()
        val dialog = BottomSheetDialog(this, R.string.browser_incognito_download)
        dialog.addAction(R.string.cancel)
        dialog.addAction(R.string.download_history_start, onClick = onConfirm)
        incognitoDownloadDialog = dialog
        dialog.setOnDismissListener { if (incognitoDownloadDialog === dialog) incognitoDownloadDialog = null }
        dialog.show()
    }

    private fun showWindows() {
        if (isWindowManagerShowing) return
        dismissKeyboard()
        rememberActiveWindow(saveHistory = false, capturePreview = true)
        windowsView.value.show()
        renderWindows()
        showPage(activePage)
    }

    private fun hideWindows() {
        closeWindowsDialog?.dismiss()
        windowsView.ifInitialized()?.hide()
        showPage(activePage)
        restoreSelectedWindowIfNeeded()
    }

    private fun selectWindow(index: Int) {
        if (index != windows.selectedIndex) {
            rememberActiveWindow(saveHistory = true)
            if (windows.select(index)) switchToSelectedWindow()
        }
        hideWindows()
    }

    private fun addWindow() {
        rememberActiveWindow(saveHistory = true)
        if (!windows.add()) return
        switchToSelectedWindow()
        hideWindows()
    }

    private fun closeWindow(index: Int) {
        val window = windows.items.getOrNull(index) ?: return
        val session = incognitoSession
        if (session != null && windows.items.size == 1) {
            exitIncognito()
            return
        }
        val activeWindowChanged = windows.close(index)
        if (activeWindowChanged) {
            switchToSelectedWindow()
        } else {
            updateWindowCount()
            renderWindows()
        }
        session?.clearProfile(window)
    }

    private fun confirmCloseAllWindows() {
        closeWindowsDialog?.dismiss()
        val dialog = BottomSheetDialog(this, R.string.browser_window_confirm_close_all)
        dialog.addAction(R.string.cancel)
        dialog.addAction(R.string.browser_window_close_all) {
            if (isIncognito) {
                exitIncognito()
            } else {
                windows.closeAll()
                switchToSelectedWindow()
            }
        }
        closeWindowsDialog = dialog
        dialog.setOnDismissListener { if (closeWindowsDialog === dialog) closeWindowsDialog = null }
        dialog.show()
    }

    private fun renderWindows() {
        val manager = windowsView.ifInitialized() ?: return
        if (!manager.isShowing) return
        manager.render(windows, regularWindows.items.size, isIncognito, isIncognito || IncognitoProfile.isSupported)
    }

    private fun refreshManagedWindow() {
        if (!isWindowManagerShowing || activePage != Page.BROWSER) return
        rememberActiveWindow(saveHistory = false)
        renderWindows()
    }

    private fun switchToSelectedWindow() {
        releaseWebView()
        clearSearchInput()
        failedUrl = null
        browserUrl = null
        updateAddressLabel()
        browserView.ifInitialized()?.pageError?.visibility = View.GONE
        setLoading(false)
        setSiteIcon(null)
        setConnectionStatus(null)
        val selected = windows.selected
        if (!selected.home && (selected.url != null || selected.history != null)) {
            selected.url?.let(::updateAddress)
            showPage(Page.BROWSER)
            if (!isWindowManagerShowing) restoreSelectedWindowIfNeeded()
        } else {
            showPage(Page.START)
        }
        updateWindowCount()
        updateNavigationButtons()
        renderWindows()
    }

    private fun restoreSelectedWindowIfNeeded() {
        if (activePage != Page.BROWSER || webView != null || failedUrl != null) return
        if (!restoreWindowContent()) showPage(Page.START)
        updateNavigationButtons()
    }

    private fun updateWindowCount() {
        val controls = toolbarView.ifInitialized() ?: return
        val count = windows.items.size
        controls.windowCount.text = NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(count)
        controls.windows.contentDescription = getString(R.string.browser_window_count, count)
    }

    private fun rememberActiveWindow(saveHistory: Boolean, capturePreview: Boolean = false) {
        val view = webView
        val window = windows.selected
        window.home = activePage != Page.BROWSER && !(activePage == Page.SEARCH && searchFromBrowser)
        val url = browserUrl ?: window.url
        val title = if (view != null && failedUrl == null && view.url == url) {
            view.title
        } else {
            window.title.takeIf { window.url == url }
        }
        window.rememberAddress(url, title)
        if (!window.home && view != null && failedUrl == null && window.url == url && view.url == url) {
            siteIcon?.let(window::rememberFavicon)
            if (capturePreview && !isIncognito && binding.progress.visibility != View.VISIBLE) {
                drawWindowPreview(view)?.let { window.preview = it }
            }
        }
        if (saveHistory && view != null) window.history = saveWindowHistory(view)
    }

    private fun drawWindowPreview(view: WebView): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        val height = minOf(view.height, view.width)
        val scale = minOf(1f, MAX_WINDOW_PREVIEW_SIDE.toFloat() / view.width)
        val preview = Bitmap.createBitmap(
            (view.width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        return try {
            Canvas(preview).apply {
                drawColor(getColor(R.color.start_page_search_surface))
                scale(scale, scale)
                view.draw(this)
            }
            preview
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun saveWindowHistory(view: WebView): Bundle? {
        if (failedUrl != null || browserUrl == null || view.url != browserUrl) return null
        if (view.copyBackForwardList().size > MAX_WINDOW_HISTORY_ENTRIES) return null
        val history = Bundle()
        if (view.saveState(history) == null) return null
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(history)
            history.takeIf { parcel.dataSize() <= MAX_WINDOW_HISTORY_BYTES }
        } finally {
            parcel.recycle()
        }
    }

    private fun restoreWindowContent(legacyState: Bundle? = null): Boolean {
        val window = windows.selected
        val history = window.history ?: legacyState
        val url = window.url
        if (url == null && history == null) return false
        window.history = null
        val view = obtainWebView() ?: return false
        val restored = history?.let(view::restoreState)?.currentItem
        if (restored != null) {
            updateAddress(restored.url)
            restored.favicon?.let { showPageFavicon(restored.url, it) }
        } else {
            loadPage(url ?: BrowserAddress.HOME)
        }
        return true
    }

    private fun releaseWebView(stopLoading: Boolean = true) {
        val view = webView ?: return
        webView = null
        webDebugScript = null
        recordedVisit = null
        try {
            if (stopLoading) {
                view.webChromeClient = null
                view.setDownloadListener(null)
                view.stopLoading()
            }
        } finally {
            binding.webContent.removeView(view)
            view.destroy()
        }
    }

    private fun showPageLoadError(url: String) {
        failedUrl = url
        setSiteIcon(null)
        setConnectionStatus(null)
        binding.pageError.visibility = View.VISIBLE
        updateAddress(url)
        updateNavigationButtons()
        setLoading(false)
        refreshManagedWindow()
    }

    private fun setWebDebugEnabled(enabled: Boolean) {
        if (isIncognito) return
        browserPreferences.edit().putBoolean(WEB_DEBUG_ENABLED, enabled).apply()
        val view = webView ?: return
        if (enabled) {
            if (webDebugScript == null) webDebugScript = Eruda.registerAtDocumentStart(view)
        } else {
            webDebugScript?.remove()
            webDebugScript = null
        }
        if (!enabled || (activePage == Page.BROWSER && failedUrl == null)) {
            Eruda.setEnabled(view, enabled)
        }
    }

    private fun enableWebDebugIfReady(view: WebView, url: String) {
        // Covers older WebViews and loads that were already in progress when debugging was enabled.
        if (!isIncognito && webDebugEnabled && failedUrl == null && url == view.url) {
            Eruda.setEnabled(view, true)
        }
    }

    private fun bindSearchControls(page: ViewSearchPageBinding) = with(page) {
        closeSearch.setOnClickListener { closeSearchPage() }
        submitSearch.setOnClickListener { submitQuery(query.text.toString()) }
        copyPageLink.setOnClickListener {
            browserUrl?.let { copyText(R.string.page_link, it, R.string.page_link_copied) }
        }
        editPageLink.setOnClickListener {
            val url = browserUrl ?: return@setOnClickListener
            query.error = null
            query.setText(url)
            query.setSelection(query.length())
            focusSearchInput()
        }
        shortcutCursorLeft.setOnClickListener { moveSearchCursor(towardLeft = true) }
        shortcutCursorRight.setOnClickListener { moveSearchCursor(towardLeft = false) }
        listOf(shortcutHttps, shortcutHttp).forEach { shortcut ->
            shortcut.setOnClickListener { setSearchProtocol(shortcut.text.toString()) }
        }
        shortcutWww.setOnClickListener { addWwwPrefix(shortcutWww.text.toString()) }
        listOf(shortcutCom, shortcutCn, shortcutSlash, shortcutDot).forEach { shortcut ->
            shortcut.setOnClickListener { insertSearchText(shortcut.text.toString()) }
        }
        query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            submitQuery(query.text.toString())
            true
        }
        historyAdapter = SearchHistoryAdapter(
            this@MainActivity,
            onOpen = ::submitQuery,
            onDelete = { entry ->
                searchHistory.remove(entry)
                refreshSearchHistory()
            }
        )
        history.itemsCanFocus = true
        history.emptyView = emptyHistory
        history.adapter = historyAdapter
        clearHistory.setOnClickListener { confirmClearSearchHistory() }
    }

    private fun showSearchPage(fromBrowser: Boolean = false) {
        searchFromBrowser = fromBrowser
        showPage(Page.SEARCH)
        refreshSearchHistory()
        refreshCurrentPageCard()
        focusSearchInput()
    }

    private fun closeSearchPage() {
        if (searchFromBrowser) clearSearchInput()
        dismissKeyboard()
        showPage(if (searchFromBrowser) Page.BROWSER else Page.START)
    }

    private fun clearSearchInput() {
        val query = searchView.ifInitialized()?.query ?: return
        query.error = null
        query.text.clear()
    }

    private fun refreshCurrentPageCard() {
        if (activePage != Page.SEARCH) return
        val url = browserUrl.takeIf { searchFromBrowser }
        searchPage.currentPageCard.visibility = if (url == null) View.GONE else View.VISIBLE
        if (url == null) return

        val pageTitle = currentWebTitle()
        searchPage.currentPageTitle.text = pageTitle ?: getString(R.string.current_web_page)
        searchPage.currentPageUrl.text = url
    }

    private fun refreshSearchHistory() {
        val entries = if (isIncognito) emptyList() else searchHistory.entries()
        historyAdapter.replaceEntries(entries)
        searchPage.emptyHistory.setText(if (isIncognito) R.string.browser_incognito_no_history else R.string.empty_search_history)
        searchPage.clearHistory.visibility = if (entries.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun confirmClearSearchHistory() {
        clearHistoryDialog?.dismiss()
        dismissKeyboard()
        val dialog = BottomSheetDialog(this, R.string.confirm_clear_search_history)
        dialog.addAction(R.string.cancel)
        dialog.addAction(R.string.clear_search_history) {
            searchHistory.clear()
            refreshSearchHistory()
        }
        dialog.setOnDismissListener { if (clearHistoryDialog === dialog) clearHistoryDialog = null }
        clearHistoryDialog = dialog
        dialog.show()
    }

    private fun focusSearchInput() {
        val query = searchPage.query
        query.requestFocus()
        query.post {
            if (activePage == Page.SEARCH && query.hasFocus()) {
                getSystemService(InputMethodManager::class.java)
                    ?.showSoftInput(query, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun setSearchProtocol(protocol: String) {
        focusSearchInput()
        val query = searchPage.query
        val input = query.text
        val prefixLength = searchHostStart(input)
        val cursor = query.selectionEnd.takeIf { it >= 0 } ?: input.length
        input.replace(0, prefixLength, protocol)
        query.setSelection((cursor - prefixLength + protocol.length).coerceIn(protocol.length, input.length))
    }

    private fun moveSearchCursor(towardLeft: Boolean) {
        focusSearchInput()
        val query = searchPage.query
        val layout = query.layout ?: return
        if (towardLeft) {
            Selection.moveLeft(query.text, layout)
        } else {
            Selection.moveRight(query.text, layout)
        }
    }

    private fun addWwwPrefix(prefix: String) {
        focusSearchInput()
        val query = searchPage.query
        val input = query.text
        val hostStart = searchHostStart(input)
        if (input.startsWith(prefix, hostStart, ignoreCase = true)) return

        val cursor = query.selectionEnd.takeIf { it >= 0 } ?: input.length
        input.insert(hostStart, prefix)
        query.setSelection(maxOf(cursor, hostStart) + prefix.length)
    }

    private fun searchHostStart(input: CharSequence): Int =
        HTTP_PREFIX.find(input)?.value?.length
            ?: input.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }
            ?: input.length

    private fun insertSearchText(value: String) {
        focusSearchInput()
        val query = searchPage.query
        val input = query.text
        val selectionStart = query.selectionStart.takeIf { it >= 0 } ?: input.length
        val selectionEnd = query.selectionEnd.takeIf { it >= 0 } ?: selectionStart
        val start = minOf(selectionStart, selectionEnd)
        input.replace(start, maxOf(selectionStart, selectionEnd), value)
        query.setSelection(start + value.length)
    }

    private fun showStartPage() {
        dismissKeyboard()
        showPage(Page.START)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        downloads.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (!isIncognito && (requestCode == INCOGNITO_QR_REQUEST || requestCode == INCOGNITO_LIBRARY_REQUEST)) return
        when (requestCode) {
            QR_REQUEST, INCOGNITO_QR_REQUEST -> data?.getStringExtra(QrScanActivity.RESULT_CONTENT)
                ?.takeUnless(String::isBlank)?.let(::showQrResult)
            LIBRARY_REQUEST, INCOGNITO_LIBRARY_REQUEST -> data?.getStringExtra(BrowserLibraryActivity.SELECTED_URL)
                ?.takeIf(BrowserAddress::isWebUrl)?.let(::open)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        downloads.onRequestPermissionsResult(requestCode, grantResults)
        pageQrShare.onRequestPermissionsResult(requestCode, grantResults)
    }

    private fun showQrResult(content: String) {
        val address = BrowserAddress.scannedWebUrl(content)
        val resultText = address ?: content
        val resultType = if (address != null) R.string.qr_result_website else R.string.qr_result_text
        scannedContent = content
        with(qrResultPage) {
            qrContentType.setText(resultType)
            qrContent.text = resultText
            qrContent.textDirection = if (address != null) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_FIRST_STRONG
            qrContentScroll.scrollTo(0, 0)
            qrCopyResult.setOnClickListener {
                copyText(resultType, resultText, R.string.qr_result_copied)
            }
            qrResultAction.setText(if (address != null) R.string.qr_visit_now else R.string.qr_search_now)
            qrResultAction.setOnClickListener {
                if (address == null && !isIncognito) searchHistory.record(content.trim())
                open(address ?: BrowserAddress.bingSearchUrl(content))
            }
        }
        showPage(Page.QR_RESULT)
    }

    private fun copyText(label: Int, text: String, confirmation: Int) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(label), text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showPage(page: Page) {
        clearExitConfirmation()
        activePage = page
        val isBrowser = page == Page.BROWSER
        val isStart = page == Page.START
        val managingWindows = isWindowManagerShowing
        if (page != Page.SEARCH) searchFromBrowser = false
        if (page != Page.QR_RESULT) scannedContent = null
        layout.browserShell.visibility = when {
            !isStart && !isBrowser -> View.GONE
            managingWindows -> View.INVISIBLE
            else -> View.VISIBLE
        }
        startView.showWhen(isStart)
        searchView.showWhen(page == Page.SEARCH)
        qrResultView.showWhen(page == Page.QR_RESULT)
        browserView.showWhen(isBrowser)
        navigationView.showWhen(isBrowser)
        if (isStart || isBrowser) {
            val controls = toolbar.root
            val toolbarWidth = if (isBrowser) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            val params = controls.layoutParams
            if (params.width != toolbarWidth) {
                params.width = toolbarWidth
                controls.layoutParams = params
            }
            updateWindowCount()
        }
        if (isBrowser) {
            updateAddressLabel()
            updateNavigationButtons()
        }
        val pageTitle = if (isBrowser) webView?.title else null
        title = when {
            managingWindows -> getString(if (isIncognito) R.string.browser_incognito_windows else R.string.browser_windows_page)
            isIncognito -> getString(R.string.browser_menu_incognito)
            else -> pageTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        }
        updateBookmarkButton()
        updateWindowColors(page)
    }

    @Suppress("DEPRECATION")
    private fun updateWindowColors(page: Page) {
        val managingWindows = isWindowManagerShowing
        window.setBackgroundDrawableResource(
            when {
                managingWindows -> R.drawable.window_manager_surface
                page == Page.START -> R.drawable.start_page_surface
                page == Page.BROWSER -> R.color.start_page_search_surface
                else -> R.color.start_page_background
            }
        )
        if (Build.VERSION.SDK_INT < 35) {
            val background = getColor(
                if (managingWindows || page == Page.BROWSER) R.color.start_page_search_surface
                else R.color.start_page_background
            )
            window.statusBarColor = background
            window.navigationBarColor = when {
                Build.VERSION.SDK_INT < 27 -> Color.BLACK
                managingWindows -> getColor(R.color.start_page_glow)
                else -> background
            }
        }
    }

    private fun submitQuery(input: String) {
        val query = input.trim()
        val url = BrowserAddress.resolve(query, BrowserAddress.BING_SEARCH_URL_PREFIX)
        if (url == null) {
            searchPage.query.error = getString(R.string.invalid_search_input)
            return
        }
        if (!isIncognito) searchHistory.record(query)
        clearSearchInput()
        open(url)
    }

    private fun open(url: String) {
        dismissKeyboard()
        if (activePage != Page.BROWSER) {
            if (!searchFromBrowser) {
                releaseWebView()
                windows.selected.clear()
                browserUrl = null
                updateNavigationButtons()
            }
            showPage(Page.BROWSER)
        }
        binding.root.requestFocus()
        loadPage(url)
    }

    private fun loadPage(url: String) {
        val view = obtainWebView() ?: run {
            showStartPage()
            return
        }
        val saved = windows.selected.history
        windows.selected.history = null
        if (view.url == null && saved != null) view.restoreState(saved)
        failedUrl = null
        binding.pageError.visibility = View.GONE
        setSiteIcon(null)
        setConnectionStatus(null)
        updateAddress(url)
        view.loadUrl(url)
    }

    private fun dismissKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun updateAddress(url: String) {
        if (browserUrl != url) {
            setSiteIcon(null)
            browserUrl = url
        }
        updateAddressLabel()
        updateBookmarkButton()
        refreshCurrentPageCard()
    }

    private fun currentWebTitle(): String? = webView?.takeIf {
        failedUrl == null && browserUrl != null && it.url == browserUrl
    }?.title?.takeIf(String::isNotBlank)

    private fun updateAddressLabel(pageTitle: String? = currentWebTitle()) {
        val browser = browserView.ifInitialized() ?: return
        val label = pageTitle ?: browserUrl.orEmpty()
        with(browser.address) {
            textDirection = if (pageTitle == null) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_FIRST_STRONG
            if (text != label) text = label
        }
        updateAddressDescription()
    }

    private fun updateCommittedConnection(view: WebView, committedUrl: String) {
        val currentUrl = view.url ?: return
        if (failedUrl != null || currentUrl != browserUrl) return
        val current = Uri.parse(currentUrl)
        val committed = Uri.parse(committedUrl)
        if (current.scheme != committed.scheme || current.encodedAuthority != committed.encodedAuthority) return

        val status = when (current.scheme) {
            "https" -> ConnectionStatus.ENCRYPTED.takeIf { view.certificate != null }
            "http" -> ConnectionStatus.UNENCRYPTED
            else -> null
        }
        setConnectionStatus(status)
    }

    private fun setConnectionStatus(status: ConnectionStatus?) {
        if (connectionStatus == status) return
        connectionStatus = status
        val browser = browserView.ifInitialized() ?: return
        browser.connectionBadge.visibility = if (status == null) View.GONE else View.VISIBLE
        if (status != null) browser.connectionBadge.setImageResource(status.icon)
        browser.addressBar.tooltipText = status?.let { getString(it.description) }
        updateAddressDescription()
    }

    private fun updateAddressDescription() {
        binding.addressBar.contentDescription = connectionStatus?.let {
            getString(R.string.browser_address_with_connection, binding.address.text, getString(it.description))
        }
    }

    private fun restoreSiteIcon(view: WebView) {
        if (siteIcon != null || failedUrl != null) return
        val entry = view.copyBackForwardList().currentItem ?: return
        if (entry.url == browserUrl) entry.favicon?.let { showPageFavicon(entry.url, it) }
    }

    private fun showPageFavicon(url: String, icon: Bitmap) {
        if (siteIcon === icon) return
        setSiteIcon(icon)
        refreshManagedWindow()
        if (isIncognito) return
        recordedVisit?.takeIf { it.url == url }?.let { BrowserHistory.updateFavicon(this, it, icon) }
        bookmarks.updateFavicon(url, icon)
    }

    private fun setSiteIcon(icon: Bitmap?) {
        if (siteIcon === icon) return
        siteIcon = icon
        browserView.ifInitialized()?.addressIcon?.setPageIcon(icon)
        searchView.ifInitialized()?.currentPageIcon?.setPageIcon(icon)
    }

    private fun ImageView.setPageIcon(icon: Bitmap?) {
        if (icon == null) setImageResource(R.drawable.ic_start_search) else setImageBitmap(icon)
    }

    private fun updateNavigationButtons() {
        navigationView.ifInitialized()?.forward?.isEnabled = webView?.canGoForward() == true
    }

    private fun setLoading(active: Boolean) {
        browserView.ifInitialized()?.progress?.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    private fun handleHomeBack() {
        val now = SystemClock.elapsedRealtime()
        if (now < exitConfirmationDeadline) {
            exitBrowser()
            return
        }
        exitConfirmationDeadline = now + EXIT_CONFIRMATION_MS
        with(exitHint) {
            removeCallbacks(hideExitHint)
            if (visibility != View.VISIBLE) {
                alpha = 0f
                visibility = View.VISIBLE
                animate().alpha(1f).setDuration(EXIT_HINT_FADE_MS).start()
            }
            postDelayed(hideExitHint, EXIT_CONFIRMATION_MS)
        }
    }

    private fun clearExitConfirmation() {
        if (exitConfirmationDeadline == 0L) return
        exitConfirmationDeadline = 0L
        with(exitHint) {
            removeCallbacks(hideExitHint)
            animate().cancel()
            visibility = View.INVISIBLE
        }
    }

    private fun exitBrowser() {
        clearExitConfirmation()
        menuDialog?.dismiss()
        closeWindowsDialog?.dismiss()
        finish()
    }

    private fun goBackFromBrowserPage() {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else showStartPage()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            isWindowManagerShowing -> hideWindows()
            activePage == Page.START && isIncognito -> exitIncognito()
            activePage == Page.START -> handleHomeBack()
            activePage == Page.SEARCH -> closeSearchPage()
            activePage == Page.QR_RESULT -> showStartPage()
            else -> goBackFromBrowserPage()
        }
    }

    override fun onStart() {
        super.onStart()
        updateBookmarkButton()
        webView?.onResume()
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    override fun onPause() {
        updates.onPause()
        super.onPause()
    }

    override fun onStop() {
        clearExitConfirmation()
        webView?.onPause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        downloads.save(outState)
        pageQrShare.save(outState)
        if (isIncognito) {
            val regularPage = if (regularWindows.selected.home) Page.START else Page.BROWSER
            outState.putString(ACTIVE_PAGE, regularPage.name)
        } else {
            outState.putString(ACTIVE_PAGE, activePage.name)
            outState.putBoolean(WINDOW_MANAGER_VISIBLE, isWindowManagerShowing)
            outState.putBoolean(SEARCH_FROM_BROWSER, searchFromBrowser)
            scannedContent?.let { outState.putString(QR_CONTENT, it) }
            rememberActiveWindow(saveHistory = true)
        }
        outState.putBundle(BROWSER_WINDOWS_STATE, regularWindows.save())
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRetainNonConfigurationInstance(): Any? {
        val session = incognitoSession?.takeIf { retainIncognitoOnRecreate } ?: return null
        rememberActiveWindow(saveHistory = true)
        incognitoSession = null
        return session
    }

    override fun onDestroy() {
        updates.close()
        menuDialog?.dismiss()
        closeWindowsDialog?.dismiss()
        bookmarkEditor?.dismiss()
        clearHistoryDialog?.dismiss()
        incognitoDownloadDialog?.dismiss()
        pageQrShare.close()
        releaseWebView()
        incognitoSession?.clearProfiles()
        super.onDestroy()
    }
}
