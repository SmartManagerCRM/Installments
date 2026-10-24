package com.smartmanager.installments.ui.payments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartmanager.installments.AppContainer
import com.smartmanager.installments.R
import com.smartmanager.installments.core.money.Currencies
import com.smartmanager.installments.core.money.MoneyMath
import com.smartmanager.installments.core.payments.AllocationLine
import com.smartmanager.installments.core.payments.AllocationResult
import com.smartmanager.installments.core.payments.InstallmentBalance
import com.smartmanager.installments.core.payments.InstallmentStatusCalculator
import com.smartmanager.installments.core.payments.PaymentAllocator
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.data.db.ContractRow
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.db.InstallmentBalanceView
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.data.repo.LedgerError
import com.smartmanager.installments.data.repo.LedgerException
import com.smartmanager.installments.data.repo.PaymentOutcome
import com.smartmanager.installments.data.repo.PaymentRequest
import com.smartmanager.installments.ui.components.ConfirmDialog
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.DropdownField
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.KeyValueRow
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalSettings
import com.smartmanager.installments.ui.components.LocalSnack
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.ReminderRequest
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.components.errorText
import com.smartmanager.installments.ui.components.rememberReminderLauncher
import com.smartmanager.installments.ui.contracts.methodLabel
import com.smartmanager.installments.ui.dashboard.StatusBadge
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

class PayVm(val c: AppContainer, val preContractId: String?, val preCustomerId: String?) : ViewModel() {
    /** Generated once per screen: re-submitting the same form can never create a second payment. */
    val requestKey: String = UUID.randomUUID().toString()
    var step by mutableStateOf(0)
    var customer by mutableStateOf<CustomerEntity?>(null)
    var query by mutableStateOf("")
    var customers by mutableStateOf<List<CustomerEntity>>(emptyList())
    var contracts by mutableStateOf<List<ContractRow>>(emptyList())
    var contract by mutableStateOf<ContractRow?>(null)
    var balances by mutableStateOf<List<InstallmentBalanceView>>(emptyList())
    var amount by mutableStateOf("")
    var method by mutableStateOf("CASH")
    var reference by mutableStateOf("")
    var notes by mutableStateOf("")
    var manual by mutableStateOf(false)
    var manualAmounts by mutableStateOf<Map<String, String>>(emptyMap())
    var error by mutableStateOf<Throwable?>(null)
    var busy by mutableStateOf(false)
    var confirmDuplicate by mutableStateOf(false)
    var outcome by mutableStateOf<PaymentOutcome?>(null)
    private var started = false

    val currency get() = contract?.currency ?: c.run { "TND" }
    private val dec get() = Currencies.of(currency).decimals
    val amountMinor: Long? get() = MoneyMath.parse(amount, dec)
    val core get() = balances.map { InstallmentBalance(it.installmentId, it.sequence, LocalDate.ofEpochDay(it.dueDate), it.amountMinor, it.paidMinor) }
    val totalOutstanding get() = balances.sumOf { it.outstandingMinor }

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            if (preContractId != null) {
                val row = c.db.contracts().observeRow(preContractId).first()
                customer = row?.let { c.db.customers().get(it.customerId) }
                if (row != null) selectContract(row)
            } else if (preCustomerId != null) {
                customer = c.db.customers().get(preCustomerId)
                loadContracts()
            } else search("")
        }
    }

    fun search(q: String) { query = q; viewModelScope.launch { customers = c.db.customers().search(q.trim()) } }

    fun loadContracts() {
        val cu = customer ?: return
        viewModelScope.launch { contracts = c.db.contracts().observeForCustomer(cu.id).first().filter { it.status == Status.ACTIVE && it.outstandingMinor > 0 } }
    }

    fun selectContract(row: ContractRow) {
        contract = row
        viewModelScope.launch { balances = c.db.contracts().balances(row.id); step = 1 }
    }

    fun autoLines(rule: com.smartmanager.installments.core.payments.AllocationRule): List<AllocationLine>? {
        val a = amountMinor ?: return null
        return (PaymentAllocator.auto(a, core, rule) as? AllocationResult.Success)?.lines
    }

    fun manualLines(): List<AllocationLine>? {
        val out = mutableListOf<AllocationLine>()
        for ((id, t) in manualAmounts) {
            if (t.isBlank()) continue
            val m = MoneyMath.parse(t, dec) ?: return null
            if (m > 0) out += AllocationLine(id, m)
        }
        return out
    }

    fun submit(rule: com.smartmanager.installments.core.payments.AllocationRule) {
        val co = contract ?: return
        val a = amountMinor ?: return
        busy = true; error = null
        viewModelScope.launch {
            runCatching {
                c.ledger.recordPayment(
                    PaymentRequest(requestKey, co.id, a, method, reference, notes, null, if (manual) manualLines() else null, rule, confirmDuplicate),
                )
            }.onSuccess { busy = false; outcome = it }
                .onFailure { busy = false; if ((it as? LedgerException)?.error == LedgerError.POSSIBLE_DUPLICATE) confirmDuplicateDialog = true else error = it }
        }
    }

    var confirmDuplicateDialog by mutableStateOf(false)
}

