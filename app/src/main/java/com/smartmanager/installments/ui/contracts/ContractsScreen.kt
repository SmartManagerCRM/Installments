package com.smartmanager.installments.ui.contracts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.widthIn
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
import com.smartmanager.installments.data.db.ContractRow
import com.smartmanager.installments.data.db.Status
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.StatusChip
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

@Composable
fun ContractStatusChip(status: String) {
    val (res, fg, bg) = when (status) {
        Status.ACTIVE -> Triple(R.string.contract_active, Brand.Blue, Brand.BlueSoft)
        Status.COMPLETED -> Triple(R.string.contract_completed, Brand.Green, Brand.GreenSoft)
        Status.CANCELLED -> Triple(R.string.contract_cancelled, Brand.Red, Brand.RedSoft)
        else -> Triple(R.string.contract_draft, Brand.Orange, Brand.OrangeSoft)
    }
    StatusChip(stringResource(res), fg, bg)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContractsVm(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val status = MutableStateFlow("ALL")
    val paged: Flow<PagingData<ContractRow>> = combine(query, status) { q, s -> q.trim() to s }.flatMapLatest { (q, s) ->
        Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) { c.db.contracts().paged(s, "", q) }.flow
    }.cachedIn(viewModelScope)
}

@Composable
fun ContractsScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val vm = appVm { ContractsVm(it) }
    val q by vm.query.collectAsStateWithLifecycle()
    val st by vm.status.collectAsStateWithLifecycle()
    val items = vm.paged.collectAsLazyPagingItems()
    val fmt = LocalFmt.current
    val readOnly = LocalReadOnly.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SMTopBar(stringResource(R.string.nav_contracts))
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                    Field(q, { vm.query.value = it }, stringResource(R.string.search_contracts), trailing = { Icon(Icons.Filled.Search, null) })
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("ALL" to R.string.filter_all, Status.ACTIVE to R.string.contract_active, Status.COMPLETED to R.string.contract_completed,
                            Status.DRAFT to R.string.contract_draft, Status.CANCELLED to R.string.contract_cancelled).forEach { (k, r) ->
                            FilterChip(selected = st == k, onClick = { vm.status.value = k }, label = { Text(stringResource(r)) })
                        }
                    }
                    if (items.itemCount == 0) EmptyState(stringResource(R.string.no_contracts))
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                            items[i]?.let { r ->
                                SMCard(Modifier.fillMaxWidth(), onClick = { onOpen(r.id) }) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(r.number, style = MaterialTheme.typography.titleSmall)
                                            Text(r.customerName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                            Text(fmt.date(r.contractDate) + (r.nextDueDate?.let { " · " + stringResource(R.string.next_due) + " " + fmt.date(it) } ?: ""), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(fmt.money(r.outstandingMinor, r.currency), fontWeight = FontWeight.Bold, color = Brand.Navy)
                                            ContractStatusChip(r.status)
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
        if (!readOnly) FloatingActionButton(onClick = onNew, containerColor = Brand.Orange, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Filled.Add, stringResource(R.string.new_contract))
        }
    }
}
