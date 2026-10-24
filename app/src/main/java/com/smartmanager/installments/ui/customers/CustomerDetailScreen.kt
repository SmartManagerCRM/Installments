package com.smartmanager.installments.ui.customers

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartmanager.installments.AppContainer
import com.smartmanager.installments.R
import com.smartmanager.installments.core.payments.InstallmentStatusCalculator
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.data.db.ContractRow
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.db.InstallmentRow
import com.smartmanager.installments.data.db.PaymentRow
import com.smartmanager.installments.data.db.PromiseEntity
import com.smartmanager.installments.data.db.ReminderEntity
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.ui.components.ConfirmDialog
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.KeyValueRow
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.LocalSnack
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.ReminderRequest
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.components.StatusChip
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.components.dialPhone
import com.smartmanager.installments.ui.components.errorText
import com.smartmanager.installments.ui.components.rememberReminderLauncher
import com.smartmanager.installments.ui.contracts.ContractStatusChip
import com.smartmanager.installments.ui.dashboard.StatusBadge
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class CustomerProfile(
    val customer: CustomerEntity?,
    val contracts: List<ContractRow>,
    val installments: List<InstallmentRow>,
    val payments: List<PaymentRow>,
    val promises: List<PromiseEntity>,
    val reminders: List<ReminderEntity>,
    val today: LocalDate,
    val currency: String,
)

