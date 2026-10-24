package com.smartmanager.installments

import com.smartmanager.installments.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class FormattersTests {
    @Test fun frenchUsesNarrowSpaceGroupingCommaDecimalAndDt() {
        val f = Formatters("fr", "dd/MM/yyyy", "TND")
        assertEquals("2\u202F400,000", f.amount(2_400_000))
        assertEquals("\u20662\u202F400,000\u00A0DT\u2069", f.money(2_400_000))
    }

    @Test fun arabicKeepsWesternDigitsAndUsesArabicSymbol() {
        val f = Formatters("ar", "dd/MM/yyyy", "TND")
        assertEquals("2,400.000", f.amount(2_400_000))
        assertEquals("\u20672,400.000\u00A0\u062F.\u062A\u2069", f.money(2_400_000)) // د.ت inside an RTL isolate
        assertEquals("15/03/2026", f.date(LocalDate.of(2026, 3, 15)))
    }

    @Test fun englishUsesIsoCode() {
        val f = Formatters("en", "dd/MM/yyyy", "TND")
        assertEquals("\u20662,400.000\u00A0TND\u2069", f.money(2_400_000))
        assertEquals("SAR", f.symbol("SAR"))
    }

    @Test fun currencyPrecisionComesFromTheCurrencyNotTheCode() {
        val f = Formatters("en", "yyyy-MM-dd", "SAR")
        assertEquals("1,234.50", f.amount(123_450, "SAR"))
        assertEquals("1.234", f.amount(1_234, "TND"))
        assertEquals("2026-03-15", f.date(LocalDate.of(2026, 3, 15)))
    }

    @Test fun parsesUserInputPerCurrency() {
        val f = Formatters("ar", "dd/MM/yyyy", "TND")
        assertEquals(200_000L, f.parseMoney("200", "TND"))
        assertEquals(20_000L, f.parseMoney("٢٠٠", "SAR"))
        assertNull(f.parseMoney("200.1234", "SAR")) // too many decimals: rejected, never rounded
        assertEquals("200.000", f.editable(200_000, "TND"))
    }

    @Test fun invalidDatePatternFallsBackSafely() {
        assertEquals("15/03/2026", Formatters("en", "not a pattern {{", "TND").date(LocalDate.of(2026, 3, 15)))
    }
}
