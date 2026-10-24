package com.smartmanager.installments.ui.products

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.smartmanager.installments.core.money.Currencies
import com.smartmanager.installments.core.money.MoneyMath
import com.smartmanager.installments.data.db.ProductEntity
import com.smartmanager.installments.data.repo.ProductInput
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.LocalSettings
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.components.errorText
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ProductsVm(c: AppContainer) : ViewModel() {
    val query = MutableStateFlow("")
    val archived = MutableStateFlow(false)
    val paged: Flow<PagingData<ProductEntity>> = combine(query, archived) { q, a -> q.trim() to a }.flatMapLatest { (q, a) ->
        Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) { c.db.products().paged(q, !a) }.flow
    }.cachedIn(viewModelScope)
}

@Composable
fun ProductsScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val vm = appVm { ProductsVm(it) }
    val q by vm.query.collectAsStateWithLifecycle()
    val archived by vm.archived.collectAsStateWithLifecycle()
    val items = vm.paged.collectAsLazyPagingItems()
    val fmt = LocalFmt.current
    val readOnly = LocalReadOnly.current
    Box(Modifier.fillMaxSize()) {
        Column {
            SMTopBar(stringResource(R.string.products_catalog), onBack = onBack)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp)) {
                    Field(q, { vm.query.value = it }, stringResource(R.string.search_products))
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(archived, { vm.archived.value = it }); Text(stringResource(R.string.show_archived)) }
                    if (items.itemCount == 0) EmptyState(stringResource(R.string.no_products))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(count = items.itemCount, key = items.itemKey { it.id }) { i ->
                            items[i]?.let { p ->
                                SMCard(Modifier.fillMaxWidth(), onClick = { onEdit(p.id) }) {
                                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(p.name, style = MaterialTheme.typography.titleSmall)
                                            Text(listOfNotNull(p.sku, p.category.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                                        }
                                        Text(fmt.money(p.priceMinor), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        item { Box(Modifier.padding(bottom = 88.dp)) }
                    }
                }
            }
        }
        if (!readOnly) FloatingActionButton(onClick = { onEdit(null) }, containerColor = Brand.Orange, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) { Icon(Icons.Filled.Add, null) }
    }
}

@Composable
fun ProductEditScreen(id: String?, onBack: () -> Unit) {
    val c = LocalContainer.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val cur = settings.currency
    val dec = Currencies.of(cur).decimals
    var name by remember { mutableStateOf("") }
    var sku by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var serial by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var show by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(id == null) }
    LaunchedEffect(id) {
        if (id != null) c.db.products().get(id)?.let { p ->
            name = p.name; sku = p.sku ?: ""; category = p.category; price = MoneyMath.format(p.priceMinor, dec, null, '.'); cost = p.costMinor?.let { MoneyMath.format(it, dec, null, '.') } ?: ""
            serial = p.serialNumber ?: ""; notes = p.notes; active = p.active; loaded = true
        }
    }
    Column {
        SMTopBar(stringResource(if (id == null) R.string.add_product else R.string.edit_product), onBack = onBack)
        if (!loaded) return@Column
        ContentColumn {
            ErrorBanner(errorText(error))
            val req = stringResource(R.string.required)
            val priceMinor = MoneyMath.parse(price, dec)
            Field(name, { name = it }, stringResource(R.string.product_name) + " *", error = if (show && name.isBlank()) req else null)
            Field(sku, { sku = it }, stringResource(R.string.sku))
            Field(category, { category = it }, stringResource(R.string.category))
            Field(price, { price = it }, stringResource(R.string.selling_price) + " *", keyboard = KeyboardType.Decimal, suffix = cur, error = if (show && priceMinor == null) req else null)
            Field(cost, { cost = it }, stringResource(R.string.cost_price), keyboard = KeyboardType.Decimal, suffix = cur)
            Field(serial, { serial = it }, stringResource(R.string.serial_number))
            Field(notes, { notes = it }, stringResource(R.string.notes), singleLine = false, minLines = 2)
            PrimaryButton(stringResource(R.string.save), {
                show = true
                if (name.isNotBlank() && priceMinor != null) scope.launch {
                    runCatching { c.products.save(ProductInput(id, name, sku, category, priceMinor, MoneyMath.parse(cost, dec), serial, notes)) }.onSuccess { onBack() }.onFailure { error = it }
                }
            })
            if (id != null) {
                SecondaryButton(stringResource(if (active) R.string.archive_product else R.string.restore_product), { scope.launch { runCatching { c.products.setActive(id, !active) }.onSuccess { onBack() }.onFailure { error = it } } })
            }
        }
    }
}
