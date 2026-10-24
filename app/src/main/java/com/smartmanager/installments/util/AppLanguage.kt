package com.smartmanager.installments.util

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

object AppLanguage {
    val supported = listOf("ar", "fr", "en")

    /** Language the user picked in Settings (per-app locale) or the system language; limited to ar/fr/en. */
    fun current(): String {
        val picked = AppCompatDelegate.getApplicationLocales()
        val lang = (if (!picked.isEmpty) picked[0]?.language else null) ?: Locale.getDefault().language
        return if (lang in supported) lang else "en"
    }

    /** A context whose resources use the app language (needed when strings are resolved outside an Activity, e.g. PDFs). */
    fun localized(base: Context): Context {
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(Locale.forLanguageTag(current()))
        return base.createConfigurationContext(cfg)
    }

    /** First run: if the phone language is not Arabic/French/English, start in Arabic (primary market). */
    fun defaultForFirstRun(): String? {
        val sys = Locale.getDefault().language
        return if (sys in supported) null else "ar"
    }
}
