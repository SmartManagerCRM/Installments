package com.smartmanager.installments.util

import com.smartmanager.installments.core.money.Currencies
import com.smartmanager.installments.core.money.MoneyMath
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Locale-aware formatting. Digits are always Western (0-9), which is what Tunisian/Maghreb retailers use in Arabic UIs;
 * the grouping/decimal separators follow the language (French: "2 400,000"). Money strings are wrapped in Unicode
 * directional isolates so "2,400.000 TND" never gets reordered inside right-to-left text.
 */
class Formatters(val language: String, val dateFormat: String, val defaultCurrency: String) {
    private val locale: Locale = Locale.forLanguageTag(language)
    private val safePattern: String = runCatching { DateTimeFormatter.ofPattern(dateFormat, locale); dateFormat }.getOrDefault("dd/MM/yyyy")
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern(safePattern, locale)
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("$safePattern HH:mm", locale)

    private val group: Char? = if (language.startsWith("fr")) '\u202F' else ','
    private val decimal: Char = if (language.startsWith("fr")) ',' else '.'

    fun amount(minor: Long, currency: String = defaultCurrency): String =
        MoneyMath.format(minor, Currencies.of(currency).decimals, group, decimal)

    private val arabicSymbols = mapOf(
        "TND" to "د.ت", "SAR" to "ر.س", "DZD" to "د.ج", "MAD" to "د.م", "EGP" to "ج.م", "AED" to "د.إ",
        "QAR" to "ر.ق", "KWD" to "د.ك", "LYD" to "د.ل", "JOD" to "د.أ", "EUR" to "€", "USD" to "$",
    )

    /** Display symbol: Arabic UI uses the Arabic symbol (د.ت), French uses DT for the dinar, otherwise the ISO code. */
    fun symbol(currency: String): String = when {
        language.startsWith("ar") -> arabicSymbols[currency] ?: currency
        language.startsWith("fr") && currency == "TND" -> "DT"
        currency == "EUR" -> "\u20AC"
        currency == "USD" -> "$"
        else -> currency
    }

    /** Amount + symbol wrapped in a directional isolate so it is never reordered inside surrounding text. */
    fun money(minor: Long, currency: String = defaultCurrency): String {
        val open = if (language.startsWith("ar")) '\u2067' else '\u2066'
        return "$open${amount(minor, currency)}\u00A0${symbol(currency)}\u2069"
    }

    /** Editable text: no grouping, '.' decimal. */
    fun editable(minor: Long, currency: String = defaultCurrency): String = MoneyMath.format(minor, Currencies.of(currency).decimals, null, '.')

    fun parseMoney(text: String, currency: String = defaultCurrency): Long? = MoneyMath.parse(text, Currencies.of(currency).decimals)

    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFmt)
    fun date(d: LocalDate): String = d.format(dateFmt)
    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(millis).atZone(zone).format(dateTimeFmt)
    fun number(n: Number): String = n.toString()
}
