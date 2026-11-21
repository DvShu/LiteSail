package com.joel.litesail.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.os.SystemClock
import android.text.Selection
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.FrameLayout
import com.google.zxing.WriterException
import com.joel.litesail.R
import com.joel.litesail.browser.BrowserAddress
import com.joel.litesail.browser.BrowserDownloads
import com.joel.litesail.browser.BrowserMenuDialog
import com.joel.litesail.browser.BrowserWindows
import com.joel.litesail.browser.BrowserWindowsDialog
import com.joel.litesail.browser.Eruda
import com.joel.litesail.browser.WebPageQrCode
import com.joel.litesail.databinding.ActivityMainBinding
import com.joel.litesail.databinding.ViewBrowserBinding
import com.joel.litesail.databinding.ViewQrResultBinding
import com.joel.litesail.databinding.ViewSearchPageBinding
import com.joel.litesail.databinding.ViewStartPageBinding
import com.joel.litesail.databinding.ViewWebPageQrCodeBinding
import com.joel.litesail.scan.ui.QrScanActivity
import com.joel.litesail.search.SearchHistory
import com.joel.litesail.search.SearchHistoryAdapter
import java.text.NumberFormat

private const val ACTIVE_PAGE = "active_page"
private const val BROWSER_URL = "browser_url"
private const val SEARCH_FROM_BROWSER = "search_from_browser"
private const val START_PAGE_VISIBLE = "start_page_visible"
private const val BROWSER_WINDOWS_STATE = "browser_windows"
private const val MAX_WINDOW_HISTORY_ENTRIES = 24
private const val MAX_WINDOW_HISTORY_BYTES = 24 * 1024
private const val QR_CONTENT = "qr_content"
private const val QR_REQUEST = 101
private const val WEB_DEBUG_ENABLED = "web_debug_enabled"
private const val EXIT_CONFIRMATION_MS = 2_000L
private const val EXIT_HINT_FADE_MS = 150L
private val HTTP_PREFIX = Regex("^\\s*https?://", RegexOption.IGNORE_CASE)

class MainActivity : Activity() {
    private enum class Page { START, SEARCH, BROWSER, QR_RESULT }
    private enum class ConnectionStatus(val icon: Int, val description: Int) {
        ENCRYPTED(R.drawable.ic_connection_encrypted, R.string.browser_connection_encrypted),
        UNENCRYPTED(R.drawable.ic_connection_unencrypted, R.string.browser_connection_unencrypted)
    }

