package com.smartmanager.installments.ui.contracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartmanager.installments.AppContainer
import com.smartmanager.installments.R
import com.smartmanager.installments.core.contracts.ContractMath
import com.smartmanager.installments.core.contracts.LineItem
import com.smartmanager.installments.core.money.Currencies
import com.smartmanager.installments.core.money.MoneyMath
import com.smartmanager.installments.core.schedule.Frequency
import com.smartmanager.installments.core.schedule.ScheduleGenerator
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.db.ProductEntity
import com.smartmanager.installments.data.repo.NewContract
import com.smartmanager.installments.data.repo.NewItem
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.DateField
import com.smartmanager.installments.ui.components.DropdownField
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.KeyValueRow
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalSettings
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.components.errorText
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.launch
import java.time.LocalDate

class ItemDraft(val key: Long = System.nanoTime(), val productId: String? = null, description: String = "", qty: String = "1", price: String = "") {
    var description by mutableStateOf(description)
    var qty by mutableStateOf(qty)
    var price by mutableStateOf(price)
}

class WizardVm(val c: AppContainer, preCustomerId: String?, val draftId: String?) : ViewModel() {
    var step by mutableStateOf(0)
    var currency by mutableStateOf("TND")
    var customer by mutableStateOf<CustomerEntity?>(null)
    var customerQuery by mutableStateOf("")
    var customerResults by mutableStateOf<List<CustomerEntity>>(emptyList())
    val items = mutableStateListOf<ItemDraft>()
    var discount by mutableStateOf("")
    var down by mutableStateOf("")
    var downMethod by mutableStateOf("CASH")
    var frequency by mutableStateOf(Frequency.MONTHLY)
    var count by mutableStateOf("10")
    var firstDue by mutableStateOf(LocalDate.now().plusMonths(1))
    var contractDate by mutableStateOf(LocalDate.now())
    var roundWhole by mutableStateOf(true)
    var rows by mutableStateOf<List<SchedRow>>(emptyList())
    var terms by mutableStateOf("")
    var notes by mutableStateOf("")
    var error by mutableStateOf<Throwable?>(null)
    var saving by mutableStateOf(false)
    var catalogQuery by mutableStateOf("")
    var catalogResults by mutableStateOf<List<ProductEntity>>(emptyList())
    var showCatalog by mutableStateOf(false)
    private var started = false

    private val dec get() = Currencies.of(currency).decimals
    fun money(t: String): Long? = if (t.isBlank()) 0L else MoneyMath.parse(t, dec)

    fun start(defaultTerms: String) {
        if (started) return
        started = true
        viewModelScope.launch {
            val s = c.settings.current()
            currency = s.currency; roundWhole = s.roundToWholeUnit; frequency = s.defaultFrequency.takeIf { it != Frequency.CUSTOM } ?: Frequency.MONTHLY
            terms = s.contractTerms.ifBlank { defaultTerms }
            if (draftId != null) {
                c.ledger.loadDraft(draftId)?.let { d ->
                    customer = c.db.customers().get(d.customerId); currency = d.currency; contractDate = d.contractDate
                    items.clear(); d.items.forEach { items += ItemDraft(productId = it.productId, description = it.description, qty = it.quantity.toString(), price = MoneyMath.format(it.unitPriceMinor, Currencies.of(d.currency).decimals, null, '.')) }
                    discount = d.discountMinor.takeIf { it > 0 }?.let { MoneyMath.format(it, Currencies.of(d.currency).decimals, null, '.') } ?: ""
                    down = d.downPaymentMinor.takeIf { it > 0 }?.let { MoneyMath.format(it, Currencies.of(d.currency).decimals, null, '.') } ?: ""
                    frequency = d.frequency
                    rows = d.schedule.map { SchedRow(System.nanoTime() + it.sequence, it.dueDate, MoneyMath.format(it.amountMinor, Currencies.of(d.currency).decimals, null, '.')) }
                    count = d.schedule.size.toString(); terms = d.terms.ifBlank { terms }; notes = d.notes
                    step = 4
                }
            } else {
                if (preCustomerId != null) { customer = c.db.customers().get(preCustomerId); if (customer != null) step = 1 }
                searchCustomers("")
            }
            if (items.isEmpty()) items += ItemDraft()
        }
    }

