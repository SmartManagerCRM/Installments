package com.smartmanager.installments.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.R
import com.smartmanager.installments.data.repo.RestorePreview
import com.smartmanager.installments.data.repo.RestoreRejected
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun BackupSection() {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val fmt = LocalFmt.current
    val scope = rememberCoroutineScope()
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var restorePass by remember { mutableStateOf("") }
    var needsPass by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val okBackup = stringResource(R.string.backup_ok)
    val failBackup = stringResource(R.string.backup_failed)
    val okRestore = stringResource(R.string.restore_ok)

    fun reason(e: Throwable): String = stringResource0(ctx, when ((e as? RestoreRejected)?.reason) {
        RestoreRejected.Reason.NOT_A_BACKUP -> R.string.rst_not_backup
        RestoreRejected.Reason.CORRUPT -> R.string.rst_corrupt
        RestoreRejected.Reason.INTEGRITY_FAILED -> R.string.rst_integrity
        RestoreRejected.Reason.UNSUPPORTED_VERSION -> R.string.rst_version
        RestoreRejected.Reason.NEWER_SCHEMA -> R.string.rst_newer
        RestoreRejected.Reason.PASSPHRASE_REQUIRED -> R.string.rst_pass_required
        RestoreRejected.Reason.WRONG_PASSPHRASE -> R.string.rst_wrong_pass
        RestoreRejected.Reason.INCONSISTENT_DATA -> R.string.rst_inconsistent
        null -> R.string.rst_failed
    })

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            busy = true
            val r = runCatching {
                val bytes = c.backup.createBackup(passphrase.takeIf { it.isNotEmpty() }?.toCharArray())
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
            }
            busy = false
            message = if (r.isSuccess) true to okBackup else false to failBackup
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                pendingBytes = bytes
                preview = c.backup.inspect(bytes)
                needsPass = preview!!.header.encrypted
                restorePass = ""
            }.onFailure { message = false to reason(it); pendingBytes = null; preview = null }
            busy = false
        }
    }

    ContentColumn {
        InfoBanner(stringResource(R.string.backup_explain), Brand.Navy, Brand.BlueSoft)
        message?.let { (ok, text) -> if (ok) InfoBanner("✓ $text", Brand.Green, Brand.GreenSoft) else ErrorBanner(text) }
        SectionTitle(stringResource(R.string.create_backup))
        Field(passphrase, { passphrase = it }, stringResource(R.string.backup_passphrase_optional))
        InfoBanner(stringResource(R.string.backup_passphrase_warning), Brand.Orange, Brand.OrangeSoft)
        PrimaryButton(stringResource(R.string.create_backup), { create.launch("smartmanager-backup-${LocalDate.now()}.smibak") }, enabled = !busy, orange = true)
        SectionTitle(stringResource(R.string.restore_backup))
        PrimaryButton(stringResource(R.string.choose_backup_file), { open.launch(arrayOf("*/*")) }, enabled = !busy)
    }

    val p = preview
    val bytes = pendingBytes
    if (p != null && bytes != null) AlertDialog(
        onDismissRequest = { preview = null; pendingBytes = null },
        title = { Text(stringResource(R.string.restore_backup)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.restore_preview, fmt.dateTime(p.header.createdAtEpochMs), p.header.tableCounts["customers"] ?: 0, p.header.tableCounts["contracts"] ?: 0, p.header.tableCounts["payments"] ?: 0))
                Text(stringResource(R.string.restore_warning), color = Brand.Red)
                if (needsPass) Field(restorePass, { restorePass = it }, stringResource(R.string.backup_passphrase))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                preview = null
                scope.launch {
                    busy = true
                    val r = runCatching { c.backup.restore(bytes, restorePass.takeIf { needsPass }?.toCharArray()) }
                    busy = false; pendingBytes = null
                    message = r.fold({ true to okRestore }, { false to reason(it) })
                    if (r.isSuccess) c.license.refresh()
                }
            }) { Text(stringResource(R.string.replace_my_data), color = Brand.Red) }
        },
        dismissButton = { TextButton(onClick = { preview = null; pendingBytes = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun stringResource0(ctx: android.content.Context, id: Int) = ctx.getString(id)
