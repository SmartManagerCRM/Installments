package com.smartmanager.installments.ui.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.smartmanager.installments.AppContainer
import com.smartmanager.installments.R
import com.smartmanager.installments.core.payments.InstallmentStatus
import com.smartmanager.installments.core.payments.InstallmentStatusCalculator
import com.smartmanager.installments.core.reminders.ReminderType
import com.smartmanager.installments.data.db.AmountCount
import com.smartmanager.installments.data.db.InstallmentRow
import com.smartmanager.installments.data.db.OverdueCustomerRow
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.LocalReadOnly
import com.smartmanager.installments.ui.components.LocalSettings
import com.smartmanager.installments.ui.components.ReminderRequest
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SectionTitle
import com.smartmanager.installments.ui.components.StatusChip
import com.smartmanager.installments.ui.components.appVm
import com.smartmanager.installments.ui.components.rememberReminderLauncher
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

data class DashState(
    val today: LocalDate,
    val currency: String,
    val outstanding: Long,
    val dueToday: AmountCount,
    val overdue: AmountCount,
    val customers: Int,
    val activeContracts: Int,
    val overdueCustomers: List<OverdueCustomerRow>,
    val upcoming: List<InstallmentRow>,
    val otherCurrencyContracts: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardVm(c: AppContainer) : ViewModel() {
    // Every figure is a live query on the real records; nothing here is cached or hardcoded.
    val state: StateFlow<DashState?> = combine(c.ticker.today, c.settings.settings.map { it.currency }.distinctUntilChanged()) { t, cur -> t to cur }
        .flatMapLatest { (today, cur) ->
            val d = c.db.dashboard()
            val e = today.toEpochDay()
            val a = combine(d.outstanding(cur), d.dueToday(e, cur), d.overdue(e, cur), c.db.customers().observeActiveCount(), c.db.contracts().observeActiveCount()) { o, dt, ov, cu, ac ->
                listOf(o, dt, ov, cu, ac)
            }
            val b = combine(d.overdueCustomers(e, cur, 5), d.upcoming(e, cur, 8), c.db.contracts().observeOtherCurrencyActive(cur)) { oc, up, other -> Triple(oc, up, other) }
            combine(a, b) { x, y ->
                @Suppress("UNCHECKED_CAST")
                DashState(today, cur, x[0] as Long, x[1] as AmountCount, x[2] as AmountCount, x[3] as Int, x[4] as Int, y.first, y.second, y.third)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun DashboardScreen(
    onOpenCustomer: (String) -> Unit, onOpenContract: (String) -> Unit, onOpenSettings: () -> Unit, onCollection: () -> Unit,
    onAddCustomer: () -> Unit, onNewContract: () -> Unit, onRecordPayment: () -> Unit, onOverdueList: () -> Unit, onDueTodayList: () -> Unit,
    onCustomersList: () -> Unit, onContractsList: () -> Unit, onOpenPaymentFor: (String) -> Unit,
) {
    val vm = appVm { DashboardVm(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val fmt = LocalFmt.current
    val settings = LocalSettings.current
    val readOnly = LocalReadOnly.current
    val remind = rememberReminderLauncher()

    ContentColumn(maxWidth = 960) {
        // Header: menu | logo + name | notification indicator (mirrors automatically in RTL)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Menu, stringResource(R.string.settings), tint = Brand.Navy) }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_logo), null, Modifier.size(44.dp))
                Spacer(Modifier.size(8.dp))
                Column {
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall, color = Brand.Navy, maxLines = 1)
                    if (settings.businessName.isNotBlank()) Text(settings.businessName, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, maxLines = 1)
                }
            }
            val alerts = (s?.dueToday?.count ?: 0) + (s?.overdue?.count ?: 0)
            IconButton(onClick = onCollection) {
                BadgedBox(badge = { if (alerts > 0) Badge(containerColor = Brand.Red) { Text(alerts.toString()) } }) {
                    Icon(Icons.Filled.Notifications, stringResource(R.string.collection_center), tint = Brand.Navy)
                }
            }
        }

        val st = s
        if (st == null) {
            Spacer(Modifier.height(200.dp))
        } else {
            // Total outstanding hero card (tap: outstanding report is reachable from Reports; here it opens the collection center)
            Box(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Brand.Navy, Color(0xFF1A4C9C))), RoundedCornerShape(22.dp))
                    .clickable(onClick = onCollection).padding(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.total_outstanding), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(fmt.money(st.outstanding, st.currency), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(stringResource(R.string.across_contracts, st.activeContracts), color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium)
                    }
                    Image(painterResource(R.drawable.ic_coins), null, Modifier.size(72.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.White)
                }
            }
            if (st.otherCurrencyContracts > 0) {
                Text(stringResource(R.string.other_currency_note, st.otherCurrencyContracts), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }

            // Summary cards: overdue and due-today first (what needs attention), then contracts and customers. 2x2 on phones, 4 across on tablets.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cards = listOf<@Composable (Modifier) -> Unit>(
                    { m -> SummaryCard(m, stringResource(R.string.overdue), fmt.money(st.overdue.amountMinor, st.currency), stringResource(R.string.n_customers, st.overdue.count), Brand.Red, Brand.RedSoft, Icons.Filled.Warning, onOverdueList) },
                    { m -> SummaryCard(m, stringResource(R.string.due_today), fmt.money(st.dueToday.amountMinor, st.currency), stringResource(R.string.n_customers, st.dueToday.count), Brand.Green, Brand.GreenSoft, Icons.Filled.CalendarMonth, onDueTodayList) },
                    { m -> SummaryCard(m, stringResource(R.string.active_contracts), st.activeContracts.toString(), stringResource(R.string.installment_plans), Brand.Purple, Brand.PurpleSoft, Icons.Filled.Description, onContractsList) },
                    { m -> SummaryCard(m, stringResource(R.string.total_customers), st.customers.toString(), stringResource(R.string.registered), Brand.Blue, Brand.BlueSoft, Icons.Filled.People, onCustomersList) },
                )
                val perRow = if (maxWidth > 640.dp) 4 else 2
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    cards.chunked(perRow).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { row.forEach { it(Modifier.weight(1f)) } }
                    }
                }
            }

            // Recent overdue customers
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.recent_overdue_customers), style = MaterialTheme.typography.titleMedium, color = Brand.Navy, modifier = Modifier.weight(1f))
                        if (st.overdueCustomers.isNotEmpty()) Text(stringResource(R.string.view_all), color = Brand.Blue, modifier = Modifier.clickable { onOverdueList() }, fontWeight = FontWeight.SemiBold)
                    }
                    if (st.overdueCustomers.isEmpty()) EmptyState(stringResource(R.string.no_overdue))
                    st.overdueCustomers.forEachIndexed { i, r ->
                        val days = st.today.toEpochDay() - r.oldestDueDate
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenCustomer(r.customerId) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(r.customerName)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(r.customerName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(fmt.money(r.overdueMinor, st.currency), color = Brand.Red, fontWeight = FontWeight.Bold)
                            }
                            Text(stringResource(R.string.days_overdue, days.toInt()), color = Brand.Red, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = {
                                remind(ReminderRequest(r.customerId, r.customerName, r.phone, null, ReminderType.OVERDUE, r.overdueMinor, st.currency, r.oldestDueDate))
                            }) {
                                Box(Modifier.size(40.dp).background(Brand.GreenSoft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Chat, stringResource(R.string.whatsapp_reminder), tint = Brand.Green)
                                }
                            }
                        }
                        if (i < st.overdueCustomers.lastIndex) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            // Quick actions: one row of four cards with a tinted icon each (orange = main call to action)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction(Modifier.weight(1f), stringResource(R.string.add_customer), Icons.Filled.PersonAdd, Brand.Green, Brand.GreenSoft, !readOnly, onAddCustomer)
                QuickAction(Modifier.weight(1f), stringResource(R.string.new_contract), Icons.Filled.Description, Brand.Blue, Brand.BlueSoft, !readOnly, onNewContract)
                QuickAction(Modifier.weight(1f), stringResource(R.string.record_payment), Icons.Filled.AccountBalanceWallet, Brand.Orange, Brand.OrangeSoft, !readOnly, onRecordPayment)
                QuickAction(Modifier.weight(1f), stringResource(R.string.send_reminder), Icons.Filled.Chat, Brand.Purple, Brand.PurpleSoft, true, onCollection)
            }

            // Upcoming payments
            SMCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.upcoming_payments), style = MaterialTheme.typography.titleMedium, color = Brand.Navy, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    if (st.upcoming.isEmpty()) EmptyState(stringResource(R.string.no_upcoming))
                    st.upcoming.forEachIndexed { i, r ->
                        UpcomingRow(r, st.today, onClick = { onOpenPaymentFor(r.b.contractId) })
                        if (i < st.upcoming.lastIndex) androidx.compose.material3.HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun Avatar(name: String) {
    Box(Modifier.size(44.dp).background(Brand.BlueSoft, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Brand.Blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun UpcomingRow(r: InstallmentRow, today: LocalDate, onClick: () -> Unit) {
    val fmt = LocalFmt.current
    val status = InstallmentStatusCalculator.status(LocalDate.ofEpochDay(r.b.dueDate), r.b.amountMinor, r.b.paidMinor, today)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(Brand.BlueSoft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CalendarMonth, null, tint = Brand.Blue, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(r.customerName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text("${fmt.date(r.b.dueDate)} \u00B7 ${r.b.contractNumber}", style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(fmt.money(r.b.outstandingMinor, r.b.currency), fontWeight = FontWeight.Bold)
            StatusBadge(status)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.Muted)
    }
}

@Composable
fun StatusBadge(status: InstallmentStatus) {
    val (res, fg, bg) = when (status) {
        InstallmentStatus.PAID -> Triple(R.string.status_paid, Brand.Green, Brand.GreenSoft)
        InstallmentStatus.OVERDUE -> Triple(R.string.status_overdue, Brand.Red, Brand.RedSoft)
        InstallmentStatus.DUE_TODAY -> Triple(R.string.status_due_today, Brand.Orange, Brand.OrangeSoft)
        InstallmentStatus.PARTIALLY_PAID -> Triple(R.string.status_partial, Brand.Blue, Brand.BlueSoft)
        InstallmentStatus.UPCOMING -> Triple(R.string.status_upcoming, Brand.Blue, Brand.BlueSoft)
        InstallmentStatus.RESCHEDULED -> Triple(R.string.status_rescheduled, Brand.Muted, Color(0xFFE9EFF8))
        InstallmentStatus.CANCELLED -> Triple(R.string.status_cancelled, Brand.Muted, Color(0xFFE9EFF8))
    }
    StatusChip(stringResource(res), fg, bg)
}

@Composable
private fun SummaryCard(m: Modifier, title: String, value: String, sub: String, color: Color, soft: Color, icon: ImageVector, onClick: () -> Unit) {
    SMCard(m, onClick = onClick, container = soft.copy(alpha = 0.55f)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(24.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = Brand.Navy, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Brand.Muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun QuickAction(m: Modifier, label: String, icon: ImageVector, color: Color, soft: Color, enabled: Boolean, onClick: () -> Unit) {
    SMCard(m, onClick = if (enabled) onClick else null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(48.dp).background(if (enabled) soft else Color(0xFFEEF1F6), CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (enabled) color else Brand.Muted, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.Navy, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
