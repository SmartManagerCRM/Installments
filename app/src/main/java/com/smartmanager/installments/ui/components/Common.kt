package com.smartmanager.installments.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.AppContainer
import com.smartmanager.installments.R
import com.smartmanager.installments.data.repo.AppSettings
import com.smartmanager.installments.data.repo.LedgerError
import com.smartmanager.installments.data.repo.LedgerException
import com.smartmanager.installments.ui.theme.Brand
import com.smartmanager.installments.util.Formatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalFmt = staticCompositionLocalOf<Formatters> { error("Formatters not provided") }
val LocalSettings = staticCompositionLocalOf { AppSettings() }

/** True when the license does not allow changes (expired, not activated...). Write actions are disabled and explained. */
val LocalReadOnly = staticCompositionLocalOf { false }
val LocalSnack = staticCompositionLocalOf<androidx.compose.material3.SnackbarHostState> { error("Snackbar host not provided") }

/** Content column that is centred and width-limited on tablets instead of stretching a phone layout across the screen. */
@Composable
fun ContentColumn(modifier: Modifier = Modifier, maxWidth: Int = 720, scroll: Boolean = true, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = maxWidth.dp).fillMaxWidth().let { if (scroll) it.verticalScroll(rememberScrollState()) else it }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SMTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                // AutoMirrored icon flips automatically in RTL.
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Background, titleContentColor = Brand.Navy),
    )
}

@Composable
fun SMCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, container: Color = MaterialTheme.colorScheme.surface, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    val colors = CardDefaults.cardColors(containerColor = container)
    val elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    if (onClick != null) Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, elevation = elevation) { content() }
    else Card(modifier = modifier, shape = shape, colors = colors, elevation = elevation) { content() }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = Brand.Navy, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun StatusChip(text: String, color: Color, soft: Color) {
    Box(Modifier.background(soft, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, orange: Boolean = false, icon: ImageVector? = null) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (orange) Brand.Orange else Brand.Blue),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, if (enabled) Brand.Blue else MaterialTheme.colorScheme.outline),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Field(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true, error: String? = null, readOnly: Boolean = false, minLines: Int = 1, suffix: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(), singleLine = singleLine,
        isError = error != null, supportingText = error?.let { { Text(it) } }, readOnly = readOnly, minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = RoundedCornerShape(14.dp),
        suffix = suffix?.let { { Text(it) } }, trailingIcon = trailing,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate, onChange: (LocalDate) -> Unit, modifier: Modifier = Modifier, error: String? = null) {
    val fmt = LocalFmt.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = fmt.date(date), onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp), isError = error != null, supportingText = error?.let { { Text(it) } },
            trailingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
        )
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

private fun Modifier.matchParentSize(): Modifier = this.then(Modifier.fillMaxSize())

@Composable
fun <T> DropdownField(label: String, options: List<T>, selected: T, labelOf: @Composable (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = labelOf(selected), onValueChange = {}, readOnly = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp), trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(labelOf(o)) }, onClick = { open = false; onSelect(o) }) }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String, message: String, confirmText: String, onConfirm: () -> Unit, onDismiss: () -> Unit, destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmText, color = if (destructive) Brand.Red else Brand.Blue, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun ErrorBanner(text: String?) {
    if (text != null) Box(Modifier.fillMaxWidth().background(Brand.RedSoft, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(text, color = Brand.Red, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun InfoBanner(text: String, color: Color = Brand.Navy, soft: Color = Brand.BlueSoft) {
    Box(Modifier.fillMaxWidth().background(soft, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(text, color = color, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun ProvideAppLocals(container: AppContainer, fmt: Formatters, settings: AppSettings, readOnly: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalContainer provides container, LocalFmt provides fmt, LocalSettings provides settings, LocalReadOnly provides readOnly, content = content,
    )
}

@Composable
fun Dot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

/** Maps a failure to a localized, non-technical message. */
@Composable
fun errorText(e: Throwable?): String? {
    if (e == null) return null
    val res = when ((e as? LedgerException)?.error) {
        LedgerError.LICENSE_READ_ONLY -> R.string.err_license_read_only
        LedgerError.INVALID_AMOUNT -> R.string.err_invalid_amount
        LedgerError.CONTRACT_NOT_ACTIVE -> R.string.err_contract_not_active
        LedgerError.NOT_FOUND -> R.string.err_not_found
        LedgerError.ALLOCATION_INVALID -> R.string.err_allocation_invalid
        LedgerError.EXCEEDS_OUTSTANDING -> R.string.err_exceeds_outstanding
        LedgerError.POSSIBLE_DUPLICATE -> R.string.err_possible_duplicate
        LedgerError.SCHEDULE_INVALID -> R.string.err_schedule_invalid
        LedgerError.CONTRACT_INVALID -> R.string.err_contract_invalid
        LedgerError.ALREADY_REVERSED -> R.string.err_already_reversed
        LedgerError.REVERSAL_BLOCKED_RESCHEDULED -> R.string.err_reversal_blocked
        LedgerError.CUSTOMER_ARCHIVED -> R.string.err_customer_archived
        LedgerError.ONLY_DRAFT -> R.string.err_only_draft
        LedgerError.VALIDATION -> R.string.err_validation
        LedgerError.HAS_HISTORY -> R.string.err_has_history
        LedgerError.NOTHING_TO_SETTLE -> R.string.err_nothing_to_settle
        LedgerError.DISCOUNT_INVALID -> R.string.err_discount_invalid
        null -> R.string.err_generic
    }
    return stringResource(res)
}


/** Optional attachment/reference: a free-text reference or a file chosen with the system picker (persisted URI permission). */
@Composable
fun AttachmentField(value: String, onChange: (String) -> Unit, label: String) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onChange(uri.toString())
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Field(value, onChange, label)
        SecondaryButton(stringResource(R.string.attach_file), { picker.launch(arrayOf("*/*")) })
    }
}

fun openAttachment(ctx: android.content.Context, value: String) {
    if (!value.startsWith("content://")) return
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(value))
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
