package com.smartmanager.installments.ui.contracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.ui.Alignment
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
import com.smartmanager.installments.core.payments.InstallmentState
import com.smartmanager.installments.core.payments.InstallmentStatusCalculator
import com.smartmanager.installments.data.db.AuditEventEntity
import com.smartmanager.installments.data.db.ContractEntity
import com.smartmanager.installments.data.db.ContractItemEntity
import com.smartmanager.installments.data.db.InstallmentBalanceView
import com.smartmanager.installments.data.db.PaymentEntity
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.ui.components.ConfirmDialog
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.KeyValueRow
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.LocalSnack
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.components.errorText
import com.smartmanager.installments.ui.dashboard.StatusBadge
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ContractDetail(
    val contract: ContractEntity?,
    val customerName: String,
    val items: List<ContractItemEntity>,
    val balances: List<InstallmentBalanceView>,
    val payments: List<PaymentEntity>,
    val audit: List<AuditEventEntity>,
    val collected: Long,
    val today: LocalDate,
)

class ContractDetailVm(val c: AppContainer, val id: String) : ViewModel() {
    val state: StateFlow<ContractDetail?> = combine(
        c.db.contracts().observe(id), c.db.contracts().observeItems(id), c.db.contracts().observeAllBalances(id),
        c.db.payments().observeForContract(id), c.db.audit().observeFor(id),
    ) { co, it, ba, pa, au -> arrayOf<Any?>(co, it, ba, pa, au) }
        .combine(c.db.contracts().observeCollected(id)) { a, col -> a to col }
        .combine(c.ticker.today) { (a, col), today ->
            @Suppress("UNCHECKED_CAST")
            ContractDetail(a[0] as ContractEntity?, "", a[1] as List<ContractItemEntity>, a[2] as List<InstallmentBalanceView>, a[3] as List<PaymentEntity>, a[4] as List<AuditEventEntity>, col ?: 0L, today)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun ContractDetailScreen(
    id: String, onBack: () -> Unit, onOpenCustomer: (String) -> Unit, onPay: (String) -> Unit, onSettle: (String) -> Unit,
    onReschedule: (String) -> Unit, onEditDraft: (String) -> Unit, onOpenPayment: (String) -> Unit,
) {
    val vm = appVm(key = "contract-$id") { ContractDetailVm(it, id) }
    val s by vm.state.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val readOnly = LocalReadOnly.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var error by remember { mutableStateOf<Throwable?>(null) }
    var cancelDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    val pdfFailed = stringResource(R.string.pdf_failed)

    Column {
        SMTopBar(stringResource(R.string.contract), onBack = onBack)
        val d = s
        val c = d?.contract ?: return@Column
        androidx.compose.runtime.LaunchedEffect(c.customerId) { customerName = vm.c.db.customers().get(c.customerId)?.fullName ?: "" }
        val current = d.balances.filter { it.state == Status.ACTIVE }
        val outstanding = if (c.status == Status.ACTIVE) current.sumOf { it.outstandingMinor } else 0L
        ContentColumn {
            ErrorBanner(errorText(error))
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.number, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                        ContractStatusChip(c.status)
                    }
                    TextButton(onClick = { onOpenCustomer(c.customerId) }) { Text(customerName, style = MaterialTheme.typography.titleMedium) }
                    KeyValueRow(stringResource(R.string.contract_date), fmt.date(c.contractDate))
                    KeyValueRow(stringResource(R.string.original_price), fmt.money(c.originalPriceMinor, c.currency))
                    if (c.discountMinor > 0) KeyValueRow(stringResource(R.string.discount), "- " + fmt.money(c.discountMinor, c.currency))
                    KeyValueRow(stringResource(R.string.down_payment), fmt.money(c.downPaymentMinor, c.currency))
                    KeyValueRow(stringResource(R.string.financed_amount), fmt.money(c.financedMinor, c.currency), bold = true)
                    KeyValueRow(stringResource(R.string.total_collected), fmt.money(d.collected, c.currency), Brand.Green)
                    KeyValueRow(stringResource(R.string.outstanding_balance), fmt.money(outstanding, c.currency), if (outstanding > 0) Brand.Red else Brand.Green, bold = true)
                    if (c.rescheduleCount > 0) Text(stringResource(R.string.rescheduled_times, c.rescheduleCount), color = Brand.Orange, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (!readOnly) when (c.status) {
                Status.ACTIVE -> {
                    PrimaryButton(stringResource(R.string.record_payment), { onPay(id) }, orange = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onSettle(id) }, Modifier.weight(1f)) { Text(stringResource(R.string.early_settlement), maxLines = 1) }
                        OutlinedButton(onClick = { onReschedule(id) }, Modifier.weight(1f)) { Text(stringResource(R.string.reschedule), maxLines = 1) }
                    }
                }
                Status.DRAFT -> {
                    PrimaryButton(stringResource(R.string.edit_draft), { onEditDraft(id) })
                    SecondaryButton(stringResource(R.string.delete_draft), { deleteDialog = true })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { runCatching { vm.c.pdf.contract(id) }.onSuccess { vm.c.pdf.share(ctx, it) }.onFailure { snack.showSnackbar(pdfFailed) } }
                }, Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Share, null); Text(" " + stringResource(R.string.share_pdf), maxLines = 1) }
                OutlinedButton(onClick = {
                    scope.launch { runCatching { vm.c.pdf.contract(id) }.onSuccess { vm.c.pdf.print(ctx, it, c.number) }.onFailure { snack.showSnackbar(pdfFailed) } }
                }, Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Print, null); Text(" " + stringResource(R.string.print), maxLines = 1) }
            }

            SectionTitle(stringResource(R.string.products))
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    d.items.forEach { it0 -> KeyValueRow("${it0.quantity} × ${it0.description}", fmt.money(it0.quantity * it0.unitPriceMinor, c.currency)) }
                }
            }

            SectionTitle(stringResource(R.string.installment_schedule))
            SMCard(Modifier.fillMaxWidth()) {
                Column {
                    d.balances.forEachIndexed { i, b ->
                        val st = InstallmentStatusCalculator.status(
                            LocalDate.ofEpochDay(b.dueDate), b.amountMinor, b.paidMinor, d.today,
                            runCatching { InstallmentState.valueOf(b.state) }.getOrDefault(InstallmentState.ACTIVE),
                        )
                        val muted = b.state != Status.ACTIVE
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${b.sequence}", fontWeight = FontWeight.Bold, color = Brand.Muted, modifier = Modifier.padding(end = 10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fmt.date(b.dueDate), color = if (muted) Brand.Muted else Brand.Navy, style = MaterialTheme.typography.titleSmall)
                                if (b.paidMinor > 0) Text(stringResource(R.string.paid_of, fmt.amount(b.paidMinor, b.currency), fmt.amount(b.amountMinor, b.currency)), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(fmt.money(b.amountMinor, b.currency), fontWeight = FontWeight.Bold, color = if (muted) Brand.Muted else Brand.Navy)
                                StatusBadge(st)
                            }
                        }
                        if (i < d.balances.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            SectionTitle(stringResource(R.string.payments_title))
            SMCard(Modifier.fillMaxWidth()) {
                if (d.payments.isEmpty()) EmptyState(stringResource(R.string.no_payments)) else Column {
                    d.payments.forEachIndexed { i, p ->
                        Row(Modifier.fillMaxWidth().padding(14.dp).let { m -> m }, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                TextButton(onClick = { onOpenPayment(p.id) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(p.receiptNumber, style = MaterialTheme.typography.titleSmall) }
                                Text(fmt.dateTime(p.paidAt) + if (p.status == Status.REVERSED) " · " + stringResource(R.string.reversed) else "", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                            }
                            Text(fmt.money(p.amountMinor, p.currency), fontWeight = FontWeight.Bold, color = if (p.status == Status.REVERSED) Brand.Muted else Brand.Green)
                        }
                        if (i < d.payments.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            if (d.audit.isNotEmpty()) {
                SectionTitle(stringResource(R.string.history))
                SMCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        d.audit.take(15).forEach { a ->
                            Text(fmt.dateTime(a.at) + " · " + a.action.replace('_', ' ').lowercase(), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                        }
                    }
                }
            }

            if (!readOnly && c.status == Status.ACTIVE) SecondaryButton(stringResource(R.string.cancel_contract), { cancelDialog = true })
        }
        if (cancelDialog) androidx.compose.material3.AlertDialog(
            onDismissRequest = { cancelDialog = false }, title = { Text(stringResource(R.string.cancel_contract)) },
            text = { Column { Text(stringResource(R.string.cancel_contract_msg)); Field(reason, { reason = it }, stringResource(R.string.reason)) } },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    cancelDialog = false
                    scope.launch { runCatching { vm.c.ledger.cancelContract(id, reason) }.onFailure { error = it } }
                }) { Text(stringResource(R.string.cancel_contract), color = Brand.Red) }
            },
            dismissButton = { TextButton(onClick = { cancelDialog = false }) { Text(stringResource(R.string.back)) } },
        )
        if (deleteDialog) ConfirmDialog(
            stringResource(R.string.delete_draft), stringResource(R.string.delete_draft_msg), stringResource(R.string.delete), destructive = true,
            onConfirm = { deleteDialog = false; scope.launch { runCatching { vm.c.ledger.deleteDraft(id) }.onSuccess { onBack() }.onFailure { error = it } } },
            onDismiss = { deleteDialog = false },
        )
    }
}
