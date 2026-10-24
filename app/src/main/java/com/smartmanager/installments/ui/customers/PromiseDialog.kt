package com.smartmanager.installments.ui.customers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartmanager.installments.R
import com.smartmanager.installments.ui.components.DateField
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.LocalFmt
import java.time.LocalDate

/** Records a customer's promise to pay (amount, date, notes). The outcome is entered later, explicitly, by the retailer. */
@Composable
fun PromiseDialog(currency: String, onDismiss: () -> Unit, onSave: (amountMinor: Long, epochDay: Long, notes: String) -> Unit) {
    val fmt = LocalFmt.current
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().plusDays(3)) }
    var notes by remember { mutableStateOf("") }
    val parsed = fmt.parseMoney(amount, currency)
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(stringResource(R.string.add_promise)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(amount, { amount = it }, stringResource(R.string.promised_amount), keyboard = KeyboardType.Decimal, suffix = currency)
                DateField(stringResource(R.string.promised_date), date, { date = it })
                Field(notes, { notes = it }, stringResource(R.string.notes), singleLine = false, minLines = 2)
            }
        },
        confirmButton = { TextButton(enabled = parsed != null && parsed > 0, onClick = { onSave(parsed!!, date.toEpochDay(), notes) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
