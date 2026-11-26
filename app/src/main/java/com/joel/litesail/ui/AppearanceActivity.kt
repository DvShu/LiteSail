package com.joel.litesail.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import com.joel.litesail.R

abstract class AppearanceActivity : Activity() {
    private enum class Brand(val key: String, val theme: Int) {
        TEAL("teal", R.style.Theme_LiteSail)
    }

    private lateinit var brand: Brand
    protected var isDarkMode = false
        private set

    override fun attachBaseContext(newBase: Context) {
        val preferences = newBase.getSharedPreferences(PREFERENCES, MODE_PRIVATE)
        val brandKey = preferences.getString(BRAND, null)
        brand = Brand.entries.firstOrNull { it.key == brandKey } ?: Brand.TEAL
        isDarkMode = preferences.getBoolean(DARK_MODE, false)
        val nightMode = if (isDarkMode) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        super.attachBaseContext(newBase)
        applyOverrideConfiguration(Configuration().apply { uiMode = nightMode })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(brand.theme)
        super.onCreate(savedInstanceState)
    }

    protected fun switchDisplayMode() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
            .putBoolean(DARK_MODE, !isDarkMode)
            .apply()
        recreate()
    }

    private companion object {
        const val PREFERENCES = "appearance"
        const val BRAND = "brand"
        const val DARK_MODE = "dark_mode"
    }
}
