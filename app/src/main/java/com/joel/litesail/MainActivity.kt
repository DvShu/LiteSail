package com.joel.litesail

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
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
import com.joel.litesail.databinding.ActivityMainBinding
import com.joel.litesail.databinding.ViewBrowserBinding
import com.joel.litesail.databinding.ViewSearchPageBinding
import com.joel.litesail.databinding.ViewStartPageBinding

private const val ACTIVE_PAGE = "active_page"
private const val BROWSER_URL = "browser_url"
private const val START_PAGE_VISIBLE = "start_page_visible"
private val HTTP_PREFIX = Regex("^\\s*https?://", RegexOption.IGNORE_CASE)

class MainActivity : Activity() {
    private enum class Page { START, SEARCH, BROWSER }

    private lateinit var binding: ViewBrowserBinding
    private lateinit var startPage: ViewStartPageBinding
    private lateinit var searchPage: ViewSearchPageBinding
    private lateinit var searchHistory: SearchHistory
    private lateinit var historyAdapter: SearchHistoryAdapter
    private var activePage = Page.START
    private var requestedUrl: String? = null
    private var loading = false
    private var failedUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = ActivityMainBinding.inflate(layoutInflater)
        binding = layout.browser
        startPage = layout.startPage
        searchPage = layout.searchPage
        searchHistory = SearchHistory(this)
        setContentView(layout.root)
        if (Build.VERSION.SDK_INT >= 35) {
            layout.root.setOnApplyWindowInsetsListener { _, insets ->
                val systemAreas = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                val homeArea = insets.getInsets(systemAreas)
                val browserArea = insets.getInsets(systemAreas or WindowInsets.Type.ime())
                startPage.root.setPadding(homeArea.left, homeArea.top, homeArea.right, homeArea.bottom)
                searchPage.root.setPadding(browserArea.left, browserArea.top, browserArea.right, browserArea.bottom)
                binding.root.setPadding(browserArea.left, browserArea.top, browserArea.right, browserArea.bottom)
                insets
            }
        }
        configureWebView()
        bindControls()
        bindSearchControls()
        val browserRestored = savedInstanceState?.let(binding.webView::restoreState) != null
        requestedUrl = savedInstanceState?.getString(BROWSER_URL)
        val legacyBrowserVisible = savedInstanceState?.getBoolean(START_PAGE_VISIBLE, !browserRestored) == false
        val pageName = savedInstanceState?.getString(ACTIVE_PAGE)
        val page = Page.entries.firstOrNull { it.name == pageName }
            ?: if (legacyBrowserVisible) Page.BROWSER else Page.START
        if (page == Page.SEARCH) showSearchPage() else showPage(page)
        if (page == Page.BROWSER && !browserRestored) open(requestedUrl ?: BrowserAddress.HOME)
        updateAddress(binding.webView.url)
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
                if (BrowserAddress.isWebScheme(request.url.scheme)) return false
                if (request.isForMainFrame) {
                    Toast.makeText(this@MainActivity, R.string.unsupported_link, Toast.LENGTH_SHORT).show()
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                failedUrl = null
                binding.pageError.visibility = View.GONE
                binding.progress.progress = 0
                setLoading(true)
                updateAddress(url)
                updateNavigationButtons()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                updateAddress(url)
                updateNavigationButtons()
            }

            override fun onPageFinished(view: WebView, url: String) {
                updateAddress(failedUrl ?: view.url ?: url)
                updateNavigationButtons()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame || !BrowserAddress.isWebScheme(request.url.scheme)) return
                failedUrl = request.url.toString()
                binding.pageError.visibility = View.VISIBLE
                updateAddress(failedUrl)
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
            }
        }
    }

    private fun bindControls() = with(binding) {
        startPage.search.setOnClickListener { showSearchPage() }
        go.setOnClickListener { submitAddress() }
        address.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_GO) return@setOnEditorActionListener false
            submitAddress()
            true
        }
        back.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        home.setOnClickListener { showStartPage() }
        retry.setOnClickListener { failedUrl?.let(::open) }
        reload.setOnClickListener {
            if (loading) {
                webView.stopLoading()
                setLoading(false)
                updateNavigationButtons()
            } else {
                failedUrl?.let(::open) ?: webView.reload()
            }
        }
    }

    private fun bindSearchControls() = with(searchPage) {
        closeSearch.setOnClickListener { showStartPage() }
        submitSearch.setOnClickListener { submitQuery(query.text.toString()) }
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

    private fun showSearchPage() {
        refreshSearchHistory()
        showPage(Page.SEARCH)
        focusSearchInput()
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

    private fun showPage(page: Page) {
        activePage = page
        startPage.root.visibility = if (page == Page.START) View.VISIBLE else View.GONE
        searchPage.root.visibility = if (page == Page.SEARCH) View.VISIBLE else View.GONE
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
                Page.SEARCH -> R.color.start_page_background
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
        searchPage.query.error = null
        searchPage.query.text.clear()
        open(url)
    }

    private fun submitAddress() {
        val url = BrowserAddress.resolve(binding.address.text.toString())
        if (url == null) {
            binding.address.error = getString(R.string.invalid_address)
            return
        }
        open(url)
    }

    private fun open(url: String) {
        dismissKeyboard()
        if (activePage != Page.BROWSER) showPage(Page.BROWSER)
        binding.root.requestFocus()
        binding.address.error = null
        requestedUrl = url
        updateAddress(url)
        binding.webView.loadUrl(url)
    }

    private fun dismissKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun updateAddress(url: String?) {
        if (url != null && !binding.address.hasFocus()) binding.address.setText(url)
    }

    private fun updateNavigationButtons() = with(binding) {
        back.isEnabled = webView.canGoBack()
        forward.isEnabled = webView.canGoForward()
    }

    private fun setLoading(active: Boolean) {
        loading = active
        binding.progress.visibility = if (active) View.VISIBLE else View.INVISIBLE
        binding.reload.setText(if (active) R.string.stop else R.string.refresh)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            activePage == Page.START -> super.onBackPressed()
            activePage == Page.SEARCH -> showStartPage()
            binding.webView.canGoBack() -> binding.webView.goBack()
            else -> showStartPage()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ACTIVE_PAGE, activePage.name)
        val browserUrl = failedUrl ?: binding.webView.url ?: requestedUrl
        if (browserUrl != null) {
            outState.putString(BROWSER_URL, browserUrl)
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
