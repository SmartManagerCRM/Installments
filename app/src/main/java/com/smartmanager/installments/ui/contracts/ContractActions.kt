package com.smartmanager.installments.ui.contracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.R
import com.smartmanager.installments.core.money.Currencies
import com.smartmanager.installments.core.money.MoneyMath
import com.smartmanager.installments.core.schedule.Frequency
import com.smartmanager.installments.core.schedule.ScheduleGenerator
import com.smartmanager.installments.data.db.ContractEntity
import com.smartmanager.installments.data.db.InstallmentBalanceView
import com.smartmanager.installments.ui.components.ConfirmDialog
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.DateField
import com.smartmanager.installments.ui.components.DropdownField
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.KeyValueRow
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.components.errorText
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

@Composable
fun RescheduleScreen(contractId: String, onBack: () -> Unit, onDone: () -> Unit) {
    val c = LocalContainer.current
    val fmt = LocalFmt.current
    val scope = rememberCoroutineScope()
    var contract by remember { mutableStateOf<ContractEntity?>(null) }
    var open by remember { mutableStateOf<List<InstallmentBalanceView>>(emptyList()) }
    var freq by remember { mutableStateOf(Frequency.MONTHLY) }
    var count by remember { mutableStateOf("6") }
    var first by remember { mutableStateOf(LocalDate.now().plusMonths(1)) }
    var rows by remember { mutableStateOf<List<SchedRow>>(emptyList()) }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(contractId) {
        contract = c.db.contracts().get(contractId)
        open = c.db.contracts().balances(contractId).filter { it.outstandingMinor > 0 }
    }
    val cur = contract?.currency ?: fmt.defaultCurrency
    val outstanding = open.sumOf { it.outstandingMinor }

    fun generate() {
        val n = count.toIntOrNull() ?: return
        if (n !in 1..ScheduleGenerator.MAX_INSTALLMENTS || outstanding <= 0) return
        val f = if (freq == Frequency.CUSTOM) Frequency.MONTHLY else freq
        rows = ScheduleGenerator.generate(outstanding, n, f, first, Currencies.of(cur).unit).map { SchedRow(System.nanoTime() + it.sequence, it.dueDate, MoneyMath.format(it.amountMinor, Currencies.of(cur).decimals, null, '.')) }
    }

    Column {
        SMTopBar(stringResource(R.string.reschedule), onBack = onBack)
        ContentColumn {
            ErrorBanner(errorText(error))
            InfoBanner(stringResource(R.string.reschedule_info))
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    KeyValueRow(stringResource(R.string.outstanding_balance), fmt.money(outstanding, cur), Brand.Red, bold = true)
                    open.forEach { KeyValueRow("#${it.sequence} · " + fmt.date(it.dueDate), fmt.money(it.outstandingMinor, cur)) }
                }
            }
            SectionTitle(stringResource(R.string.new_schedule))
            DropdownField(stringResource(R.string.frequency), Frequency.entries.toList(), freq, { frequencyLabel(it) }, { freq = it })
            Field(count, { count = it.filter { ch -> ch.isDigit() } }, stringResource(R.string.number_of_installments), keyboard = KeyboardType.Number)
            DateField(stringResource(R.string.first_due_date), first, { first = it })
            PrimaryButton(stringResource(R.string.generate_schedule), { generate() })
            if (rows.isNotEmpty()) ScheduleEditor(rows, { rows = it }, cur, outstanding)
            Field(reason, { reason = it }, stringResource(R.string.reason) + " *")
            val parsed = rows.toScheduled(cur)
            val ok = parsed != null && parsed.isNotEmpty() && parsed.sumOf { it.amountMinor } == outstanding && reason.isNotBlank()
            PrimaryButton(stringResource(R.string.confirm_reschedule), { confirm = true }, enabled = ok, orange = true)
        }
        if (confirm) ConfirmDialog(
            stringResource(R.string.reschedule), stringResource(R.string.reschedule_confirm_msg), stringResource(R.string.confirm), onConfirm = {
                confirm = false
                scope.launch { runCatching { c.ledger.reschedule(contractId, rows.toScheduled(cur)!!, reason) }.onSuccess { onDone() }.onFailure { error = it } }
            }, onDismiss = { confirm = false },
        )
    }
}

@Composable
fun SettleScreen(contractId: String, onBack: () -> Unit, onDone: (String) -> Unit) {
    val c = LocalContainer.current
    val fmt = LocalFmt.current
    val scope = rememberCoroutineScope()
    val requestKey = rememberSaveable { UUID.randomUUID().toString() }
    var contract by remember { mutableStateOf<ContractEntity?>(null) }
    var outstanding by remember { mutableStateOf(0L) }
    var discount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("CASH") }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(contractId) {
        contract = c.db.contracts().get(contractId)
        outstanding = c.ledger.settlementQuote(contractId).outstandingMinor
    }
    val cur = contract?.currency ?: fmt.defaultCurrency
    val dec = Currencies.of(cur).decimals
    val disc = if (discount.isBlank()) 0L else MoneyMath.parse(discount, dec)
    val valid = disc != null && disc >= 0 && disc < outstanding
    val cash = if (valid) outstanding - disc!! else 0L

    Column {
        SMTopBar(stringResource(R.string.early_settlement), onBack = onBack)
        ContentColumn {
            ErrorBanner(errorText(error))
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    KeyValueRow(stringResource(R.string.contract), contract?.number ?: "")
                    KeyValueRow(stringResource(R.string.outstanding_balance), fmt.money(outstanding, cur), bold = true)
                }
            }
            Field(discount, { discount = it }, stringResource(R.string.settlement_discount), keyboard = KeyboardType.Decimal, suffix = cur,
                error = if (discount.isNotBlank() && !valid) stringResource(R.string.err_discount_invalid) else null)
            InfoBanner(stringResource(R.string.settlement_discount_hint))
            DropdownField(stringResource(R.string.payment_method), listOf("CASH", "BANK_TRANSFER", "CARD", "OTHER"), method, { methodLabel(it) }, { method = it })
            Field(reference, { reference = it }, stringResource(R.string.reference_number))
            Field(notes, { notes = it }, stringResource(R.string.notes), singleLine = false, minLines = 2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.amount_to_collect), fontWeight = FontWeight.Bold)
                Text(fmt.money(cash, cur), fontWeight = FontWeight.Bold, color = Brand.Green)
            }
            PrimaryButton(stringResource(R.string.confirm_settlement), { confirm = true }, enabled = valid && outstanding > 0 && !busy, orange = true)
        }
        if (confirm) ConfirmDialog(
            stringResource(R.string.early_settlement), stringResource(R.string.settle_confirm_msg, fmt.money(cash, cur)), stringResource(R.string.confirm),
            onConfirm = {
                confirm = false; busy = true
                scope.launch {
                    runCatching { c.ledger.settleContract(requestKey, contractId, disc ?: 0, method, reference, notes) }
                        .onSuccess { busy = false; onDone(it.payment.id) }.onFailure { busy = false; error = it }
                }
            }, onDismiss = { confirm = false },
        )
    }
}
