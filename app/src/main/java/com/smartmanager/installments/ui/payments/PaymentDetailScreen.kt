package com.smartmanager.installments.ui.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.R
import com.smartmanager.installments.data.db.InstallmentBalanceView
import com.smartmanager.installments.data.db.PaymentAllocationEntity
import com.smartmanager.installments.data.db.PaymentKind
import com.smartmanager.installments.data.db.PaymentRow
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.KeyValueRow
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.LocalSnack
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.components.errorText
import com.smartmanager.installments.ui.contracts.methodLabel
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun PaymentDetailScreen(id: String, onBack: () -> Unit, onOpenContract: (String) -> Unit) {
    val c = LocalContainer.current
    val fmt = LocalFmt.current
    val readOnly = LocalReadOnly.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    var row by remember { mutableStateOf<PaymentRow?>(null) }
    var allocs by remember { mutableStateOf<List<Pair<PaymentAllocationEntity, InstallmentBalanceView?>>>(emptyList()) }
    var reverseDialog by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val pdfFailed = stringResource(R.string.pdf_failed)

    LaunchedEffect(id, refresh) {
        row = c.db.payments().observeRow(id).first()
        allocs = c.db.payments().allocations(id).map { it to c.db.contracts().balanceOf(it.installmentId) }
    }
    Column {
        SMTopBar(stringResource(R.string.receipt), onBack = onBack)
        val r = row ?: return@Column
        val p = r.p
        ContentColumn {
            ErrorBanner(errorText(error))
            if (p.status == Status.REVERSED) InfoBanner(stringResource(R.string.payment_reversed_info, p.reversedAt?.let { fmt.dateTime(it) } ?: "", p.reversalReason ?: ""), Brand.Red, Brand.RedSoft)
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    KeyValueRow(stringResource(R.string.receipt_number), p.receiptNumber, bold = true)
                    KeyValueRow(stringResource(R.string.customer), r.customerName)
                    TextButton(onClick = { onOpenContract(p.contractId) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text(stringResource(R.string.contract) + ": " + r.contractNumber) }
                    KeyValueRow(stringResource(R.string.amount_received), fmt.money(p.amountMinor, p.currency), Brand.Green, true)
                    KeyValueRow(stringResource(R.string.payment_date), fmt.dateTime(p.paidAt))
                    KeyValueRow(stringResource(R.string.payment_method), "").let { }
                    Text(methodLabel(p.method) + if (p.kind == PaymentKind.DOWN_PAYMENT) " · " + stringResource(R.string.down_payment) else if (p.kind == PaymentKind.ADJUSTMENT) " · " + stringResource(R.string.settlement_discount) else "")
                    p.reference?.let { KeyValueRow(stringResource(R.string.reference_number), it) }
                    if (p.notes.isNotBlank()) KeyValueRow(stringResource(R.string.notes), p.notes)
                }
            }
            if (allocs.isNotEmpty()) {
                SectionTitle(stringResource(R.string.installment_allocation))
                SMCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        allocs.forEach { (a, b) -> KeyValueRow(b?.let { "#${it.sequence} · " + fmt.date(it.dueDate) } ?: "", fmt.money(a.amountMinor, p.currency)) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { runCatching { c.pdf.receipt(id) }.onSuccess { c.pdf.share(ctx, it) }.onFailure { snack.showSnackbar(pdfFailed) } } }, Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Text(" " + stringResource(R.string.share_pdf)) }
                OutlinedButton(onClick = { scope.launch { runCatching { c.pdf.receipt(id) }.onSuccess { c.pdf.print(ctx, it, p.receiptNumber) }.onFailure { snack.showSnackbar(pdfFailed) } } }, Modifier.weight(1f)) { Icon(Icons.Filled.Print, null); Text(" " + stringResource(R.string.print)) }
            }
            if (!readOnly && p.status == Status.POSTED) SecondaryButton(stringResource(R.string.reverse_payment), { reverseDialog = true })
        }
        if (reverseDialog) androidx.compose.material3.AlertDialog(
            onDismissRequest = { reverseDialog = false }, title = { Text(stringResource(R.string.reverse_payment)) },
            text = { Column { Text(stringResource(R.string.reverse_payment_msg)); Field(reason, { reason = it }, stringResource(R.string.reason)) } },
            confirmButton = {
                TextButton(enabled = reason.isNotBlank(), onClick = {
                    reverseDialog = false
                    scope.launch { runCatching { c.ledger.reversePayment(id, reason) }.onSuccess { refresh++ }.onFailure { error = it } }
                }) { Text(stringResource(R.string.reverse_payment), color = Brand.Red) }
            },
            dismissButton = { TextButton(onClick = { reverseDialog = false }) { Text(stringResource(R.string.back)) } },
        )
    }
}
