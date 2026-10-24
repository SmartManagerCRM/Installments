package com.smartmanager.installments.ui.customers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.smartmanager.installments.data.db.CustomerRow
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class CustomersVm(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    /** 0 all, 1 with balance, 2 overdue, 3 no balance, 4 archived */
    val filter = MutableStateFlow(0)

    val paged: Flow<PagingData<CustomerRow>> = combine(query, filter, c.ticker.today, c.settings.settings.map { it.currency }.distinctUntilChanged()) { q, f, t, cur ->
        arrayOf<Any>(q.trim(), f, t.toEpochDay(), cur)
    }.flatMapLatest { a ->
        val f = a[1] as Int
        Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) {
            c.db.customers().paged(a[0] as String, if (f == 4) Status.ARCHIVED else Status.ACTIVE, if (f == 4) 0 else f, a[2] as Long, a[3] as String)
        }.flow
    }.cachedIn(viewModelScope)
}

@Composable
fun CustomersScreen(wide: Boolean, onOpenCustomer: (String) -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit, onOpenContract: (String) -> Unit, onNewContract: (String) -> Unit, onPay: (String) -> Unit) {
    val vm = appVm { CustomersVm(it) }
    val q by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val items = vm.paged.collectAsLazyPagingItems()
    val fmt = LocalFmt.current
    val readOnly = LocalReadOnly.current
    var selected by rememberSaveable { mutableStateOf<String?>(null) }

    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(if (wide) 0.42f else 1f).fillMaxHeight()) {
            Column(Modifier.fillMaxSize()) {
                SMTopBar(stringResource(R.string.nav_customers))
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Field(q, { vm.query.value = it }, stringResource(R.string.search_customers), trailing = { Icon(Icons.Filled.Search, null) })
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            R.string.filter_all, R.string.filter_with_balance, R.string.filter_overdue, R.string.filter_no_balance, R.string.filter_archived,
                        ).forEachIndexed { i, r ->
                            FilterChip(selected = filter == i, onClick = { vm.filter.value = i }, label = { Text(stringResource(r)) })
                        }
                    }
                }
                if (items.itemCount == 0) EmptyState(stringResource(R.string.no_customers))
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                        items[i]?.let { r ->
                            SMCard(Modifier.fillMaxWidth(), onClick = { if (wide) selected = r.id else onOpenCustomer(r.id) }) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.fullName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                        Text(r.phone, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        if (r.outstandingMinor > 0) Text(fmt.money(r.outstandingMinor), fontWeight = FontWeight.Bold, color = Brand.Navy)
                                        if (r.overdueMinor > 0) Text(stringResource(R.string.overdue) + " " + fmt.money(r.overdueMinor), color = Brand.Red, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    item { Box(Modifier.padding(bottom = 88.dp)) }
                }
            }
            if (!readOnly) FloatingActionButton(
                onClick = onAdd, containerColor = Brand.Orange, contentColor = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) { Icon(Icons.Filled.Add, stringResource(R.string.add_customer)) }
        }
        if (wide) {
            Box(Modifier.weight(0.58f).fillMaxHeight()) {
                val id = selected
                if (id == null) EmptyState(stringResource(R.string.select_customer_hint), Modifier.align(Alignment.Center))
                else CustomerDetailScreen(id, embedded = true, onBack = { selected = null }, onEdit = onEdit, onOpenContract = onOpenContract, onNewContract = onNewContract, onPay = onPay)
            }
        }
    }
}
