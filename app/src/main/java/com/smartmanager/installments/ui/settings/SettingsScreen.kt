package com.smartmanager.installments.ui.settings

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.smartmanager.installments.BuildConfig
import com.smartmanager.installments.R
import com.smartmanager.installments.core.money.Currencies
import com.smartmanager.installments.core.payments.AllocationRule
import com.smartmanager.installments.core.reminders.ReminderTemplates
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.core.schedule.Frequency
import com.smartmanager.installments.data.repo.SettingKeys
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.DropdownField
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalSettings
import com.smartmanager.installments.ui.components.LocalSnack
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.contracts.frequencyLabel
import com.smartmanager.installments.ui.license.LicenseScreen
import com.smartmanager.installments.ui.theme.Brand
import com.smartmanager.installments.util.AppLanguage
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

@Composable
fun SettingsMenuScreen(onBack: () -> Unit, onSection: (String) -> Unit, onProducts: () -> Unit) {
    val c = LocalContainer.current
    var hasDemo by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasDemo = c.db.bulk().demoCustomerCount() > 0 }
    Column {
        SMTopBar(stringResource(R.string.settings), onBack = onBack)
        ContentColumn {
            MenuItem(Icons.Filled.Business, R.string.business_profile) { onSection("business") }
            MenuItem(Icons.Filled.Inventory, R.string.products_catalog, onProducts)
            MenuItem(Icons.Filled.Backup, R.string.backup_restore) { onSection("backup") }
            MenuItem(Icons.Filled.Language, R.string.language) { onSection("language") }
            MenuItem(Icons.Filled.Chat, R.string.reminder_templates) { onSection("templates") }
            MenuItem(Icons.Filled.VerifiedUser, R.string.license_info) { onSection("license") }
            if (BuildConfig.DEMO_DATA_ENABLED || hasDemo) MenuItem(Icons.Filled.Science, R.string.demo_data) { onSection("demo") }
            MenuItem(Icons.Filled.Info, R.string.about) { onSection("about") }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: Int, onClick: () -> Unit) {
    SMCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Brand.Blue)
            Text(stringResource(label), Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.titleSmall)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.Muted)
        }
    }
}

@Composable
fun SettingsSectionScreen(section: String, onBack: () -> Unit) {
    val title = when (section) {
        "business" -> R.string.business_profile; "backup" -> R.string.backup_restore; "language" -> R.string.language
        "templates" -> R.string.reminder_templates; "license" -> R.string.license_info; "demo" -> R.string.demo_data; else -> R.string.about
    }
    Column {
        SMTopBar(stringResource(title), onBack = onBack)
        when (section) {
            "business" -> BusinessSection()
            "backup" -> BackupSection()
            "language" -> LanguageSection()
            "templates" -> TemplatesSection()
            "license" -> LicenseScreen()
            "demo" -> DemoSection()
            else -> AboutSection()
        }
    }
}