    private lateinit var layout: ActivityMainBinding
    private lateinit var binding: ViewBrowserBinding
    private lateinit var startPage: ViewStartPageBinding
    private lateinit var searchPage: ViewSearchPageBinding
    private lateinit var qrResultPage: ViewQrResultBinding
    private lateinit var searchHistory: SearchHistory
    private lateinit var historyAdapter: SearchHistoryAdapter
    private var activePage = Page.START
    private val windows = BrowserWindows()
    private val downloads = BrowserDownloads(this)
    private var windowsDialog: BrowserWindowsDialog? = null
    private var webView: WebView? = null
    private var browserUrl: String? = null
    private var siteIcon: Bitmap? = null
    private var connectionStatus: ConnectionStatus? = null
    private var searchFromBrowser = false
    private var failedUrl: String? = null
    private var scannedContent: String? = null
    private var menuDialog: BrowserMenuDialog? = null
    private var pageQrDialog: AlertDialog? = null
    private var exitConfirmationDeadline = 0L
    private val hideExitHint = Runnable { clearExitConfirmation() }
    private val browserPreferences by lazy { getSharedPreferences("browser_settings", MODE_PRIVATE) }
    private val webDebugEnabled get() = browserPreferences.getBoolean(WEB_DEBUG_ENABLED, false)
    private val toolbar get() = layout.browserToolbar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloads.restore(savedInstanceState)
        layout = ActivityMainBinding.inflate(layoutInflater)
        binding = layout.browser
        startPage = layout.startPage
        searchPage = layout.searchPage
        qrResultPage = layout.qrResult
        searchHistory = SearchHistory(this)
        setContentView(layout.root)
        if (Build.VERSION.SDK_INT >= 35) {
            layout.root.setOnApplyWindowInsetsListener { _, insets ->
                val systemAreas = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val systemArea = insets.getInsets(systemAreas)
                val browserArea = insets.getInsets(systemAreas or WindowInsets.Type.ime())
                layout.browserShell.setPadding(browserArea.left, browserArea.top, browserArea.right, browserArea.bottom)
                searchPage.root.setPadding(browserArea.left, browserArea.top, browserArea.right, browserArea.bottom)
                qrResultPage.root.setPadding(systemArea.left, systemArea.top, systemArea.right, systemArea.bottom)
                insets
            }
        }
        bindControls()
        bindSearchControls()
        qrResultPage.closeQrResult.setOnClickListener { showStartPage() }
        val windowState = savedInstanceState?.getBundle(BROWSER_WINDOWS_STATE)
        val savedBrowserUrl = savedInstanceState?.getString(BROWSER_URL)
        if (windowState != null) {
            windows.restore(windowState)
        } else {
            windows.selected.rememberAddress(savedBrowserUrl, null)
        }
        val legacyBrowserVisible = savedInstanceState?.getBoolean(START_PAGE_VISIBLE, savedBrowserUrl == null) == false
        val pageName = savedInstanceState?.getString(ACTIVE_PAGE)
        val page = Page.entries.firstOrNull { it.name == pageName }
            ?: if (legacyBrowserVisible) Page.BROWSER else Page.START
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
        updateWindowCount()
        updateNavigationButtons()
    }

    private fun obtainWebView(): WebView {
        webView?.let { return it }
        return WebView(this).also { view ->
            webView = view
            configureWebView(view)
            binding.webContent.addView(
                view, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) = with(view) {
        setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (view !== webView) return@setDownloadListener
            downloads.start(url, userAgent, contentDisposition, mimeType)
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
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme
                if (BrowserAddress.isWebScheme(scheme)) return false
                if (request.isForMainFrame) {
                    val sourceHost = view.url?.let { Uri.parse(it).host } ?: "-"
                    Log.i("LiteSail", "Blocked navigation scheme=${scheme ?: "-"} sourceHost=$sourceHost")
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (view !== webView) return
                failedUrl = null
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
                restoreSiteIcon(view)
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (view !== webView) return
                updateAddress(failedUrl ?: view.url ?: url)
                restoreSiteIcon(view)
                updateNavigationButtons()
                enableWebDebugIfReady(view, url)
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (view !== webView) return
                updateCommittedConnection(view, url)
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
                if (binding.root.visibility == View.VISIBLE) {
                    this@MainActivity.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
                }
                refreshCurrentPageCard()
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                if (view !== webView) return
                val url = view.url ?: return
                if (failedUrl == null && url == browserUrl) setSiteIcon(icon)
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
            binding.address.text = null
            searchFromBrowser = false
            refreshCurrentPageCard()
            if (activePage == Page.BROWSER) showStartPage()
        }
        updateNavigationButtons()
    }

    @Suppress("DEPRECATION")
    private fun bindControls() = with(binding) {
        startPage.startSearchBox.clipToOutline = true
        startPage.search.setOnClickListener { showSearchPage() }
        startPage.scanQr.setOnClickListener {
            startActivityForResult(Intent(this@MainActivity, QrScanActivity::class.java), QR_REQUEST)
        }
        addressBar.setOnClickListener {
            clearSearchInput()
            showSearchPage(fromBrowser = true)
        }
        toolbar.root.clipToOutline = true
        toolbar.back.setOnClickListener { webView?.takeIf(WebView::canGoBack)?.goBack() }
        toolbar.forward.setOnClickListener { webView?.takeIf(WebView::canGoForward)?.goForward() }
        toolbar.home.setOnClickListener { showStartPage() }
        toolbar.windows.setOnClickListener { showWindows() }
        toolbar.openMenu.setOnClickListener {
            dismissKeyboard()
            clearExitConfirmation()
            val dialog = menuDialog ?: BrowserMenuDialog(
                this@MainActivity,
                isWebDebugEnabled = { webDebugEnabled },
                onWebDebugChanged = ::setWebDebugEnabled,
                currentPageUrl = ::currentWebPageUrl,
                onPageQrCode = ::showWebPageQrCode,
                onDownloads = downloads::show,
                onWindows = ::showWindows,
                onExit = ::exitBrowser
            ).also { menuDialog = it }
            dialog.show()
        }
        retry.setOnClickListener { failedUrl?.let(::open) }
        reload.setOnClickListener { failedUrl?.let(::open) ?: webView?.reload() }
    }

    private fun currentWebPageUrl(): String? {
        if (activePage != Page.BROWSER || failedUrl != null) return null
        return webView?.url?.takeIf {
            it == browserUrl && BrowserAddress.isWebScheme(Uri.parse(it).scheme)
        }
    }

    private fun showWindows() {
        dismissKeyboard()
        clearExitConfirmation()
        rememberActiveWindow(saveHistory = false)
        val dialog = windowsDialog ?: BrowserWindowsDialog(
            this,
            windows,
            onSelect = { index ->
                if (index != windows.selectedIndex) {
                    rememberActiveWindow(saveHistory = true)
                    if (windows.select(index)) switchToSelectedWindow()
                }
            },
            onClose = { index ->
                if (windows.close(index)) switchToSelectedWindow() else updateWindowCount()
            },
            onAdd = {
                rememberActiveWindow(saveHistory = true)
                if (windows.add()) switchToSelectedWindow()
            }
        ).also { dialog ->
            dialog.setOnDismissListener {
                if (!dialog.isShowing && !isFinishing && !isDestroyed) restoreSelectedWindowIfNeeded()
            }
            windowsDialog = dialog
        }
        dialog.show()
    }

    private fun switchToSelectedWindow() {
        releaseWebView()
        clearSearchInput()
        failedUrl = null
        browserUrl = null
        binding.address.text = null
        binding.pageError.visibility = View.GONE
        setLoading(false)
        setSiteIcon(null)
        setConnectionStatus(null)
        val selected = windows.selected
        if (!selected.home && (selected.url != null || selected.history != null)) {
            selected.url?.let(::updateAddress)
            showPage(Page.BROWSER)
            if (windowsDialog?.isShowing != true) restoreSelectedWindowIfNeeded()
        } else {
            showPage(Page.START)
        }
        updateWindowCount()
        updateNavigationButtons()
    }

    private fun restoreSelectedWindowIfNeeded() {
        if (activePage != Page.BROWSER || webView != null || failedUrl != null) return
        if (!restoreWindowContent()) showPage(Page.START)
        updateNavigationButtons()
    }

    private fun updateWindowCount() = with(toolbar) {
        val count = this@MainActivity.windows.items.size
        windowCount.text = NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(count)
        windows.contentDescription = getString(R.string.browser_window_count, count)
    }

    private fun rememberActiveWindow(saveHistory: Boolean) {
        val view = webView
        val window = windows.selected
        window.home = activePage != Page.BROWSER && !(activePage == Page.SEARCH && searchFromBrowser)
        val url = browserUrl ?: window.url
        val title = if (view != null && failedUrl == null && view.url == url) view.title else window.title
        window.rememberAddress(url, title)
        if (saveHistory && view != null) window.history = saveWindowHistory(view)
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
        val view = obtainWebView()
        val restored = history?.let(view::restoreState)?.currentItem
        if (restored != null) {
            updateAddress(restored.url)
            setSiteIcon(restored.favicon)
        } else {
            loadPage(url ?: BrowserAddress.HOME)
        }
        return true
    }

    private fun releaseWebView(stopLoading: Boolean = true) {
        val view = webView ?: return
        webView = null
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
    }

    private fun showWebPageQrCode(url: String) {
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.browser_page_qr_code)
            .setPositiveButton(android.R.string.ok, null)
        try {
            val qr = WebPageQrCode.encode(url)
            val pixels = IntArray(qr.width * qr.height)
            for (y in 0 until qr.height) {
                for (x in 0 until qr.width) {
                    pixels[y * qr.width + x] = if (qr[x, y]) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(pixels, qr.width, qr.height, Bitmap.Config.ARGB_8888).apply {
                density = Bitmap.DENSITY_NONE
            }
            val image = ViewWebPageQrCodeBinding.inflate(layoutInflater).root
            image.setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
            image.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                val width = right - left - image.paddingLeft - image.paddingRight
                val height = bottom - top - image.paddingTop - image.paddingBottom
                val available = minOf(width, height)
                if (available <= 0) return@addOnLayoutChangeListener
                val size = WebPageQrCode.displaySize(qr.width, available)
                val scale = size.toFloat() / qr.width
                image.imageMatrix = Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(((width - size) / 2).toFloat(), ((height - size) / 2).toFloat())
                }
            }
            builder.setView(image)
        } catch (_: WriterException) {
            builder.setMessage(R.string.browser_page_qr_code_too_long)
        }

        pageQrDialog?.dismiss()
        val dialog = builder.create()
        dialog.setOnDismissListener { if (pageQrDialog === dialog) pageQrDialog = null }
        pageQrDialog = dialog
        dialog.show()
    }

    private fun setWebDebugEnabled(enabled: Boolean) {
        browserPreferences.edit().putBoolean(WEB_DEBUG_ENABLED, enabled).apply()
        if (!enabled || (activePage == Page.BROWSER && failedUrl == null)) {
            webView?.let { Eruda.setEnabled(it, enabled) }
        }
    }

    private fun enableWebDebugIfReady(view: WebView, url: String) {
        if (webDebugEnabled && failedUrl == null && url == view.url) {
            Eruda.setEnabled(view, true)
        }
    }

    private fun bindSearchControls() = with(searchPage) {
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
        refreshSearchHistory()
        showPage(Page.SEARCH)
        refreshCurrentPageCard()
        focusSearchInput()
    }

    private fun closeSearchPage() {
        if (searchFromBrowser) clearSearchInput()
        dismissKeyboard()
        showPage(if (searchFromBrowser) Page.BROWSER else Page.START)
    }

    private fun clearSearchInput() = with(searchPage.query) {
        error = null
        text.clear()
    }

    private fun refreshCurrentPageCard() {
        if (activePage != Page.SEARCH) return
        val url = browserUrl.takeIf { searchFromBrowser }
        searchPage.currentPageCard.visibility = if (url == null) View.GONE else View.VISIBLE
        if (url == null) return

        val pageTitle = webView?.title?.takeIf {
            failedUrl == null && url == webView?.url && it.isNotBlank()
        }
        searchPage.currentPageTitle.text = pageTitle ?: getString(R.string.current_web_page)
        searchPage.currentPageUrl.text = url
    }

    private fun refreshSearchHistory() {
        val entries = searchHistory.entries()
        historyAdapter.replaceEntries(entries)
        searchPage.clearHistory.visibility = if (entries.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun confirmClearSearchHistory() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_clear_search_history)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_search_history) { _, _ ->
                searchHistory.clear()
                refreshSearchHistory()
            }
            .show()
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
        if (requestCode == QR_REQUEST && resultCode == RESULT_OK) {
            data?.getStringExtra(QrScanActivity.RESULT_CONTENT)?.takeUnless(String::isBlank)?.let(::showQrResult)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        downloads.onRequestPermissionsResult(requestCode, grantResults)
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
                if (address == null) searchHistory.record(content.trim())
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
        if (page != Page.SEARCH) searchFromBrowser = false
        if (page != Page.QR_RESULT) scannedContent = null
        layout.browserShell.visibility = if (isStart || isBrowser) View.VISIBLE else View.GONE
        startPage.root.visibility = if (isStart) View.VISIBLE else View.GONE
        searchPage.root.visibility = if (page == Page.SEARCH) View.VISIBLE else View.GONE
        qrResultPage.root.visibility = if (page == Page.QR_RESULT) View.VISIBLE else View.GONE
        binding.root.visibility = if (isBrowser) View.VISIBLE else View.GONE
        with(toolbar) {
            val navigationVisibility = if (isBrowser) View.VISIBLE else View.GONE
            back.visibility = navigationVisibility
            forward.visibility = navigationVisibility
            home.visibility = navigationVisibility
            val toolbarWidth = if (isBrowser) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            val params = root.layoutParams
            if (params.width != toolbarWidth) {
                params.width = toolbarWidth
                root.layoutParams = params
            }
        }
        val pageTitle = if (isBrowser) webView?.title else null
        title = pageTitle?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
        updateWindowColors(page)
    }

    @Suppress("DEPRECATION")
    private fun updateWindowColors(page: Page) {
        window.setBackgroundDrawableResource(
            when (page) {
                Page.START -> R.drawable.start_page_surface
                Page.SEARCH, Page.QR_RESULT -> R.color.start_page_background
                Page.BROWSER -> android.R.color.white
            }
        )
        if (Build.VERSION.SDK_INT < 35) {
            val background = if (page == Page.BROWSER) Color.WHITE else getColor(R.color.start_page_background)
            window.statusBarColor = background
            window.navigationBarColor = if (Build.VERSION.SDK_INT >= 27) background else Color.BLACK
        }
    }

    private fun submitQuery(input: String) {
        val query = input.trim()
        val url = BrowserAddress.resolve(query, BrowserAddress.BING_SEARCH_URL_PREFIX)
        if (url == null) {
            searchPage.query.error = getString(R.string.invalid_search_input)
            return
        }
        searchHistory.record(query)
        clearSearchInput()
        open(url)
    }

    private fun open(url: String) {
        dismissKeyboard()
        if (activePage != Page.BROWSER) showPage(Page.BROWSER)
        binding.root.requestFocus()
        loadPage(url)
    }

    private fun loadPage(url: String) {
        val view = obtainWebView()
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
            binding.address.text = url
            updateAddressDescription()
        }
        refreshCurrentPageCard()
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
        binding.connectionBadge.visibility = if (status == null) View.GONE else View.VISIBLE
        if (status != null) binding.connectionBadge.setImageResource(status.icon)
        binding.addressBar.tooltipText = status?.let { getString(it.description) }
        updateAddressDescription()
    }

    private fun updateAddressDescription() {
        binding.addressBar.contentDescription = connectionStatus?.let {
            getString(R.string.browser_address_with_connection, browserUrl.orEmpty(), getString(it.description))
        }
    }

    private fun restoreSiteIcon(view: WebView) {
        if (siteIcon != null || failedUrl != null) return
        val entry = view.copyBackForwardList().currentItem ?: return
        if (entry.url == browserUrl) setSiteIcon(entry.favicon)
    }

    private fun setSiteIcon(icon: Bitmap?) {
        if (siteIcon === icon) return
        siteIcon = icon
        if (icon == null) {
            binding.addressIcon.setImageResource(R.drawable.ic_start_search)
            searchPage.currentPageIcon.setImageResource(R.drawable.ic_start_search)
        } else {
            binding.addressIcon.setImageBitmap(icon)
            searchPage.currentPageIcon.setImageBitmap(icon)
        }
    }

    private fun updateNavigationButtons() = with(toolbar) {
        back.isEnabled = webView?.canGoBack() == true
        forward.isEnabled = webView?.canGoForward() == true
    }

    private fun setLoading(active: Boolean) {
        binding.progress.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    private fun handleHomeBack() {
        val now = SystemClock.elapsedRealtime()
        if (now < exitConfirmationDeadline) {
            exitBrowser()
            return
        }
        exitConfirmationDeadline = now + EXIT_CONFIRMATION_MS
        with(layout.exitHint) {
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
        with(layout.exitHint) {
            removeCallbacks(hideExitHint)
            animate().cancel()
            visibility = View.INVISIBLE
        }
    }

    private fun exitBrowser() {
        clearExitConfirmation()
        menuDialog?.dismiss()
        windowsDialog?.dismiss()
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            activePage == Page.START -> handleHomeBack()
            activePage == Page.SEARCH -> closeSearchPage()
            activePage == Page.QR_RESULT -> showStartPage()
            webView?.canGoBack() == true -> webView?.goBack()
            else -> showStartPage()
        }
    }

    override fun onStart() {
        super.onStart()
        webView?.onResume()
    }

    override fun onStop() {
        clearExitConfirmation()
        webView?.onPause()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        downloads.save(outState)
        outState.putString(ACTIVE_PAGE, activePage.name)
        outState.putBoolean(SEARCH_FROM_BROWSER, searchFromBrowser)
        scannedContent?.let { outState.putString(QR_CONTENT, it) }
        rememberActiveWindow(saveHistory = true)
        outState.putBundle(BROWSER_WINDOWS_STATE, windows.save())
    }

    override fun onDestroy() {
        menuDialog?.dismiss()
        windowsDialog?.dismiss()
        pageQrDialog?.dismiss()
        releaseWebView()
        super.onDestroy()
    }
}
