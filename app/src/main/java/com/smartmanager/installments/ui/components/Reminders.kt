package com.smartmanager.installments.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.smartmanager.installments.R
import com.smartmanager.installments.core.reminders.ReminderTemplates
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.core.reminders.WhatsAppLink
import kotlinx.coroutines.launch
import java.util.Locale

data class ReminderRequest(
    val customerId: String,
    val customerName: String,
    val phone: String,
    val contractId: String?,
    val type: ReminderType,
    val amountMinor: Long,
    val currency: String,
    val dateEpochDay: Long?,
    val contractNumber: String = "",
    val balanceMinor: Long = 0,
    val receiptNumber: String = "",
)

/**
 * Click-to-chat only: builds the personalised message from the (editable) template and opens WhatsApp with the text
 * PREFILLED. Nothing is sent automatically; the retailer reviews and presses send. We only log that the chat was opened.
 */
@Composable
fun rememberReminderLauncher(): (ReminderRequest) -> Unit {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val settings = LocalSettings.current
    val fmt = LocalFmt.current
    val snack = LocalSnack.current
    val scope = rememberCoroutineScope()
    val invalidPhone = stringResource(R.string.msg_invalid_phone)
    val noWhatsapp = stringResource(R.string.msg_whatsapp_missing)
    val lang = remember { Locale.getDefault().language }
    return { r ->
        val values = mapOf(
            "customer_name" to r.customerName,
            "amount" to fmt.money(r.amountMinor, r.currency),
            "date" to (r.dateEpochDay?.let { fmt.date(it) } ?: ""),
            "business_name" to settings.businessName,
            "contract_number" to r.contractNumber,
            "balance" to fmt.money(r.balanceMinor, r.currency),
            "receipt_number" to r.receiptNumber,
        )
        val message = ReminderTemplates.render(settings.template(lang, r.type), values).trim()
        val url = WhatsAppLink.build(r.phone, settings.countryCode, message)
        scope.launch {
            if (url == null) {
                snack.showSnackbar(invalidPhone)
            } else {
                try {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    c.collection.recordReminderPrepared(r.customerId, r.contractId, r.type, message)
                } catch (e: ActivityNotFoundException) {
                    snack.showSnackbar(noWhatsapp)
                }
            }
        }
    }
}

fun dialPhone(ctx: Context, phone: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone.filter { it.isDigit() || it == '+' })).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