    fun searchCustomers(q: String) {
        customerQuery = q
        viewModelScope.launch { customerResults = c.db.customers().search(q.trim()) }
    }

    fun searchCatalog(q: String) {
        catalogQuery = q
        viewModelScope.launch { catalogResults = c.db.products().search(q.trim()) }
    }

    fun addProduct(p: ProductEntity) {
        if (items.size == 1 && items[0].description.isBlank() && items[0].price.isBlank()) items.clear()
        items += ItemDraft(productId = p.id, description = p.name, qty = "1", price = MoneyMath.format(p.priceMinor, dec, null, '.'))
        showCatalog = false
    }

    val lineItems: List<LineItem>? get() {
        val out = items.map { i ->
            val q = i.qty.toIntOrNull() ?: return null
            val p = money(i.price) ?: return null
            LineItem(q, p)
        }
        return out
    }

    val totals get() = lineItems?.let { ContractMath.totals(it, money(discount) ?: -1, money(down) ?: -1) }

    fun generate() {
        val t = totals ?: return
        val n = count.toIntOrNull() ?: return
        if (!t.ok || n !in 1..ScheduleGenerator.MAX_INSTALLMENTS) return
        val sched = if (frequency == Frequency.CUSTOM) {
            // Custom: start from an even monthly split that the retailer then edits freely.
            ScheduleGenerator.generate(t.financedMinor, n, Frequency.MONTHLY, firstDue, if (roundWhole) Currencies.of(currency).unit else 1L)
        } else ScheduleGenerator.generate(t.financedMinor, n, frequency, firstDue, if (roundWhole) Currencies.of(currency).unit else 1L)
        rows = sched.map { SchedRow(System.nanoTime() + it.sequence, it.dueDate, MoneyMath.format(it.amountMinor, dec, null, '.')) }
    }

    fun save(activate: Boolean, onDone: (String) -> Unit) {
        val cu = customer ?: return
        val li = lineItems ?: return
        val sched = rows.toScheduled(currency) ?: return
        saving = true
        viewModelScope.launch {
            runCatching {
                c.ledger.createContract(
                    NewContract(
                        cu.id, contractDate, currency,
                        items.mapIndexed { i, it -> NewItem(it.productId, it.description, li[i].quantity, li[i].unitPriceMinor) },
                        money(discount) ?: 0, money(down) ?: 0, frequency, sched, terms, notes, activate, downMethod,
                    ),
                    existingDraftId = draftId,
                )
            }.onSuccess { saving = false; onDone(it) }.onFailure { saving = false; error = it }
        }
    }
}