@Composable
private fun BusinessSection() {
    val c = LocalContainer.current
    val s = LocalSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val saved = stringResource(R.string.saved)
    var name by remember(s) { mutableStateOf(s.businessName) }
    var phone by remember(s) { mutableStateOf(s.businessPhone) }
    var address by remember(s) { mutableStateOf(s.businessAddress) }
    var currency by remember(s) { mutableStateOf(s.currency) }
    var dateFormat by remember(s) { mutableStateOf(s.dateFormat) }
    var footer by remember(s) { mutableStateOf(s.receiptFooter) }
    var rule by remember(s) { mutableStateOf(s.allocationRule) }
    var freq by remember(s) { mutableStateOf(s.defaultFrequency) }
    var cc by remember(s) { mutableStateOf(s.countryCode) }
    var terms by remember(s) { mutableStateOf(s.contractTerms) }
    var round by remember(s) { mutableStateOf(s.roundToWholeUnit) }
    val defaultTerms = stringResource(R.string.default_contract_terms)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) scope.launch {
            runCatching {
                val bmp = ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                val scale = 256f / maxOf(bmp.width, bmp.height)
                val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
                val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.PNG, 90, out)
                c.settings.put(SettingKeys.LOGO, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
            }
        }
    }

    ContentColumn {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val logo = remember(s.logoBase64) { s.logoBase64?.let { runCatching { Base64.decode(it, Base64.DEFAULT).let { b -> BitmapFactory.decodeByteArray(b, 0, b.size) } }.getOrNull() } }
            if (logo != null) Image(logo.asImageBitmap(), null, Modifier.size(64.dp))
            SecondaryButton(stringResource(R.string.choose_logo), { pick.launch("image/*") }, Modifier.weight(1f))
        }
        Field(name, { name = it }, stringResource(R.string.business_name))
        Field(phone, { phone = it }, stringResource(R.string.phone), keyboard = KeyboardType.Phone)
        Field(address, { address = it }, stringResource(R.string.address), singleLine = false, minLines = 2)
        DropdownField(stringResource(R.string.currency), Currencies.supported.map { it.code }, currency, { it }, { currency = it })
        InfoBanner(stringResource(R.string.currency_note))
        DropdownField(stringResource(R.string.date_format), listOf("dd/MM/yyyy", "MM/dd/yyyy", "yyyy-MM-dd", "dd MMM yyyy"), dateFormat, { it }, { dateFormat = it })
        Field(cc, { cc = it.filter { ch -> ch.isDigit() } }, stringResource(R.string.country_code), keyboard = KeyboardType.Number, suffix = "+")
        DropdownField(stringResource(R.string.allocation_rule), AllocationRule.entries.toList(), rule, { stringResource(if (it == AllocationRule.OLDEST_FIRST) R.string.alloc_auto_oldest else R.string.alloc_auto_latest) }, { rule = it })
        DropdownField(stringResource(R.string.default_frequency), Frequency.entries.filter { it != Frequency.CUSTOM }, freq, { frequencyLabel(it) }, { freq = it })
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(round, { round = it }); Text(stringResource(R.string.round_whole_units), Modifier.weight(1f))
        }
        Field(footer, { footer = it }, stringResource(R.string.receipt_footer), singleLine = false, minLines = 2)
        SectionTitle(stringResource(R.string.contract_terms))
        InfoBanner(stringResource(R.string.terms_legal_notice), Brand.Orange, Brand.OrangeSoft)
        Field(terms.ifBlank { defaultTerms }.let { if (terms.isBlank()) "" else it }, { terms = it }, stringResource(R.string.contract_terms), singleLine = false, minLines = 6)
        if (terms.isBlank()) Text(stringResource(R.string.terms_using_default), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        PrimaryButton(stringResource(R.string.save), {
            scope.launch {
                val put = c.settings::put
                put(SettingKeys.BUSINESS_NAME, name.trim()); put(SettingKeys.BUSINESS_PHONE, phone.trim()); put(SettingKeys.BUSINESS_ADDRESS, address.trim())
                put(SettingKeys.CURRENCY, currency); put(SettingKeys.DATE_FORMAT, dateFormat); put(SettingKeys.RECEIPT_FOOTER, footer)
                put(SettingKeys.ALLOCATION_RULE, rule.name); put(SettingKeys.DEFAULT_FREQUENCY, freq.name); put(SettingKeys.COUNTRY_CODE, cc)
                put(SettingKeys.CONTRACT_TERMS, terms); put(SettingKeys.ROUND_WHOLE, if (round) "1" else "0")
                snack.showSnackbar(saved)
            }
        })
    }
}

@Composable
private fun LanguageSection() {
    val current = AppLanguage.current()
    val names = mapOf("ar" to "العربية", "fr" to "Français", "en" to "English")
    ContentColumn {
        AppLanguage.supported.forEach { code ->
            SMCard(Modifier.fillMaxWidth(), onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code)) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = current == code, onClick = { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code)) })
                    Text(names.getValue(code), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun TemplatesSection() {
    val c = LocalContainer.current
    val s = LocalSettings.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val saved = stringResource(R.string.saved)
    val lang = AppLanguage.current()
    ContentColumn {
        InfoBanner(stringResource(R.string.templates_hint, ReminderTemplates.placeholders.joinToString(" ") { "{$it}" }))
        ReminderType.entries.forEach { t ->
            var text by remember(s, t) { mutableStateOf(s.template(lang, t)) }
            val label = when (t) {
                ReminderType.UPCOMING -> R.string.tpl_upcoming; ReminderType.DUE_TODAY -> R.string.tpl_due_today; ReminderType.OVERDUE -> R.string.tpl_overdue
                ReminderType.PAYMENT_CONFIRMATION -> R.string.tpl_payment_confirmation; ReminderType.PROMISE_FOLLOWUP -> R.string.tpl_promise
            }
            Field(text, { text = it }, stringResource(label), singleLine = false, minLines = 3)
            Row {
                TextButton(onClick = { scope.launch { c.settings.put(SettingKeys.template(t, lang), text); snack.showSnackbar(saved) } }) { Text(stringResource(R.string.save)) }
                TextButton(onClick = { text = ReminderTemplates.default(lang, t); scope.launch { c.settings.put(SettingKeys.template(t, lang), text) } }) { Text(stringResource(R.string.reset_default)) }
            }
        }
    }
}

@Composable
private fun AboutSection() {
    ContentColumn {
        SMCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.version_x, BuildConfig.VERSION_NAME))
                Text(stringResource(R.string.about_offline))
                Text(stringResource(R.string.about_backup_notice), color = Brand.Orange)
            }
        }
    }
}
