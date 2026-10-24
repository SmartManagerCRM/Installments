package com.smartmanager.installments.ui.license

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartmanager.installments.BuildConfig
import com.smartmanager.installments.R
import com.smartmanager.installments.core.licensing.LicenseCheck
import com.smartmanager.installments.core.licensing.LicenseStatus
import com.smartmanager.installments.core.licensing.LicenseType
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.KeyValueRow
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

@Composable
fun statusMessage(s: LicenseStatus): String = stringResource(
    when (s) {
        LicenseStatus.NOT_ACTIVATED -> R.string.lic_not_activated
        LicenseStatus.VALID -> R.string.lic_valid
        LicenseStatus.EXPIRED -> R.string.lic_expired
        LicenseStatus.NOT_YET_VALID -> R.string.lic_not_yet_valid
        LicenseStatus.WRONG_DEVICE -> R.string.lic_wrong_device
        LicenseStatus.INVALID_SIGNATURE -> R.string.lic_invalid_signature
        LicenseStatus.MALFORMED -> R.string.lic_malformed
        LicenseStatus.UNSUPPORTED_VERSION -> R.string.lic_unsupported
        LicenseStatus.WRONG_PRODUCT -> R.string.lic_wrong_product
        LicenseStatus.UNKNOWN_KEY -> R.string.lic_unknown_key
        LicenseStatus.CLOCK_ROLLBACK -> R.string.lic_clock_rollback
    },
)

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("SmartManager", text))
}

/** Device id + activation request (copy/share) and license import (file or pasted text). Shared by the gate and Settings. */
@Composable
private fun ActivationPanel(onResult: (LicenseCheck) -> Unit) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pasted by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<LicenseCheck?>(null) }
    val request = remember { c.license.activationRequest() }
    val copied = stringResource(R.string.copied)

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes().take(200_000).toByteArray(), Charsets.UTF_8) } }.getOrNull() }
            val r = if (text == null) LicenseCheck(LicenseStatus.MALFORMED) else c.license.importLicense(text)
            result = r; onResult(r)
        }
    }

    SectionTitle(stringResource(R.string.step1_send_request))
    SMCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyValueRow(stringResource(R.string.device_id), c.license.deviceId, bold = true)
            Text(request, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, maxLines = 4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copy(ctx, request); android.widget.Toast.makeText(ctx, copied, android.widget.Toast.LENGTH_SHORT).show() }, Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.ContentCopy, null); Text(" " + stringResource(R.string.copy)) }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, request) }
                    ctx.startActivity(Intent.createChooser(send, null))
                }, Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Share, null); Text(" " + stringResource(R.string.share)) }
            }
        }
    }
    SectionTitle(stringResource(R.string.step2_import_license))
    PrimaryButton(stringResource(R.string.import_license_file), { pick.launch(arrayOf("*/*")) }, icon = Icons.Filled.FileOpen)
    Field(pasted, { pasted = it }, stringResource(R.string.or_paste_license), singleLine = false, minLines = 2)
    SecondaryButton(stringResource(R.string.activate), {
        scope.launch { val r = c.license.importLicense(pasted); result = r; onResult(r) }
    }, enabled = pasted.isNotBlank())
    result?.let { r -> if (r.status != LicenseStatus.VALID) ErrorBanner(statusMessage(r.status)) else InfoBanner("✓ " + stringResource(R.string.lic_activated_ok), Brand.Green, Brand.GreenSoft) }
}

@Composable
fun ActivationScreen(onContinueReadOnly: () -> Unit) {
    val c = LocalContainer.current
    val lic by c.license.state.collectAsStateWithLifecycle()
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = Brand.Background) {
        ContentColumn {
            Spacer(Modifier.height(12.dp))
            Image(painterResource(R.drawable.ic_logo), null, Modifier.size(72.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, color = Brand.Navy)
            Text(stringResource(R.string.activation_title), style = MaterialTheme.typography.titleLarge)
            if (lic.status != LicenseStatus.NOT_ACTIVATED) ErrorBanner(statusMessage(lic.status))
            else InfoBanner(stringResource(R.string.activation_intro))
            ActivationPanel(onResult = { })
            if (BuildConfig.ALLOW_TEST_LICENSE_KEY) InfoBanner("DEBUG BUILD: accepts licenses signed with the published TEST key only (license-generator/test-keys). Never distribute debug builds.", Brand.Orange, Brand.OrangeSoft)
            SecondaryButton(stringResource(R.string.continue_read_only), onContinueReadOnly)
            Text(stringResource(R.string.read_only_explain), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
    }
}

@Composable
fun LicenseScreen() {
    val c = LocalContainer.current
    val fmt = LocalFmt.current
    val lic by c.license.state.collectAsStateWithLifecycle()
    var showImport by remember { mutableStateOf(false) }
    ContentColumn {
        val p = lic.payload
        SMCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(statusMessage(lic.status), style = MaterialTheme.typography.titleMedium, color = if (lic.status == LicenseStatus.VALID) Brand.Green else Brand.Red)
                if (p != null && lic.status != LicenseStatus.WRONG_DEVICE) {
                    KeyValueRow(stringResource(R.string.customer), p.customerName)
                    KeyValueRow(stringResource(R.string.license_id), p.licenseId + if (p.revision > 1) " (r${p.revision})" else "")
                    KeyValueRow(stringResource(R.string.license_type), stringResource(if (p.type == LicenseType.LIFETIME) R.string.lic_type_lifetime else R.string.lic_type_duration))
                    KeyValueRow(stringResource(R.string.valid_from), fmt.date(Instant.ofEpochSecond(p.startAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()))
                    p.expiresAt?.let { KeyValueRow(stringResource(R.string.valid_until), fmt.date(Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate())) }
                    lic.daysRemaining?.let { d -> KeyValueRow(stringResource(R.string.days_remaining), if (d >= 0) d.toString() else "0", if (lic.expiringSoon || d < 0) Brand.Red else Brand.Green, true) }
                }
                KeyValueRow(stringResource(R.string.device_id), c.license.deviceId)
            }
        }
        if (lic.expiringSoon) InfoBanner(stringResource(R.string.lic_expiring_soon, lic.daysRemaining ?: 0), Brand.Orange, Brand.OrangeSoft)
        if (!lic.allowsWrites) InfoBanner(stringResource(R.string.read_only_explain), Brand.Navy, Brand.BlueSoft)
        SecondaryButton(stringResource(if (showImport) R.string.hide else R.string.renew_or_replace), { showImport = !showImport })
        if (showImport) ActivationPanel(onResult = { })
        InfoBanner(stringResource(R.string.device_replacement_help))
    }
}
