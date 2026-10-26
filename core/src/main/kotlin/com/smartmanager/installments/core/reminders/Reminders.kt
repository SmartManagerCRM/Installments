package com.smartmanager.installments.core.reminders

import java.net.URLEncoder

enum class ReminderType { UPCOMING, DUE_TODAY, OVERDUE, PAYMENT_CONFIRMATION, PROMISE_FOLLOWUP }

/**
 * Placeholders usable in editable templates:
 * {customer_name} {amount} {date} {business_name} {contract_number} {balance} {receipt_number}
 */
object ReminderTemplates {
    val placeholders = listOf("customer_name", "amount", "date", "business_name", "contract_number", "balance", "receipt_number")

    fun default(lang: String, type: ReminderType): String = when (lang.lowercase().take(2)) {
        "ar" -> when (type) {
            ReminderType.UPCOMING -> "مرحباً {customer_name}، نود تذكيركم بأن لديكم قسطاً بقيمة {amount} مستحق الدفع بتاريخ {date}. يرجى التواصل معنا عند الحاجة. شكراً لثقتكم بنا. {business_name}"
            ReminderType.DUE_TODAY -> "مرحباً {customer_name}، نذكّركم بأن قسطاً بقيمة {amount} مستحق الدفع اليوم ({date}). يرجى التواصل معنا إذا كنتم قد قمتم بالدفع بالفعل. شكراً لثقتكم بنا. {business_name}"
            ReminderType.OVERDUE -> "مرحباً {customer_name}، نود تذكيركم بأن لديكم قسطاً بقيمة {amount} كان مستحق الدفع بتاريخ {date}. يرجى التواصل معنا إذا كنتم قد قمتم بالدفع بالفعل. شكراً لثقتكم بنا. {business_name}"
            ReminderType.PAYMENT_CONFIRMATION -> "مرحباً {customer_name}، تم استلام دفعتكم بقيمة {amount} بتاريخ {date} (وصل رقم {receipt_number}). المبلغ المتبقي: {balance}. شكراً لكم. {business_name}"
            ReminderType.PROMISE_FOLLOWUP -> "مرحباً {customer_name}، نذكّركم بوعدكم بدفع مبلغ {amount} بتاريخ {date}. يرجى التواصل معنا لتأكيد ذلك. شكراً لثقتكم بنا. {business_name}"
        }
        "fr" -> when (type) {
            ReminderType.UPCOMING -> "Bonjour {customer_name}, nous vous rappelons qu'une échéance de {amount} est prévue le {date}. N'hésitez pas à nous contacter si besoin. Merci pour votre confiance. {business_name}"
            ReminderType.DUE_TODAY -> "Bonjour {customer_name}, nous vous rappelons qu'une échéance de {amount} est due aujourd'hui ({date}). Si vous avez déjà effectué le paiement, veuillez ne pas tenir compte de ce rappel. Merci pour votre confiance. {business_name}"
            ReminderType.OVERDUE -> "Bonjour {customer_name}, nous vous rappelons qu'une échéance de {amount} était prévue le {date}. Si vous avez déjà effectué le paiement, veuillez ne pas tenir compte de ce rappel. Merci pour votre confiance. {business_name}"
            ReminderType.PAYMENT_CONFIRMATION -> "Bonjour {customer_name}, nous avons bien reçu votre paiement de {amount} le {date} (reçu n° {receipt_number}). Solde restant : {balance}. Merci. {business_name}"
            ReminderType.PROMISE_FOLLOWUP -> "Bonjour {customer_name}, nous vous rappelons votre engagement de payer {amount} le {date}. Merci de nous contacter pour confirmer. {business_name}"
        }
        else -> when (type) {
            ReminderType.UPCOMING -> "Hello {customer_name}, this is a reminder that an installment of {amount} is due on {date}. Please contact us if you need anything. Thank you for your trust. {business_name}"
            ReminderType.DUE_TODAY -> "Hello {customer_name}, this is a reminder that an installment of {amount} is due today ({date}). If you have already paid, please ignore this message. Thank you for your trust. {business_name}"
            ReminderType.OVERDUE -> "Hello {customer_name}, this is a reminder that an installment of {amount} was due on {date}. If you have already paid, please ignore this message. Thank you for your trust. {business_name}"
            ReminderType.PAYMENT_CONFIRMATION -> "Hello {customer_name}, we received your payment of {amount} on {date} (receipt {receipt_number}). Remaining balance: {balance}. Thank you. {business_name}"
            ReminderType.PROMISE_FOLLOWUP -> "Hello {customer_name}, this is a reminder of your promise to pay {amount} on {date}. Please contact us to confirm. {business_name}"
        }
    }

    /** Replaces {key} tokens. Unknown tokens are left untouched so a typo is visible to the retailer. */
    fun render(template: String, values: Map<String, String>): String =
        // Braces are escaped on purpose: Android's ICU regex rejects an unescaped "}" (the JVM accepts it).
        Regex("\\{([a-z_]+)\\}").replace(template) { m -> values[m.groupValues[1]] ?: m.value }
}

object WhatsAppLink {
    /**
     * Builds a click-to-chat URL (https://wa.me/<number>?text=<encoded>). Nothing is ever sent automatically:
     * the retailer reviews and sends the prefilled message manually inside WhatsApp.
     *
     * @param defaultCountryCode digits only, e.g. "216" for Tunisia, used for numbers entered in national format.
     * @return null when the phone number cannot be turned into a plausible international number.
     */
    fun normalizePhone(raw: String, defaultCountryCode: String): String? {
        val ascii = com.smartmanager.installments.core.money.MoneyMath.normalizeDigits(raw)
        val hasPlus = ascii.trim().startsWith("+")
        var digits = ascii.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val cc = defaultCountryCode.filter { it.isDigit() }
        digits = when {
            hasPlus -> digits
            digits.startsWith("00") -> digits.drop(2)
            cc.isNotEmpty() && digits.startsWith(cc) && digits.length >= cc.length + 7 -> digits
            else -> cc + digits.trimStart('0')
        }
        return digits.takeIf { it.length in 8..15 }
    }

    fun build(rawPhone: String, defaultCountryCode: String, message: String): String? {
        val phone = normalizePhone(rawPhone, defaultCountryCode) ?: return null
        val encoded = URLEncoder.encode(message, "UTF-8").replace("+", "%20")
        return "https://wa.me/$phone?text=$encoded"
    }
}