class CustomerDetailVm(val c: AppContainer, val id: String) : ViewModel() {
    val state: StateFlow<CustomerProfile?> = combine(
        c.db.customers().observe(id), c.db.contracts().observeForCustomer(id), c.db.installments().observeForCustomer(id),
        c.db.payments().observeForCustomer(id), c.db.promises().observeForCustomer(id),
    ) { cu, co, ins, pay, pr -> arrayOf<Any?>(cu, co, ins, pay, pr) }
        .combine(c.db.reminders().observeForCustomer(id)) { a, rem -> Pair(a, rem) }
        .combine(c.ticker.today) { (a, rem), today ->
            @Suppress("UNCHECKED_CAST")
            CustomerProfile(a[0] as CustomerEntity?, a[1] as List<ContractRow>, a[2] as List<InstallmentRow>, a[3] as List<PaymentRow>, a[4] as List<PromiseEntity>, rem, today, "")
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun CustomerDetailScreen(
    id: String, embedded: Boolean = false, onBack: () -> Unit, onEdit: (String) -> Unit, onOpenContract: (String) -> Unit,
    onNewContract: (String) -> Unit, onPay: (String) -> Unit,
) {
    val vm = appVm(key = "cust-$id") { CustomerDetailVm(it, id) }
    val s by vm.state.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val readOnly = LocalReadOnly.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val remind = rememberReminderLauncher()
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirmArchive by remember { mutableStateOf(false) }
    var promiseDialog by remember { mutableStateOf(false) }
    val pdfFailed = stringResource(R.string.pdf_failed)

    Column {
        SMTopBar(stringResource(R.string.customer), onBack = onBack) {
            if (!readOnly) androidx.compose.material3.IconButton(onClick = { onEdit(id) }) { androidx.compose.material3.Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
        }
        val p = s
        val cu = p?.customer
        if (p == null || cu == null) return@Column
        val active = p.installments.filter { r -> r.b.contractStatus == Status.ACTIVE && r.b.state == Status.ACTIVE && r.b.outstandingMinor > 0 }
        val outstanding = active.sumOf { it.b.outstandingMinor }
        val overdue = active.filter { it.b.dueDate < p.today.toEpochDay() }.sumOf { it.b.outstandingMinor }
        val cur = active.firstOrNull()?.b?.currency ?: fmt.defaultCurrency
        ContentColumn {
            ErrorBanner(errorText(error))
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row { Text(cu.fullName, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f)); if (cu.status == Status.ARCHIVED) StatusChip(stringResource(R.string.filter_archived), Brand.Muted, Brand.BlueSoft) }
                    KeyValueRow(stringResource(R.string.phone), cu.phone)
                    cu.altPhone?.let { KeyValueRow(stringResource(R.string.alt_phone), it) }
                    if (cu.address.isNotBlank()) KeyValueRow(stringResource(R.string.address), cu.address)
                    cu.nationalRef?.let { KeyValueRow(stringResource(R.string.national_ref), it) }
                    if (cu.notes.isNotBlank()) KeyValueRow(stringResource(R.string.notes), cu.notes)
                    KeyValueRow(stringResource(R.string.created), fmt.dateTime(cu.createdAt))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SMCard(Modifier.weight(1f)) { Column(Modifier.padding(14.dp)) { Text(stringResource(R.string.outstanding_balance), color = Brand.Muted, style = MaterialTheme.typography.bodySmall); Text(fmt.money(outstanding, cur), style = MaterialTheme.typography.titleLarge, color = Brand.Navy) } }
                SMCard(Modifier.weight(1f)) { Column(Modifier.padding(14.dp)) { Text(stringResource(R.string.overdue_balance), color = Brand.Muted, style = MaterialTheme.typography.bodySmall); Text(fmt.money(overdue, cur), style = MaterialTheme.typography.titleLarge, color = if (overdue > 0) Brand.Red else Brand.Green) } }
            }
            // Actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { dialPhone(ctx, cu.phone) }, modifier = Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Call, null); Text(" " + stringResource(R.string.call)) }
                OutlinedButton(onClick = {
                    val first = active.minByOrNull { it.b.dueDate }
                    val type = when { first == null -> ReminderType.UPCOMING; first.b.dueDate < p.today.toEpochDay() -> ReminderType.OVERDUE; first.b.dueDate == p.today.toEpochDay() -> ReminderType.DUE_TODAY; else -> ReminderType.UPCOMING }
                    remind(ReminderRequest(cu.id, cu.fullName, cu.phone, first?.b?.contractId, type, first?.b?.outstandingMinor ?: outstanding, cur, first?.b?.dueDate, first?.b?.contractNumber ?: "", outstanding))
                }, modifier = Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Chat, null, tint = Brand.Green); Text(" WhatsApp") }
            }
            if (!readOnly && cu.status == Status.ACTIVE) {
                PrimaryButton(stringResource(R.string.new_contract), { onNewContract(id) }, icon = Icons.Filled.Description)
                if (outstanding > 0) PrimaryButton(stringResource(R.string.record_payment), { onPay(id) }, orange = true)
            }
            SecondaryButton(stringResource(R.string.account_statement), {
                scope.launch {
                    runCatching { vm.c.pdf.customerStatement(id) }.onSuccess { vm.c.pdf.share(ctx, it) }.onFailure { snack.showSnackbar(pdfFailed) }
                }
            }, icon = Icons.Filled.PictureAsPdf)

            SectionTitle(stringResource(R.string.contracts))
            if (p.contracts.isEmpty()) SMCard(Modifier.fillMaxWidth()) { EmptyState(stringResource(R.string.no_contracts)) }
            p.contracts.forEach { c ->
                SMCard(Modifier.fillMaxWidth(), onClick = { onOpenContract(c.id) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(c.number, style = MaterialTheme.typography.titleSmall)
                            Text(fmt.date(c.contractDate), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                        }
                        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                            Text(fmt.money(c.outstandingMinor, c.currency), fontWeight = FontWeight.Bold)
                            ContractStatusChip(c.status)
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.upcoming_installments))
            SMCard(Modifier.fillMaxWidth()) {
                if (active.isEmpty()) EmptyState(stringResource(R.string.no_upcoming)) else Column {
                    active.sortedBy { it.b.dueDate }.take(12).forEachIndexed { i, r ->
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(fmt.date(r.b.dueDate), style = MaterialTheme.typography.titleSmall)
                                Text(r.b.contractNumber + " · #" + r.b.sequence, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                            }
                            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                                Text(fmt.money(r.b.outstandingMinor, r.b.currency), fontWeight = FontWeight.Bold)
                                StatusBadge(InstallmentStatusCalculator.status(LocalDate.ofEpochDay(r.b.dueDate), r.b.amountMinor, r.b.paidMinor, p.today))
                            }
                        }
                        if (i < minOf(active.size, 12) - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            SectionTitle(stringResource(R.string.payment_history))
            SMCard(Modifier.fillMaxWidth()) {
                if (p.payments.isEmpty()) EmptyState(stringResource(R.string.no_payments)) else Column {
                    p.payments.take(10).forEachIndexed { i, r ->
                        Row(Modifier.fillMaxWidth().padding(14.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(r.p.receiptNumber, style = MaterialTheme.typography.titleSmall)
                                Text(fmt.dateTime(r.p.paidAt) + " · " + r.contractNumber, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                            }
                            Text(fmt.money(r.p.amountMinor, r.p.currency), fontWeight = FontWeight.Bold, color = if (r.p.status == Status.REVERSED) Brand.Muted else Brand.Green)
                        }
                        if (i < minOf(p.payments.size, 10) - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            SectionTitle(stringResource(R.string.promises)) {
                if (!readOnly && active.isNotEmpty()) TextButton(onClick = { promiseDialog = true }) { Text(stringResource(R.string.add_promise)) }
            }
            p.promises.forEach { pr -> PromiseCard(pr, readOnly, onOutcome = { o -> scope.launch { runCatching { vm.c.collection.setPromiseOutcome(pr.id, o) }.onFailure { error = it } } }) }

            if (p.reminders.isNotEmpty()) {
                SectionTitle(stringResource(R.string.reminders))
                p.reminders.take(10).forEach { r -> ReminderCard(r, readOnly) { o -> scope.launch { vm.c.collection.setReminderOutcome(r.id, o) } } }
            }

            if (!readOnly) {
                if (cu.status == Status.ACTIVE) SecondaryButton(stringResource(R.string.archive_customer), { confirmArchive = true }, icon = Icons.Filled.Archive)
                else SecondaryButton(stringResource(R.string.restore_customer), { scope.launch { vm.c.customers.setArchived(id, false) } }, icon = Icons.Filled.Unarchive)
            }
        }
        if (confirmArchive) ConfirmDialog(
            stringResource(R.string.archive_customer), stringResource(R.string.archive_customer_msg), stringResource(R.string.archive_customer),
            onConfirm = { confirmArchive = false; scope.launch { runCatching { vm.c.customers.setArchived(id, true) }.onFailure { error = it } } },
            onDismiss = { confirmArchive = false },
        )
        if (promiseDialog) PromiseDialog(
            onDismiss = { promiseDialog = false },
            onSave = { amount, date, notes ->
                promiseDialog = false
                scope.launch { runCatching { vm.c.collection.addPromise(id, active.firstOrNull()?.b?.contractId, amount, date, notes) }.onFailure { error = it } }
            },
            currency = cur,
        )
    }
}

@Composable
fun PromiseCard(pr: PromiseEntity, readOnly: Boolean, onOutcome: (String) -> Unit) {
    val fmt = LocalFmt.current
    SMCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row {
                Text(fmt.money(pr.amountMinor) + " · " + fmt.date(pr.promisedDate), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val (res, fg, bg) = when (pr.outcome) {
                    "KEPT" -> Triple(R.string.promise_kept, Brand.Green, Brand.GreenSoft)
                    "BROKEN" -> Triple(R.string.promise_broken, Brand.Red, Brand.RedSoft)
                    "CANCELLED" -> Triple(R.string.status_cancelled, Brand.Muted, Brand.BlueSoft)
                    else -> Triple(R.string.promise_pending, Brand.Orange, Brand.OrangeSoft)
                }
                StatusChip(stringResource(res), fg, bg)
            }
            if (pr.notes.isNotBlank()) Text(pr.notes, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            if (pr.outcome == Status.PENDING && !readOnly) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onOutcome("KEPT") }) { Text(stringResource(R.string.promise_kept)) }
                TextButton(onClick = { onOutcome("BROKEN") }) { Text(stringResource(R.string.promise_broken)) }
                TextButton(onClick = { onOutcome("CANCELLED") }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ReminderCard(r: ReminderEntity, readOnly: Boolean, onOutcome: (String) -> Unit) {
    val fmt = LocalFmt.current
    SMCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            // Wording is deliberate: we know WhatsApp was OPENED with a prepared message, not that it was sent or delivered.
            Text(stringResource(R.string.reminder_prepared_at, fmt.dateTime(r.preparedAt)), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            Text(r.message, style = MaterialTheme.typography.bodySmall, maxLines = 3)
            val outcomeRes = when (r.outcome) {
                "CUSTOMER_REPLIED" -> R.string.outcome_replied; "PAID" -> R.string.outcome_paid; "NO_RESPONSE" -> R.string.outcome_no_response
                "PROMISED" -> R.string.outcome_promised; else -> R.string.outcome_unknown
            }
            Text(stringResource(R.string.outcome) + ": " + stringResource(outcomeRes), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            if (!readOnly) androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("CUSTOMER_REPLIED" to R.string.outcome_replied, "PAID" to R.string.outcome_paid, "NO_RESPONSE" to R.string.outcome_no_response, "PROMISED" to R.string.outcome_promised).forEach { (k, v) ->
                    TextButton(onClick = { onOutcome(k) }) { Text(stringResource(v), style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}
