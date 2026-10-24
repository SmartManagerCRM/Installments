package com.smartmanager.installments.ui.collection

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.smartmanager.installments.core.collection.CollectionClassifier
import com.smartmanager.installments.core.collection.CollectionEntry
import com.smartmanager.installments.core.collection.CollectionGroup
import com.smartmanager.installments.core.collection.CollectionInput
import com.smartmanager.installments.core.collection.RecommendedAction
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.ReminderRequest
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.components.dialPhone
import com.smartmanager.installments.ui.components.rememberReminderLauncher
import com.smartmanager.installments.ui.customers.PromiseDialog
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CollectionState(val groups: Map<CollectionGroup, List<CollectionEntry>>, val currency: String, val todayEpochDay: Long)

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionVm(val c: AppContainer) : ViewModel() {
    val state: StateFlow<CollectionState?> = combine(c.ticker.today, c.settings.settings.map { it.currency }.distinctUntilChanged()) { t, cur -> t to cur }
        .flatMapLatest { (today, cur) ->
            val e = today.toEpochDay()
            combine(c.db.dashboard().collectionRows(e, cur), c.db.promises().observeOpen()) { rows, promises ->
                val byCustomer = promises.groupBy { it.customerId }.mapValues { (_, l) -> l.minByOrNull { it.promisedDate }!! }
                val inputs = rows.map { r ->
                    val p = byCustomer[r.customerId]
                    CollectionInput(r.customerId, r.customerName, r.phone, r.outstandingMinor, r.overdueMinor, r.dueTodayMinor, (e - r.oldestOverdueDate).coerceAtLeast(0),
                        r.hasPartial == 1, p?.promisedDate, p?.amountMinor)
                }
                CollectionState(CollectionClassifier.classify(inputs, e), cur, e)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

private fun groupLabel(g: CollectionGroup) = when (g) {
    CollectionGroup.DUE_TODAY -> R.string.group_due_today
    CollectionGroup.OVERDUE_1_7 -> R.string.group_overdue_1_7
    CollectionGroup.OVERDUE_8_30 -> R.string.group_overdue_8_30
    CollectionGroup.OVERDUE_30_PLUS -> R.string.group_overdue_30_plus
    CollectionGroup.PARTIAL_PAYERS -> R.string.group_partial
    CollectionGroup.PROMISED -> R.string.group_promised
}

private fun actionLabel(a: RecommendedAction) = when (a) {
    RecommendedAction.SEND_TODAY_REMINDER -> R.string.action_send_today
    RecommendedAction.SEND_OVERDUE_REMINDER -> R.string.action_send_overdue
    RecommendedAction.CALL_AND_AGREE_DATE -> R.string.action_call_agree
    RecommendedAction.ESCALATE -> R.string.action_escalate
    RecommendedAction.FOLLOW_UP_PROMISE -> R.string.action_follow_promise
    RecommendedAction.REMIND_REMAINING_BALANCE -> R.string.action_remind_remaining
}

@Composable
fun CollectionScreen(onBack: () -> Unit, onOpenCustomer: (String) -> Unit) {
    val vm = appVm { CollectionVm(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val readOnly = LocalReadOnly.current
    val ctx = LocalContext.current
    val remind = rememberReminderLauncher()
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable { mutableStateOf(CollectionGroup.DUE_TODAY.name) }
    var promiseFor by remember { mutableStateOf<CollectionEntry?>(null) }

    Column {
        SMTopBar(stringResource(R.string.collection_center), onBack = onBack)
        val st = s ?: return@Column
        ContentColumn(maxWidth = 760) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CollectionGroup.entries.forEach { g ->
                    FilterChip(selected = selected == g.name, onClick = { selected = g.name }, label = { Text(stringResource(groupLabel(g)) + " (" + (st.groups[g]?.size ?: 0) + ")") })
                }
            }
            val list = st.groups[CollectionGroup.valueOf(selected)].orEmpty()
            InfoBanner(stringResource(R.string.collection_hint))
            if (list.isEmpty()) EmptyState(stringResource(R.string.collection_empty))
            list.forEach { e ->
                val i = e.input
                SMCard(Modifier.fillMaxWidth(), onClick = { onOpenCustomer(i.customerId) }) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row {
                            Text(i.customerName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text(fmt.money(i.outstandingMinor, st.currency), fontWeight = FontWeight.Bold, color = Brand.Navy)
                        }
                        if (i.overdueMinor > 0) Text(stringResource(R.string.overdue) + " " + fmt.money(i.overdueMinor, st.currency) + " · " + stringResource(R.string.days_overdue, i.oldestOverdueDays.toInt()), color = Brand.Red, style = MaterialTheme.typography.bodySmall)
                        if (i.dueTodayMinor > 0) Text(stringResource(R.string.due_today) + " " + fmt.money(i.dueTodayMinor, st.currency), color = Brand.Orange, style = MaterialTheme.typography.bodySmall)
                        i.openPromiseDateEpochDay?.let { pd -> Text(stringResource(R.string.promised_on, fmt.money(i.openPromiseAmountMinor ?: 0, st.currency), fmt.date(pd)), color = Brand.Blue, style = MaterialTheme.typography.bodySmall) }
                        Text("➜ " + stringResource(actionLabel(e.action)), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Brand.Navy)
                        Row {
                            IconButton(onClick = {
                                val type = when {
                                    e.action == RecommendedAction.FOLLOW_UP_PROMISE -> ReminderType.PROMISE_FOLLOWUP
                                    i.oldestOverdueDays > 0 -> ReminderType.OVERDUE
                                    i.dueTodayMinor > 0 -> ReminderType.DUE_TODAY
                                    else -> ReminderType.UPCOMING
                                }
                                val amount = if (type == ReminderType.PROMISE_FOLLOWUP) i.openPromiseAmountMinor ?: i.outstandingMinor else if (i.overdueMinor > 0) i.overdueMinor else if (i.dueTodayMinor > 0) i.dueTodayMinor else i.outstandingMinor
                                val date = if (type == ReminderType.PROMISE_FOLLOWUP) i.openPromiseDateEpochDay else st.todayEpochDay - i.oldestOverdueDays
                                remind(ReminderRequest(i.customerId, i.customerName, i.phone, null, type, amount, st.currency, date, balanceMinor = i.outstandingMinor))
                            }) { Icon(Icons.Filled.Chat, stringResource(R.string.whatsapp_reminder), tint = Brand.Green) }
                            IconButton(onClick = { dialPhone(ctx, i.phone) }) { Icon(Icons.Filled.Call, stringResource(R.string.call), tint = Brand.Blue) }
                            if (!readOnly) IconButton(onClick = { promiseFor = e }) { Icon(Icons.Filled.EventAvailable, stringResource(R.string.add_promise), tint = Brand.Orange) }
                        }
                    }
                }
            }
        }
        promiseFor?.let { e ->
            PromiseDialog(currency = st.currency, onDismiss = { promiseFor = null }, onSave = { amount, date, notes ->
                promiseFor = null
                scope.launch { runCatching { vm.c.collection.addPromise(e.input.customerId, null, amount, date, notes) } }
            })
        }
    }
}
