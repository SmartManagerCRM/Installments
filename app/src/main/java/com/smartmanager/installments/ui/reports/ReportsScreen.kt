package com.smartmanager.installments.ui.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.R
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.repo.ReportTable
import com.smartmanager.installments.data.repo.ReportType
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.DateField
import com.smartmanager.installments.ui.components.EmptyState
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalSnack
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.theme.Brand
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class ReportEntry(val type: ReportType, val title: Int, val desc: Int)

private val entries = listOf(
    ReportEntry(ReportType.OUTSTANDING, R.string.report_outstanding, R.string.report_outstanding_desc),
    ReportEntry(ReportType.COLLECTED, R.string.report_collected, R.string.report_collected_desc),
    ReportEntry(ReportType.OVERDUE, R.string.report_overdue, R.string.report_overdue_desc),
    ReportEntry(ReportType.DUE, R.string.report_due, R.string.report_due_desc),
    ReportEntry(ReportType.ACTIVE, R.string.report_active, R.string.report_active_desc),
    ReportEntry(ReportType.COMPLETED, R.string.report_completed, R.string.report_completed_desc),
    ReportEntry(ReportType.STATEMENT, R.string.report_statement, R.string.report_statement_desc),
    ReportEntry(ReportType.PAYMENT_HISTORY, R.string.report_payment_history, R.string.report_payment_history_desc),
)

@Composable
fun ReportsScreen(onOpen: (String) -> Unit) {
    Column {
        SMTopBar(stringResource(R.string.nav_reports))
        ContentColumn {
            entries.forEach { e ->
                SMCard(Modifier.fillMaxWidth(), onClick = { onOpen(e.type.key) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.TableChart, null, tint = Brand.Blue)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(stringResource(e.title), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(e.desc), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brand.Muted)
                    }
                }
            }
        }
    }
}

@Composable
fun ReportScreen(typeKey: String, onBack: () -> Unit) {
    val type = ReportType.of(typeKey)
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = LocalSnack.current
    val needsPeriod = type in setOf(ReportType.COLLECTED, ReportType.DUE, ReportType.PAYMENT_HISTORY)
    var from by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var to by remember { mutableStateOf(if (type == ReportType.DUE) LocalDate.now().plusMonths(1) else LocalDate.now()) }
    var table by remember { mutableStateOf<ReportTable?>(null) }
    var customer by remember { mutableStateOf<CustomerEntity?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CustomerEntity>>(emptyList()) }
    val failed = stringResource(R.string.pdf_failed)

    fun run() { scope.launch { table = runCatching { c.reports.build(type, from, to, customer?.id) }.getOrNull() } }
    LaunchedEffect(type, from, to, customer) { if (type != ReportType.STATEMENT || customer != null) run() }
    LaunchedEffect(query) { if (type == ReportType.STATEMENT) results = c.db.customers().search(query.trim()) }

    Column {
        SMTopBar(stringResource(entries.first { it.type == type }.title), onBack = onBack)
        ContentColumn(maxWidth = 1000) {
            if (needsPeriod) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField(stringResource(R.string.from_date), from, { from = it }, Modifier.weight(1f))
                DateField(stringResource(R.string.to_date), to, { to = it }, Modifier.weight(1f))
            }
            if (type == ReportType.STATEMENT) {
                if (customer == null) {
                    Field(query, { query = it }, stringResource(R.string.search_customers))
                    results.forEach { cu -> SMCard(Modifier.fillMaxWidth(), onClick = { customer = cu }) { Column(Modifier.padding(14.dp)) { Text(cu.fullName, style = MaterialTheme.typography.titleSmall); Text(cu.phone, style = MaterialTheme.typography.bodySmall, color = Brand.Muted) } } }
                } else Text(customer!!.fullName, style = MaterialTheme.typography.titleLarge)
            }
            val t = table
            if (t != null) {
                Text(t.title, style = MaterialTheme.typography.titleMedium)
                Text(t.subtitle, color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.export_pdf), {
                        scope.launch { runCatching { c.pdf.report(t) }.onSuccess { c.pdf.share(ctx, it) }.onFailure { snack.showSnackbar(failed) } }
                    }, Modifier.weight(1f), icon = Icons.Filled.Share)
                    OutlinedButton(onClick = {
                        scope.launch { runCatching { c.pdf.csv(t) }.onSuccess { c.pdf.share(ctx, it, "text/csv") }.onFailure { snack.showSnackbar(failed) } }
                    }, Modifier.weight(1f).padding(top = 0.dp)) { Text(stringResource(R.string.export_csv)) }
                }
                if (t.rows.isEmpty()) EmptyState(stringResource(R.string.report_empty))
                else SMCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                        val widths = t.columns.indices.map { col -> if (col in t.numericColumns) 110.dp else 150.dp }
                        Row { t.columns.forEachIndexed { i, h -> Text(h, Modifier.width(widths[i]).padding(6.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = Brand.Muted) } }
                        HorizontalDivider()
                        t.rows.forEach { r ->
                            Row { r.forEachIndexed { i, v -> Text(v, Modifier.width(widths[i]).padding(6.dp), style = MaterialTheme.typography.bodySmall, textAlign = if (i in t.numericColumns) TextAlign.End else TextAlign.Start) } }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        t.totals?.let { r -> Row { r.forEachIndexed { i, v -> Text(v, Modifier.width(widths[i]).padding(6.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, textAlign = if (i in t.numericColumns) TextAlign.End else TextAlign.Start) } } }
                    }
                }
            }
        }
    }
}