@Composable
fun ContractWizardScreen(customerId: String?, draftId: String?, onBack: () -> Unit, onDone: (String) -> Unit) {
    val vm = appVm(key = "wizard-${customerId}-${draftId}") { WizardVm(it, customerId, draftId) }
    val fmt = LocalFmt.current
    val defaultTerms = stringResource(R.string.default_contract_terms)
    LaunchedEffect(Unit) { vm.start(defaultTerms) }
    val steps = listOf(R.string.step_customer, R.string.step_products, R.string.step_down_payment, R.string.step_frequency, R.string.step_schedule, R.string.step_confirm)
    val totals = vm.totals
    val paymentMethods = listOf("CASH", "BANK_TRANSFER", "CARD", "OTHER")

    Column {
        SMTopBar(stringResource(R.string.new_contract), onBack = { if (vm.step > 0 && draftId == null) vm.step-- else onBack() })
        LinearProgressIndicator(progress = { (vm.step + 1) / steps.size.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Brand.Orange)
        ContentColumn {
            Text(stringResource(R.string.step_n_of, vm.step + 1, steps.size) + " · " + stringResource(steps[vm.step]), style = MaterialTheme.typography.titleMedium, color = Brand.Navy)
            ErrorBanner(errorText(vm.error))
            when (vm.step) {
                0 -> { // Customer
                    Field(vm.customerQuery, { vm.searchCustomers(it) }, stringResource(R.string.search_customers))
                    vm.customer?.let { InfoBanner(stringResource(R.string.selected) + ": " + it.fullName + " (" + it.phone + ")", Brand.Navy, Brand.GreenSoft) }
                    vm.customerResults.forEach { cu ->
                        SMCard(Modifier.fillMaxWidth(), onClick = { vm.customer = cu }) {
                            Column(Modifier.padding(14.dp)) { Text(cu.fullName, style = MaterialTheme.typography.titleSmall); Text(cu.phone, style = MaterialTheme.typography.bodySmall, color = Brand.Muted) }
                        }
                    }
                    if (vm.customerResults.isEmpty()) Text(stringResource(R.string.no_customers), color = Brand.Muted)
                }
                1 -> { // Products and price
                    vm.items.forEachIndexed { idx, it0 ->
                        SMCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Field(it0.description, { v -> it0.description = v }, stringResource(R.string.product_description) + " *")
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Field(it0.qty, { v -> it0.qty = v.filter { ch -> ch.isDigit() } }, stringResource(R.string.quantity), Modifier.weight(0.5f), keyboard = KeyboardType.Number)
                                    Field(it0.price, { v -> it0.price = v }, stringResource(R.string.unit_price), Modifier.weight(1f), keyboard = KeyboardType.Decimal, suffix = vm.currency)
                                    if (vm.items.size > 1) IconButton(onClick = { vm.items.removeAt(idx) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = Brand.Red) }
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.items += ItemDraft() }) { Icon(Icons.Filled.Add, null); Text(" " + stringResource(R.string.add_item)) }
                        TextButton(onClick = { vm.searchCatalog(""); vm.showCatalog = true }) { Text(stringResource(R.string.from_catalog)) }
                    }
                    if (vm.showCatalog) SMCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Field(vm.catalogQuery, { vm.searchCatalog(it) }, stringResource(R.string.search_products))
                            vm.catalogResults.forEach { p ->
                                TextButton(onClick = { vm.addProduct(p) }, Modifier.fillMaxWidth()) {
                                    Text(p.name + " — " + fmt.money(p.priceMinor, vm.currency), Modifier.weight(1f))
                                }
                            }
                            if (vm.catalogResults.isEmpty()) Text(stringResource(R.string.no_products), color = Brand.Muted)
                        }
                    }
                    Field(vm.discount, { vm.discount = it }, stringResource(R.string.discount), keyboard = KeyboardType.Decimal, suffix = vm.currency)
                    totals?.let {
                        InfoBanner(stringResource(R.string.original_price) + ": " + fmt.money(it.originalPriceMinor, vm.currency) + "\n" + stringResource(R.string.net_price) + ": " + fmt.money(it.netPriceMinor, vm.currency))
                    }
                }
                2 -> { // Down payment
                    Field(vm.down, { vm.down = it }, stringResource(R.string.down_payment), keyboard = KeyboardType.Decimal, suffix = vm.currency)
                    DropdownField(stringResource(R.string.payment_method), paymentMethods, vm.downMethod, { methodLabel(it) }, { vm.downMethod = it })
                    totals?.let {
                        InfoBanner(stringResource(R.string.financed_amount) + ": " + fmt.money(it.financedMinor, vm.currency), Brand.Navy, Brand.BlueSoft)
                    }
                }
                3 -> { // Frequency
                    DropdownField(stringResource(R.string.frequency), Frequency.entries.toList(), vm.frequency, { frequencyLabel(it) }, { vm.frequency = it })
                    Field(vm.count, { vm.count = it.filter { ch -> ch.isDigit() } }, stringResource(R.string.number_of_installments), keyboard = KeyboardType.Number)
                    DateField(stringResource(R.string.first_due_date), vm.firstDue, { vm.firstDue = it })
                    DateField(stringResource(R.string.contract_date), vm.contractDate, { vm.contractDate = it })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = vm.roundWhole, onCheckedChange = { vm.roundWhole = it })
                        Text(stringResource(R.string.round_whole_units), modifier = Modifier.weight(1f))
                    }
                    if (vm.frequency == Frequency.CUSTOM) InfoBanner(stringResource(R.string.custom_schedule_hint))
                }
                4 -> { // Review/edit schedule
                    Text(stringResource(R.string.financed_amount) + ": " + fmt.money(totals?.financedMinor ?: 0, vm.currency), fontWeight = FontWeight.Bold)
                    ScheduleEditor(vm.rows, { vm.rows = it }, vm.currency, totals?.financedMinor ?: 0)
                }
                else -> { // Confirm
                    SMCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            KeyValueRow(stringResource(R.string.customer), vm.customer?.fullName ?: "")
                            vm.items.forEach { KeyValueRow("${it.qty} × ${it.description}", fmt.money((it.qty.toIntOrNull() ?: 0) * (vm.money(it.price) ?: 0), vm.currency)) }
                            HorizontalDivider(Modifier.padding(vertical = 6.dp))
                            totals?.let { t ->
                                KeyValueRow(stringResource(R.string.original_price), fmt.money(t.originalPriceMinor, vm.currency))
                                if (t.discountMinor > 0) KeyValueRow(stringResource(R.string.discount), "- " + fmt.money(t.discountMinor, vm.currency))
                                KeyValueRow(stringResource(R.string.down_payment), fmt.money(t.downPaymentMinor, vm.currency))
                                KeyValueRow(stringResource(R.string.financed_amount), fmt.money(t.financedMinor, vm.currency), bold = true)
                            }
                            KeyValueRow(stringResource(R.string.number_of_installments), vm.rows.size.toString())
                        }
                    }
                    vm.rows.toScheduled(vm.currency)?.forEachIndexed { i, s ->
                        Row(Modifier.fillMaxWidth()) { Text("#${i + 1}  " + fmt.date(s.dueDate), Modifier.weight(1f)); Text(fmt.money(s.amountMinor, vm.currency), fontWeight = FontWeight.SemiBold) }
                    }
                    SectionTitle(stringResource(R.string.contract_terms))
                    InfoBanner(stringResource(R.string.terms_legal_notice), Brand.Orange, Brand.OrangeSoft)
                    Field(vm.terms, { vm.terms = it }, stringResource(R.string.contract_terms), singleLine = false, minLines = 5)
                    Field(vm.notes, { vm.notes = it }, stringResource(R.string.notes), singleLine = false, minLines = 2)
                }
            }

            // Navigation
            val valid = when (vm.step) {
                0 -> vm.customer != null
                1 -> totals?.ok == true && vm.items.all { it.description.isNotBlank() }
                2 -> totals?.ok == true
                3 -> vm.count.toIntOrNull()?.let { it in 1..ScheduleGenerator.MAX_INSTALLMENTS } == true
                4, 5 -> totals?.let { t -> vm.rows.toScheduled(vm.currency)?.let { ScheduleGenerator.validate(it, t.financedMinor).ok } } == true
                else -> false
            }
            Spacer(Modifier.height(4.dp))
            if (vm.step < 5) {
                PrimaryButton(stringResource(R.string.next), {
                    if (vm.step == 3) vm.generate()
                    vm.step++
                }, enabled = valid, orange = true)
            } else {
                PrimaryButton(stringResource(R.string.confirm_and_save), { vm.save(true, onDone) }, enabled = valid && !vm.saving, orange = true)
                SecondaryButton(stringResource(R.string.save_as_draft), { vm.save(false, onDone) }, enabled = valid && !vm.saving)
            }
            if (vm.step > 0 && !(vm.step == 4 && draftId != null)) SecondaryButton(stringResource(R.string.back), { vm.step-- })
        }
    }
}

@Composable
fun methodLabel(m: String): String = stringResource(
    when (m) { "CASH" -> R.string.method_cash; "BANK_TRANSFER" -> R.string.method_bank; "CARD" -> R.string.method_card; "ADJUSTMENT" -> R.string.method_adjustment; else -> R.string.method_other },
)

@Composable
fun frequencyLabel(f: Frequency): String = stringResource(
    when (f) {
        Frequency.WEEKLY -> R.string.freq_weekly; Frequency.BIWEEKLY -> R.string.freq_biweekly; Frequency.MONTHLY -> R.string.freq_monthly
        Frequency.BIMONTHLY -> R.string.freq_bimonthly; Frequency.QUARTERLY -> R.string.freq_quarterly; Frequency.CUSTOM -> R.string.freq_custom
    },
)
