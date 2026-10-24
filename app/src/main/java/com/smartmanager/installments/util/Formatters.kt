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
    private val dateFmt: DateTimeFormatter = runCatching { DateTimeFormatter.ofPattern(dateFormat, locale) }
        .getOrElse { DateTimeFormatter.ofPattern("dd/MM/yyyy", locale) }
    private val dateTimeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("$dateFormat HH:mm", locale)

    private val group: Char? = if (language.startsWith("fr")) ' ' else ','
    private val decimal: Char = if (language.startsWith("fr")) ',' else '.'

    fun amount(minor: Long, currency: String = defaultCurrency): String =
        MoneyMath.format(minor, Currencies.of(currency).decimals, group, decimal)

    fun money(minor: Long, currency: String = defaultCurrency): String = "⁦${amount(minor, currency)} $currency⁩"

    /** Editable text: no grouping, '.' decimal. */
    fun editable(minor: Long, currency: String = defaultCurrency): String = MoneyMath.format(minor, Currencies.of(currency).decimals, null, '.')

    fun parseMoney(text: String, currency: String = defaultCurrency): Long? = MoneyMath.parse(text, Currencies.of(currency).decimals)

    fun date(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dateFmt)
    fun date(d: LocalDate): String = d.format(dateFmt)
    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(millis).atZone(zone).format(dateTimeFmt)
    fun number(n: Number): String = n.toString()
}
