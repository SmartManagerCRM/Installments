package com.smartmanager.installments.ui.contracts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.R
import com.smartmanager.installments.core.money.Currencies
import com.smartmanager.installments.core.money.MoneyMath
import com.smartmanager.installments.core.schedule.ScheduledInstallment
import com.smartmanager.installments.ui.components.DateField
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.LocalFmt
import com.smartmanager.installments.ui.components.SMCard
import com.smartmanager.installments.ui.theme.Brand
import java.time.LocalDate

data class SchedRow(val key: Long, val date: LocalDate, val amount: String)

fun List<SchedRow>.toScheduled(currency: String): List<ScheduledInstallment>? {
    val dec = Currencies.of(currency).decimals
    val out = mutableListOf<ScheduledInstallment>()
    forEachIndexed { i, r -> out += ScheduledInstallment(i + 1, r.date, MoneyMath.parse(r.amount, dec) ?: return null) }
    return out
}

/** Editable schedule: the retailer can change any date or amount, add or remove rows; the running total is checked live. */
@Composable
fun ScheduleEditor(rows: List<SchedRow>, onRows: (List<SchedRow>) -> Unit, currency: String, expectedMinor: Long) {
    val fmt = LocalFmt.current
    val parsed = rows.toScheduled(currency)
    val sum = parsed?.sumOf { it.amountMinor }
    val diff = if (sum == null) null else expectedMinor - sum
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, r ->
            SMCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#${i + 1}", fontWeight = FontWeight.Bold, color = Brand.Muted)
                    DateField(stringResource(R.string.due_date), r.date, { d -> onRows(rows.map { if (it.key == r.key) it.copy(date = d) else it }) }, Modifier.weight(1f))
                    Field(r.amount, { a -> onRows(rows.map { if (it.key == r.key) it.copy(amount = a) else it }) }, stringResource(R.string.amount), Modifier.weight(0.8f), keyboard = KeyboardType.Decimal)
                    IconButton(onClick = { onRows(rows.filter { it.key != r.key }) }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = Brand.Red) }
                }
            }
        }
        TextButton(onClick = {
            val last = rows.lastOrNull()?.date ?: LocalDate.now()
            onRows(rows + SchedRow(System.nanoTime(), last.plusMonths(1), if (diff != null && diff > 0) fmt.editable(diff, currency) else ""))
        }) { Icon(Icons.Filled.Add, null); Text(" " + stringResource(R.string.add_installment)) }
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.schedule_total), modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
            Text(if (sum == null) "—" else fmt.money(sum, currency), fontWeight = FontWeight.Bold)
        }
        when {
            diff == null -> Text(stringResource(R.string.schedule_invalid_amount), color = Brand.Red, style = MaterialTheme.typography.bodySmall)
            diff == 0L -> Text("✓ " + stringResource(R.string.schedule_matches, fmt.money(expectedMinor, currency)), color = Brand.Green, style = MaterialTheme.typography.bodySmall)
            else -> Text(stringResource(R.string.schedule_difference, fmt.money(kotlin.math.abs(diff), currency), stringResource(if (diff > 0) R.string.too_low else R.string.too_high)), color = Brand.Red, style = MaterialTheme.typography.bodySmall)
        }
    }
}