@Composable
fun RecordPaymentScreen(contractId: String?, customerId: String?, onBack: () -> Unit, onDone: (String) -> Unit) {
    val vm = appVm(key = "pay-$contractId-$customerId") { PayVm(it, contractId, customerId) }
    val fmt = LocalFmt.current
    val settings = LocalSettings.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val remind = rememberReminderLauncher()
    LaunchedEffect(Unit) { vm.start() }
    val steps = listOf(R.string.pstep_contract, R.string.pstep_unpaid, R.string.pstep_amount, R.string.pstep_allocation, R.string.pstep_review, R.string.pstep_confirm)
    val pdfFailed = stringResource(R.string.pdf_failed)
    val cur = vm.currency

    Column {
        SMTopBar(stringResource(R.string.record_payment), onBack = { if (vm.outcome != null) onBack() else if (vm.step > 0 && !(vm.step == 1 && vm.preContractId != null)) vm.step-- else onBack() })
        val out = vm.outcome
        if (out == null) LinearProgressIndicator(progress = { (vm.step + 1) / 6f }, modifier = Modifier.fillMaxWidth(), color = Brand.Orange)
        ContentColumn {
            if (out != null) {
                // ---- Success + receipt
                InfoBanner("✓ " + stringResource(R.string.payment_recorded), Brand.Green, Brand.GreenSoft)
                SMCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        KeyValueRow(stringResource(R.string.receipt_number), out.payment.receiptNumber, bold = true)
                        KeyValueRow(stringResource(R.string.amount_received), fmt.money(out.payment.amountMinor, cur), Brand.Green, bold = true)
                        KeyValueRow(stringResource(R.string.remaining_balance), fmt.money(out.remainingOutstandingMinor, cur), if (out.remainingOutstandingMinor > 0) Brand.Red else Brand.Green, bold = true)
                        if (out.contractCompleted) Text("🎉 " + stringResource(R.string.contract_completed_msg), color = Brand.Green)
                    }
                }
                PrimaryButton(stringResource(R.string.share_receipt), {
                    scope.launch { runCatching { vm.c.pdf.receipt(out.payment.id) }.onSuccess { vm.c.pdf.share(ctx, it) }.onFailure { snack.showSnackbar(pdfFailed) } }
                }, icon = Icons.Filled.Share, orange = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch { runCatching { vm.c.pdf.receipt(out.payment.id) }.onSuccess { vm.c.pdf.print(ctx, it, out.payment.receiptNumber) }.onFailure { snack.showSnackbar(pdfFailed) } }
                    }, Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Print, null); Text(" " + stringResource(R.string.print)) }
                    OutlinedButton(onClick = {
                        val cu = vm.customer ?: return@OutlinedButton
                        remind(ReminderRequest(cu.id, cu.fullName, cu.phone, out.payment.contractId, ReminderType.PAYMENT_CONFIRMATION, out.payment.amountMinor, cur, out.payment.paidAt / 86_400_000L, vm.contract?.number ?: "", out.remainingOutstandingMinor, out.payment.receiptNumber))
                    }, Modifier.weight(1f)) { androidx.compose.material3.Icon(Icons.Filled.Chat, null, tint = Brand.Green); Text(" WhatsApp") }
                }
                SecondaryButton(stringResource(R.string.done), { onDone(out.payment.id) })
                return@ContentColumn
            }

            Text(stringResource(R.string.step_n_of, vm.step + 1, steps.size) + " · " + stringResource(steps[vm.step]), style = MaterialTheme.typography.titleMedium, color = Brand.Navy)
            ErrorBanner(errorText(vm.error))
            when (vm.step) {
                0 -> {
                    if (vm.customer == null) {
                        Field(vm.query, { vm.search(it) }, stringResource(R.string.search_customers))
                        vm.customers.forEach { cu ->
                            SMCard(Modifier.fillMaxWidth(), onClick = { vm.customer = cu; vm.loadContracts() }) {
                                Column(Modifier.padding(14.dp)) { Text(cu.fullName, style = MaterialTheme.typography.titleSmall); Text(cu.phone, style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
                            }
                        }
                    } else {
                        Text(vm.customer!!.fullName, style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { vm.customer = null; vm.contracts = emptyList(); vm.search("") }) { Text(stringResource(R.string.change_customer)) }
                        if (vm.contracts.isEmpty()) Text(stringResource(R.string.no_open_contracts), color = Brand.Muted)
                        vm.contracts.forEach { co ->
                            SMCard(Modifier.fillMaxWidth(), onClick = { vm.selectContract(co) }) {
                                Row(Modifier.padding(14.dp)) {
                                    Column(Modifier.weight(1f)) { Text(co.number, style = MaterialTheme.typography.titleSmall); Text(fmt.date(co.contractDate), style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
                                    Text(fmt.money(co.outstandingMinor, co.currency), fontWeight = FontWeight.Bold, color = Brand.Red)
                                }
                            }
                        }
                    }
                }
                1 -> {
                    Text(vm.customer?.fullName ?: "", style = MaterialTheme.typography.titleLarge)
                    Text(vm.contract?.number ?: "", color = Brand.Muted)
                    val today = LocalDate.now()
                    SMCard(Modifier.fillMaxWidth()) {
                        Column {
                            vm.balances.filter { it.outstandingMinor > 0 }.forEachIndexed { i, b ->
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) { Text("#${b.sequence} · " + fmt.date(b.dueDate), style = MaterialTheme.typography.titleSmall); if (b.paidMinor > 0) Text(stringResource(R.string.paid_of, fmt.amount(b.paidMinor, cur), fmt.amount(b.amountMinor, cur)), style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
                                    Column(horizontalAlignment = Alignment.End) { Text(fmt.money(b.outstandingMinor, cur), fontWeight = FontWeight.Bold); StatusBadge(InstallmentStatusCalculator.status(LocalDate.ofEpochDay(b.dueDate), b.amountMinor, b.paidMinor, today)) }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                            Row(Modifier.fillMaxWidth().padding(14.dp)) { Text(stringResource(R.string.outstanding_balance), Modifier.weight(1f), fontWeight = FontWeight.Bold); Text(fmt.money(vm.totalOutstanding, cur), fontWeight = FontWeight.Bold, color = Brand.Red) }
                        }
                    }
                }
                2 -> {
                    val first = vm.balances.filter { it.outstandingMinor > 0 }.minByOrNull { it.dueDate }
                    Field(vm.amount, { vm.amount = it }, stringResource(R.string.amount_received) + " *", keyboard = KeyboardType.Decimal, suffix = cur,
                        error = vm.amountMinor.let { a -> if (vm.amount.isNotBlank() && (a == null || a <= 0 || a > vm.totalOutstanding)) stringResource(if (a != null && a > vm.totalOutstanding) R.string.err_exceeds_outstanding else R.string.err_invalid_amount) else null })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        first?.let { OutlinedButton(onClick = { vm.amount = fmt.editable(it.outstandingMinor, cur) }, Modifier.weight(1f)) { Text(stringResource(R.string.next_installment), maxLines = 1) } }
                        OutlinedButton(onClick = { vm.amount = fmt.editable(vm.totalOutstanding, cur) }, Modifier.weight(1f)) { Text(stringResource(R.string.all_remaining), maxLines = 1) }
                    }
                    DropdownField(stringResource(R.string.payment_method), listOf("CASH", "BANK_TRANSFER", "CARD", "OTHER"), vm.method, { methodLabel(it) }, { vm.method = it })
                    Field(vm.reference, { vm.reference = it }, stringResource(R.string.reference_number))
                    Field(vm.notes, { vm.notes = it }, stringResource(R.string.notes), singleLine = false, minLines = 2)
                }
                3 -> {
                    val a = vm.amountMinor ?: 0
                    val auto = vm.autoLines(settings.allocationRule)
                    Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = !vm.manual, onClick = { vm.manual = false }); Text(stringResource(if (settings.allocationRule == com.smartmanager.installments.core.payments.AllocationRule.OLDEST_FIRST) R.string.alloc_auto_oldest else R.string.alloc_auto_latest)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = vm.manual, onClick = {
                            vm.manual = true
                            if (vm.manualAmounts.isEmpty() && auto != null) vm.manualAmounts = auto.associate { it.installmentId to fmt.editable(it.amountMinor, cur) }
                        }); Text(stringResource(R.string.alloc_manual))
                    }
                    if (vm.manual) {
                        vm.balances.filter { it.outstandingMinor > 0 }.forEach { b ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) { Text("#${b.sequence} · " + fmt.date(b.dueDate)); Text(stringResource(R.string.outstanding_balance) + ": " + fmt.money(b.outstandingMinor, cur), style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
                                Field(vm.manualAmounts[b.installmentId] ?: "", { t -> vm.manualAmounts = vm.manualAmounts + (b.installmentId to t) }, stringResource(R.string.amount), Modifier.weight(0.9f), keyboard = KeyboardType.Decimal)
                            }
                        }
                        val ml = vm.manualLines()
                        val sum = ml?.sumOf { it.amountMinor }
                        Text(stringResource(R.string.allocated_of, fmt.money(sum ?: 0, cur), fmt.money(a, cur)), color = if (sum == a) Brand.Green else Brand.Red, fontWeight = FontWeight.SemiBold)
                    } else if (auto != null) {
                        auto.forEach { l -> val b = vm.balances.first { it.installmentId == l.installmentId }; KeyValueRow("#${b.sequence} · " + fmt.date(b.dueDate), fmt.money(l.amountMinor, cur)) }
                    }
                }
                4 -> {
                    val a = vm.amountMinor ?: 0
                    val lines = if (vm.manual) vm.manualLines() else vm.autoLines(settings.allocationRule)
                    val preview = lines?.let { PaymentAllocator.preview(vm.core, it) }
                    SMCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            KeyValueRow(stringResource(R.string.customer), vm.customer?.fullName ?: "")
                            KeyValueRow(stringResource(R.string.contract), vm.contract?.number ?: "")
                            KeyValueRow(stringResource(R.string.amount_received), fmt.money(a, cur), Brand.Green, true)
                            KeyValueRow(stringResource(R.string.payment_method), "").let { }
                            Text(methodLabel(vm.method), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                        }
                    }
                    Text(stringResource(R.string.balances_after), style = MaterialTheme.typography.titleSmall)
                    preview?.filter { p -> lines.any { it.installmentId == p.id } || p.outstandingMinor > 0 }?.forEach { p ->
                        val before = vm.balances.first { it.installmentId == p.id }
                        KeyValueRow("#${p.sequence} · " + fmt.date(p.dueDate), fmt.money(before.outstandingMinor, cur) + "  →  " + fmt.money(p.outstandingMinor, cur), if (p.outstandingMinor == 0L) Brand.Green else Brand.Navy)
                    }
                    KeyValueRow(stringResource(R.string.remaining_balance), fmt.money(vm.totalOutstanding - a, cur), if (vm.totalOutstanding - a == 0L) Brand.Green else Brand.Red, true)
                }
                else -> {
                    InfoBanner(stringResource(R.string.confirm_payment_msg, fmt.money(vm.amountMinor ?: 0, cur)), Brand.Navy, Brand.BlueSoft)
                }
            }

            val a = vm.amountMinor
            val valid = when (vm.step) {
                0 -> false
                1 -> vm.totalOutstanding > 0
                2 -> a != null && a > 0 && a <= vm.totalOutstanding
                3 -> if (vm.manual) vm.manualLines()?.let { l -> l.isNotEmpty() && PaymentAllocator.validateManual(a ?: 0, l, vm.core) is AllocationResult.Success } == true
                else vm.autoLines(settings.allocationRule) != null
                else -> true
            }
            if (vm.step in 1..4) PrimaryButton(stringResource(R.string.next), { vm.step++ }, enabled = valid, orange = true)
            if (vm.step == 5) PrimaryButton(stringResource(R.string.confirm_payment), { vm.submit(settings.allocationRule) }, enabled = !vm.busy, orange = true)
            if (vm.step in 1..5 && !(vm.step == 1 && vm.preContractId != null)) SecondaryButton(stringResource(R.string.back), { vm.step-- })
        }
        if (vm.confirmDuplicateDialog) ConfirmDialog(
            stringResource(R.string.possible_duplicate_payment), stringResource(R.string.possible_duplicate_payment_msg), stringResource(R.string.record_anyway),
            onConfirm = { vm.confirmDuplicateDialog = false; vm.confirmDuplicate = true; vm.submit(settings.allocationRule) },
            onDismiss = { vm.confirmDuplicateDialog = false },
        )
    }
}
