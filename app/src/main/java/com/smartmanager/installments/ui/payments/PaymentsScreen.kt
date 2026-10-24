package com.smartmanager.installments.ui.payments

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.smartmanager.installments.AppContainer
import com.smartmanager.installments.R
import com.smartmanager.installments.core.payments.InstallmentStatusCalculator
import com.smartmanager.installments.data.db.InstallmentRow
import com.smartmanager.installments.data.db.PaymentRow
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.ui.components.DropdownField
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.contracts.methodLabel
import com.smartmanager.installments.ui.dashboard.StatusBadge
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Filter ids -> (query mode, from, to) */
enum class InstFilter(val label: Int) {
    OPEN(R.string.filter_unpaid), TODAY(R.string.filter_due_today), WEEK(R.string.filter_this_week), MONTH(R.string.filter_this_month),
    OVERDUE(R.string.filter_overdue), PARTIAL(R.string.filter_partial), PAID(R.string.filter_paid),
}

enum class InstSort(val label: Int) { DUE_DATE(R.string.sort_due_date), AMOUNT(R.string.sort_amount), CUSTOMER(R.string.sort_customer), OVERDUE_DURATION(R.string.sort_overdue_duration) }

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentsVm(c: AppContainer) : ViewModel() {
    val filter = MutableStateFlow(InstFilter.OPEN)
    val sort = MutableStateFlow(InstSort.DUE_DATE)
    val query = MutableStateFlow("")
    val historyQuery = MutableStateFlow("")

    val installments: Flow<PagingData<InstallmentRow>> = combine(filter, sort, query, c.ticker.today, c.settings.settings.map { it.currency }.distinctUntilChanged()) { f, s, q, today, cur ->
        arrayOf<Any>(f, s, q.trim(), today, cur)
    }.flatMapLatest { a ->
        val f = a[0] as InstFilter; val s = a[1] as InstSort; val today = a[3] as LocalDate
        val (mode, from, to) = when (f) {
            InstFilter.OPEN -> Triple(0, 0L, 0L)
            InstFilter.TODAY -> Triple(1, today.toEpochDay(), today.toEpochDay())
            InstFilter.WEEK -> Triple(1, today.toEpochDay(), today.plusDays(6).toEpochDay())
            InstFilter.MONTH -> Triple(1, today.toEpochDay(), today.with(TemporalAdjusters.lastDayOfMonth()).toEpochDay())
            InstFilter.OVERDUE -> Triple(2, 0L, 0L)
            InstFilter.PARTIAL -> Triple(3, 0L, 0L)
            InstFilter.PAID -> Triple(4, 0L, 0L)
        }
        val sortCode = when (s) { InstSort.DUE_DATE, InstSort.OVERDUE_DURATION -> 0; InstSort.AMOUNT -> 1; InstSort.CUSTOMER -> 2 }
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
            c.db.installments().paged(mode, from, to, today.toEpochDay(), a[2] as String, sortCode, a[4] as String)
        }.flow
    }.cachedIn(viewModelScope)

    val history: Flow<PagingData<PaymentRow>> = historyQuery.flatMapLatest { q ->
        Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) { c.db.payments().paged(q.trim(), "", "") }.flow
    }.cachedIn(viewModelScope)
}

@Composable
fun PaymentsScreen(onRecordPayment: (String?) -> Unit, onOpenPayment: (String) -> Unit, onCollection: () -> Unit, onOpenContract: (String) -> Unit, initialFilter: String? = null) {
    val vm = appVm { PaymentsVm(it) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val readOnly = LocalReadOnly.current
    val fmt = LocalFmt.current
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val hq by vm.historyQuery.collectAsStateWithLifecycle()
    val inst = vm.installments.collectAsLazyPagingItems()
    val hist = vm.history.collectAsLazyPagingItems()
    val today = remember0()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SMTopBar(stringResource(R.string.nav_payments)) {
                IconButton(onClick = onCollection) { Icon(Icons.Filled.Notifications, stringResource(R.string.collection_center), tint = Brand.Navy) }
            }
            TabRow(selectedTabIndex = tab, containerColor = Brand.Background) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.installments_tab)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.history_tab)) })
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                    if (tab == 0) {
                        Field(q, { vm.query.value = it }, stringResource(R.string.search_installments), trailing = { Icon(Icons.Filled.Search, null) })
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            InstFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(stringResource(f.label)) }) }
                        }
                        DropdownField(stringResource(R.string.sort_by), InstSort.entries.toList(), sort, { stringResource(it.label) }, { vm.sort.value = it })
                        if (inst.itemCount == 0) EmptyState(stringResource(R.string.no_installments))
                        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(count = inst.itemCount, key = inst.itemKey { it.b.installmentId }) { i ->
                                inst[i]?.let { r ->
                                    val st = InstallmentStatusCalculator.status(LocalDate.ofEpochDay(r.b.dueDate), r.b.amountMinor, r.b.paidMinor, today)
                                    val days = InstallmentStatusCalculator.daysOverdue(LocalDate.ofEpochDay(r.b.dueDate), r.b.outstandingMinor, today)
                                    SMCard(Modifier.fillMaxWidth(), onClick = { if (r.b.outstandingMinor > 0 && !readOnly) onRecordPayment(r.b.contractId) else onOpenContract(r.b.contractId) }) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(r.customerName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                                Text("${r.b.contractNumber} · #${r.b.sequence} · ${fmt.date(r.b.dueDate)}", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                                                if (days > 0) Text(stringResource(R.string.days_overdue, days.toInt()), style = MaterialTheme.typography.bodySmall, color = Brand.Red)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(fmt.money(if (filter == InstFilter.PAID) r.b.amountMinor else r.b.outstandingMinor, r.b.currency), fontWeight = FontWeight.Bold)
                                                StatusBadge(st)
                                            }
                                        }
                                    }
                                }
                            }
                            item { Box(Modifier.padding(bottom = 88.dp)) }
                        }
                    } else {
                        Field(hq, { vm.historyQuery.value = it }, stringResource(R.string.search_payments), trailing = { Icon(Icons.Filled.Search, null) })
                        if (hist.itemCount == 0) EmptyState(stringResource(R.string.no_payments))
                        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(count = hist.itemCount, key = hist.itemKey { it.p.id }) { i ->
                                hist[i]?.let { r ->
                                    SMCard(Modifier.fillMaxWidth(), onClick = { onOpenPayment(r.p.id) }) {
                                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(r.customerName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                                Text("${r.p.receiptNumber} · ${r.contractNumber}", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                                                Text(fmt.dateTime(r.p.paidAt) + " · " + methodLabel(r.p.method), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(fmt.money(r.p.amountMinor, r.p.currency), fontWeight = FontWeight.Bold, color = if (r.p.status == Status.REVERSED) Brand.Muted else Brand.Green)
                                                if (r.p.status == Status.REVERSED) Text(stringResource(R.string.reversed), color = Brand.Red, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                            item { Box(Modifier.padding(bottom = 88.dp)) }
                        }
                    }
                }
            }
        }
        if (!readOnly) FloatingActionButton(onClick = { onRecordPayment(null) }, containerColor = Brand.Orange, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.record_payment))
        }
    }
}

@Composable
private fun remember0(): LocalDate {
    val c = com.smartmanager.installments.ui.components.LocalContainer.current
    val t by c.ticker.today.collectAsStateWithLifecycle()
    return t
}
