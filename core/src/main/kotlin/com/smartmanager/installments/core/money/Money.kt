package com.smartmanager.installments.core.money

/**
 * Money is ALWAYS stored as a [Long] count of the currency's minor unit
 * (e.g. millimes for TND, halalas for SAR). No floating point is used anywhere in the financial logic.
 */
data class CurrencyInfo(val code: String, val decimals: Int) {
    init {
        require(decimals in 0..4) { "decimals out of range" }
    }

    /** Number of minor units in one whole currency unit (1 TND = 1000). */
    val unit: Long get() = pow10(decimals)
}

object Currencies {
    /**
     * Decimals follow ISO 4217 except where the minor unit is not used in practice (IQD, SYP, LBP, YER, IRR): those are
     * handled in whole units so amounts are typed and shown the way shops actually price goods.
     */
    val supported: List<CurrencyInfo> = listOf(
        // Arab world first
        CurrencyInfo("TND", 3), CurrencyInfo("DZD", 2), CurrencyInfo("MAD", 2), CurrencyInfo("LYD", 3), CurrencyInfo("EGP", 2),
        CurrencyInfo("SAR", 2), CurrencyInfo("AED", 2), CurrencyInfo("QAR", 2), CurrencyInfo("KWD", 3), CurrencyInfo("BHD", 3),
        CurrencyInfo("OMR", 3), CurrencyInfo("JOD", 3), CurrencyInfo("IQD", 0), CurrencyInfo("SYP", 0), CurrencyInfo("LBP", 0),
        CurrencyInfo("YER", 0), CurrencyInfo("SDG", 2), CurrencyInfo("MRU", 2),
        // Neighbours and major world currencies
        CurrencyInfo("TRY", 2), CurrencyInfo("ILS", 2), CurrencyInfo("IRR", 0), CurrencyInfo("PKR", 2), CurrencyInfo("AFN", 2),
        CurrencyInfo("INR", 2), CurrencyInfo("EUR", 2), CurrencyInfo("USD", 2), CurrencyInfo("GBP", 2), CurrencyInfo("CHF", 2),
        CurrencyInfo("CAD", 2), CurrencyInfo("AUD", 2), CurrencyInfo("NZD", 2), CurrencyInfo("JPY", 0), CurrencyInfo("CNY", 2),
        CurrencyInfo("KRW", 0), CurrencyInfo("RUB", 2), CurrencyInfo("UAH", 2), CurrencyInfo("PLN", 2), CurrencyInfo("CZK", 2),
        CurrencyInfo("HUF", 2), CurrencyInfo("RON", 2), CurrencyInfo("SEK", 2), CurrencyInfo("NOK", 2), CurrencyInfo("DKK", 2),
        CurrencyInfo("ZAR", 2), CurrencyInfo("NGN", 2), CurrencyInfo("KES", 2), CurrencyInfo("GHS", 2), CurrencyInfo("XOF", 0),
        CurrencyInfo("XAF", 0), CurrencyInfo("BRL", 2), CurrencyInfo("MXN", 2), CurrencyInfo("SGD", 2), CurrencyInfo("MYR", 2),
        CurrencyInfo("IDR", 2), CurrencyInfo("THB", 2), CurrencyInfo("AZN", 2), CurrencyInfo("GEL", 2),
    )

    /** Unknown codes fall back to 2 decimals so that data from a newer app version never crashes. */
    fun of(code: String): CurrencyInfo =
        supported.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: CurrencyInfo(code.uppercase(), 2)
}

internal fun pow10(n: Int): Long {
    var r = 1L
    repeat(n) { r *= 10 }
    return r
}

object MoneyMath {
    private const val MAX_DIGITS = 15
    private val GROUPED = Regex("\\d{1,3}([.,]\\d{3})+")

    /**
     * Parses user input such as "1,234.50", "1 234,5", "٢٠٠" into minor units.
     * Returns null for anything ambiguous, negative, or with more fraction digits than the currency allows.
     * (No silent rounding of user input.)
     */
    fun parse(input: String, decimals: Int): Long? {
        val s = normalizeDigits(input).filter { !it.isWhitespace() && it != '\u00A0' && it != '\u202F' }
        if (s.isEmpty()) return null
        if (!s.all { it.isDigit() || it == '.' || it == ',' }) return null
        val dots = s.count { it == '.' }
        val commas = s.count { it == ',' }
        val decimalSep: Char? = when {
            dots > 0 && commas > 0 -> {
                val last = maxOf(s.lastIndexOf('.'), s.lastIndexOf(','))
                val sepChar = s[last]
                // The decimal separator must be the last separator and occur only once.
                if (s.count { it == sepChar } != 1) return null
                sepChar
            }
            dots + commas == 0 -> null
            dots + commas == 1 -> {
                val sepChar = if (dots == 1) '.' else ','
                val after = s.length - s.indexOf(sepChar) - 1
                if (after <= decimals && after > 0) sepChar
                else if (after == 3 && decimals < 3) null // grouping, e.g. "1,234" with 2 decimals
                else if (after == 0) return null
                else return null
            }
            else -> null // repeated same separator => grouping only
        }
        val intPart: String
        val fracPart: String
        val rawInt: String
        if (decimalSep == null) {
            rawInt = s
            fracPart = ""
        } else {
            val idx = s.lastIndexOf(decimalSep)
            rawInt = s.substring(0, idx)
            fracPart = s.substring(idx + 1)
            if (!fracPart.all { it.isDigit() } || fracPart.length > decimals) return null
        }
        // Grouping separators are only valid between digit groups of three ("1,234,567").
        if (rawInt.any { !it.isDigit() } && !GROUPED.matches(rawInt)) return null
        intPart = rawInt.filter { it.isDigit() }
        if (intPart.isEmpty() && fracPart.isEmpty()) return null
        if (intPart.length > MAX_DIGITS - decimals) return null
        val whole = if (intPart.isEmpty()) 0L else intPart.toLong()
        val frac = if (decimals == 0) 0L else fracPart.padEnd(decimals, '0').toLong()
        return whole * pow10(decimals) + frac
    }

    fun format(minor: Long, decimals: Int, groupSep: Char? = ',', decimalSep: Char = '.'): String {
        val neg = minor < 0
        val abs = if (minor == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(minor)
        val unit = pow10(decimals)
        val whole = (abs / unit).toString()
        val grouped = if (groupSep == null) whole else buildString {
            whole.forEachIndexed { i, c ->
                if (i > 0 && (whole.length - i) % 3 == 0) append(groupSep)
                append(c)
            }
        }
        val frac = if (decimals == 0) "" else decimalSep + (abs % unit).toString().padStart(decimals, '0')
        return (if (neg) "-" else "") + grouped + frac
    }

    /** Converts Arabic-Indic and Persian digits (and Arabic separators) to ASCII. */
    fun normalizeDigits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '٠'..'٩' -> '0' + (c - '٠')
                in '۰'..'۹' -> '0' + (c - '۰')
                '٫' -> '.'
                '٬' -> ','
                else -> c
            }
        )
    }
}
