package com.smartmanager.installments.data.repo

import com.smartmanager.installments.core.payments.AllocationRule
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.core.schedule.Frequency
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.db.SettingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    val businessName: String = "",
    val businessPhone: String = "",
    val businessAddress: String = "",
    val currency: String = "TND",
    val dateFormat: String = "dd/MM/yyyy",
    val receiptFooter: String = "",
    val allocationRule: AllocationRule = AllocationRule.OLDEST_FIRST,
    val defaultFrequency: Frequency = Frequency.MONTHLY,
    val countryCode: String = "216",
    val contractTerms: String = "",
    val roundToWholeUnit: Boolean = true,
    val logoBase64: String? = null,
    val templates: Map<String, String> = emptyMap(),
) {
    fun template(lang: String, type: ReminderType): String =
        templates["tpl.${type.name}.${lang.take(2)}"] ?: com.smartmanager.installments.core.reminders.ReminderTemplates.default(lang, type)
}

object SettingKeys {
    const val BUSINESS_NAME = "business.name"
    const val BUSINESS_PHONE = "business.phone"
    const val BUSINESS_ADDRESS = "business.address"
    const val CURRENCY = "currency"
    const val DATE_FORMAT = "dateFormat"
    const val RECEIPT_FOOTER = "receipt.footer"
    const val ALLOCATION_RULE = "allocation.rule"
    const val DEFAULT_FREQUENCY = "default.frequency"
    const val COUNTRY_CODE = "whatsapp.countryCode"
    const val CONTRACT_TERMS = "contract.terms"
    const val ROUND_WHOLE = "schedule.roundToWholeUnit"
    const val LOGO = "business.logo.b64"
    const val TEMPLATE_PREFIX = "tpl."
    fun template(type: ReminderType, lang: String) = "$TEMPLATE_PREFIX${type.name}.${lang.take(2)}"
}

class SettingsRepository(private val db: AppDatabase) {
    val settings: Flow<AppSettings> = db.settings().observeAll().map { rows -> parse(rows.associate { it.key to it.value }) }

    suspend fun current(): AppSettings = parse(db.settings().all().associate { it.key to it.value })

    suspend fun put(key: String, value: String) = db.settings().put(SettingEntity(key, value))

    private fun parse(m: Map<String, String>): AppSettings {
        val d = AppSettings()
        return AppSettings(
            businessName = m[SettingKeys.BUSINESS_NAME] ?: d.businessName,
            businessPhone = m[SettingKeys.BUSINESS_PHONE] ?: d.businessPhone,
            businessAddress = m[SettingKeys.BUSINESS_ADDRESS] ?: d.businessAddress,
            currency = m[SettingKeys.CURRENCY] ?: d.currency,
            dateFormat = m[SettingKeys.DATE_FORMAT] ?: d.dateFormat,
            receiptFooter = m[SettingKeys.RECEIPT_FOOTER] ?: d.receiptFooter,
            allocationRule = m[SettingKeys.ALLOCATION_RULE]?.let { runCatching { AllocationRule.valueOf(it) }.getOrNull() } ?: d.allocationRule,
            defaultFrequency = m[SettingKeys.DEFAULT_FREQUENCY]?.let { runCatching { Frequency.valueOf(it) }.getOrNull() } ?: d.defaultFrequency,
            countryCode = m[SettingKeys.COUNTRY_CODE] ?: d.countryCode,
            contractTerms = m[SettingKeys.CONTRACT_TERMS] ?: d.contractTerms,
            roundToWholeUnit = m[SettingKeys.ROUND_WHOLE]?.let { it == "1" } ?: d.roundToWholeUnit,
            logoBase64 = m[SettingKeys.LOGO]?.takeIf { it.isNotBlank() },
            templates = m.filterKeys { it.startsWith(SettingKeys.TEMPLATE_PREFIX) },
        )
    }
}
