package com.joel.litesail.main

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Selection
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.joel.litesail.R
import com.joel.litesail.browser.BrowserAddress
import com.joel.litesail.databinding.ActivityMainBinding
import com.joel.litesail.databinding.ViewBrowserBinding
import com.joel.litesail.databinding.ViewQrResultBinding
import com.joel.litesail.databinding.ViewSearchPageBinding
import com.joel.litesail.databinding.ViewStartPageBinding
import com.joel.litesail.scan.ui.QrScanActivity
import com.joel.litesail.search.SearchHistory
import com.joel.litesail.search.SearchHistoryAdapter

private const val ACTIVE_PAGE = "active_page"
private const val BROWSER_URL = "browser_url"
private const val SEARCH_FROM_BROWSER = "search_from_browser"
private const val START_PAGE_VISIBLE = "start_page_visible"
private const val QR_CONTENT = "qr_content"
private const val QR_REQUEST = 101
private val HTTP_PREFIX = Regex("^\\s*https?://", RegexOption.IGNORE_CASE)

class MainActivity : Activity() {
    private enum class Page { START, SEARCH, BROWSER, QR_RESULT }

    private lateinit var binding: ViewBrowserBinding
    private lateinit var startPage: ViewStartPageBinding
    private lateinit var searchPage: ViewSearchPageBinding
    private lateinit var qrResultPage: ViewQrResultBinding
    private lateinit var searchHistory: SearchHistory
    private lateinit var historyAdapter: SearchHistoryAdapter
    private var activePage = Page.START
    private var browserUrl: String? = null
    private var siteIcon: Bitmap? = null
    private var searchFromBrowser = false
    private var failedUrl: String? = null
    private var scannedContent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = ActivityMainBinding.inflate(layoutInflater)
        binding = layout.browser
        startPage = layout.startPage
        searchPage = layout.searchPage
        qrResultPage = layout.qrResult
        searchHistory = SearchHistory(this)
        setContentView(layout.root)
        if (Build.VERSION.SDK_INT >= 35) {
            layout.root.setOnApplyWindowInsetsListener { _, insets ->
                val systemAreas = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val homeArea = insets.getInsets(systemAreas)
                val browserArea = insets.getInsets(systemAreas or WindowInsets.Type.ime())
                startPage.root.setPadding(homeArea.left, homeArea.top, homeArea.right, homeArea.bottom)
                searchPage.root.setPadding(browserArea.left, browserArea.top, browserArea.right, browserArea.bottom)
                qrResultPage.root.setPadding(homeArea.left, homeArea.top, homeArea.right, homeArea.bottom)
                binding.root.setPadding(browserArea.left, browserArea.top, browserArea.right, browserArea.bottom)
                insets
            }
        }
        configureWebView()
        bindControls()
        bindSearchControls()
        qrResultPage.closeQrResult.setOnClickListener { showStartPage() }
        val browserRestored = savedInstanceState?.let(binding.webView::restoreState) != null
        val savedBrowserUrl = savedInstanceState?.getString(BROWSER_URL)
        val legacyBrowserVisible = savedInstanceState?.getBoolean(START_PAGE_VISIBLE, !browserRestored) == false
        val pageName = savedInstanceState?.getString(ACTIVE_PAGE)
        val page = Page.entries.firstOrNull { it.name == pageName }
            ?: if (legacyBrowserVisible) Page.BROWSER else Page.START
        val restoreBrowserSearch = page == Page.SEARCH && savedInstanceState?.getBoolean(SEARCH_FROM_BROWSER) == true
        if (!browserRestored && (page == Page.BROWSER || restoreBrowserSearch)) {
            loadPage(savedBrowserUrl ?: BrowserAddress.HOME)
        } else {
            (savedBrowserUrl ?: binding.webView.url)?.let(::updateAddress)
            if (browserRestored) restoreSiteIcon(binding.webView)
        }
        when (page) {
            Page.SEARCH -> showSearchPage(fromBrowser = restoreBrowserSearch)
            Page.QR_RESULT -> savedInstanceState?.getString(QR_CONTENT)?.let(::showQrResult) ?: showStartPage()
            else -> showPage(page)
        }
        updateNavigationButtons()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView) {
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
                failedUrl = null
                setSiteIcon(null)
                binding.pageError.visibility = View.GONE
                binding.progress.progress = 0
                setLoading(true)
                updateAddress(url)
                updateNavigationButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                updateAddress(url)
                restoreSiteIcon(view)
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView, url: String) {
                updateAddress(failedUrl ?: view.url ?: url)
                restoreSiteIcon(view)
                updateNavigationButtons()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || !BrowserAddress.isWebScheme(request.url.scheme)) return
                val url = request.url.toString()
                failedUrl = url
                setSiteIcon(null)
                binding.pageError.visibility = View.VISIBLE
                updateAddress(url)
                updateNavigationButtons()
                setLoading(false)
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.progress.progress = newProgress
                if (newProgress == 100) setLoading(false)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                if (binding.root.visibility == View.VISIBLE) {
                    this@MainActivity.title = title?.takeIf { it.isNotBlank() } ?: getString(R.string.app_name)
                }
                refreshCurrentPageCard()
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                val url = view.url ?: return
                if (failedUrl == null && url == browserUrl) setSiteIcon(icon)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun bindControls() = with(binding) {
        startPage.search.setOnClickListener { showSearchPage() }
        startPage.scanQr.setOnClickListener {
            startActivityForResult(Intent(this@MainActivity, QrScanActivity::class.java), QR_REQUEST)
        }
        addressBar.setOnClickListener {
            clearSearchInput()
            showSearchPage(fromBrowser = true)
        }
        back.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        home.setOnClickListener { showStartPage() }
        retry.setOnClickListener { failedUrl?.let(::open) }
        reload.setOnClickListener { failedUrl?.let(::open) ?: webView.reload() }
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

        val pageTitle = binding.webView.title?.takeIf {
            failedUrl == null && url == binding.webView.url && it.isNotBlank()
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
        if (requestCode == QR_REQUEST && resultCode == RESULT_OK) {
            data?.getStringExtra(QrScanActivity.RESULT_CONTENT)?.takeUnless(String::isBlank)?.let(::showQrResult)
        }
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
        activePage = page
        if (page != Page.SEARCH) searchFromBrowser = false
        if (page != Page.QR_RESULT) scannedContent = null
        startPage.root.visibility = if (page == Page.START) View.VISIBLE else View.GONE
        searchPage.root.visibility = if (page == Page.SEARCH) View.VISIBLE else View.GONE
        qrResultPage.root.visibility = if (page == Page.QR_RESULT) View.VISIBLE else View.GONE
        binding.root.visibility = if (page == Page.BROWSER) View.VISIBLE else View.GONE
        val pageTitle = if (page == Page.BROWSER) binding.webView.title else null
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
        setSiteIcon(null)
        updateAddress(url)
        binding.webView.loadUrl(url)
    }

    private fun dismissKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun updateAddress(url: String) {
        if (browserUrl != url) setSiteIcon(null)
        browserUrl = url
        binding.address.text = url
        refreshCurrentPageCard()
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

    private fun updateNavigationButtons() = with(binding) {
        back.isEnabled = webView.canGoBack()
        forward.isEnabled = webView.canGoForward()
    }

    private fun setLoading(active: Boolean) {
        binding.progress.visibility = if (active) View.VISIBLE else View.INVISIBLE
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            activePage == Page.START -> super.onBackPressed()
            activePage == Page.SEARCH -> closeSearchPage()
            activePage == Page.QR_RESULT -> showStartPage()
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> showStartPage()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ACTIVE_PAGE, activePage.name)
        outState.putBoolean(SEARCH_FROM_BROWSER, searchFromBrowser)
        scannedContent?.let { outState.putString(QR_CONTENT, it) }
        browserUrl?.let { url ->
            outState.putString(BROWSER_URL, url)
            binding.webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        with(binding.webView) {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        super.onDestroy()
    }
}
