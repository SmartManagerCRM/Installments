package com.smartmanager.installments

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.smartmanager.installments.ui.AppRoot
import com.smartmanager.installments.util.AppLanguage
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val container get() = (application as App).container

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First launch on a phone that is not set to ar/fr/en: start in Arabic (the primary market); the user can change it in Settings.
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppLanguage.defaultForFirstRun()?.let { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it)) }
        }
        enableEdgeToEdge()
        setContent {
            AppRoot(container, calculateWindowSizeClass(this))
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate the date (overdue flips at midnight / after a date change) and the license (expiry, clock changes).
        container.ticker.refresh()
        lifecycleScope.launch { container.license.refresh() }
    }
}
