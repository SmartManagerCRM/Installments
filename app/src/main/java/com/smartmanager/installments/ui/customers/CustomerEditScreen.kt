package com.smartmanager.installments.ui.customers

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.smartmanager.installments.R
import com.smartmanager.installments.data.db.CustomerEntity
import com.smartmanager.installments.data.repo.CustomerInput
import com.smartmanager.installments.ui.components.ConfirmDialog
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.Field
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SMTopBar
import com.smartmanager.installments.ui.components.errorText
import kotlinx.coroutines.launch

@Composable
fun CustomerEditScreen(id: String?, onBack: () -> Unit, onSaved: (String) -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf(id == null) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var alt by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var ref by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var showErrors by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var similar by remember { mutableStateOf<List<CustomerEntity>>(emptyList()) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(id) {
        if (id != null) c.db.customers().get(id)?.let { e ->
            name = e.fullName; phone = e.phone; alt = e.altPhone ?: ""; address = e.address; ref = e.nationalRef ?: ""; notes = e.notes; loaded = true
        }
    }

    fun doSave() {
        saving = true
        scope.launch {
            runCatching { c.customers.save(CustomerInput(id, name, phone, alt, address, ref, notes)) }
                .onSuccess { saving = false; onSaved(it) }
                .onFailure { saving = false; error = it }
        }
    }

    Column {
        SMTopBar(stringResource(if (id == null) R.string.add_customer else R.string.edit_customer), onBack = onBack)
        if (!loaded) return@Column
        ContentColumn {
            ErrorBanner(errorText(error))
            val req = stringResource(R.string.required)
            Field(name, { name = it }, stringResource(R.string.full_name) + " *", error = if (showErrors && name.isBlank()) req else null)
            Field(phone, { phone = it }, stringResource(R.string.phone) + " *", keyboard = KeyboardType.Phone, error = if (showErrors && phone.isBlank()) req else null)
            Field(alt, { alt = it }, stringResource(R.string.alt_phone), keyboard = KeyboardType.Phone)
            Field(address, { address = it }, stringResource(R.string.address), singleLine = false, minLines = 2)
            Field(ref, { ref = it }, stringResource(R.string.national_ref))
            Field(notes, { notes = it }, stringResource(R.string.notes), singleLine = false, minLines = 2)
            PrimaryButton(stringResource(R.string.save), {
                showErrors = true
                if (name.isNotBlank() && phone.isNotBlank() && !saving) {
                    scope.launch {
                        // Duplicates are only a warning: two legitimate customers may share a name or a phone.
                        val dup = c.customers.findSimilar(name, phone, id)
                        if (dup.isEmpty()) doSave() else similar = dup
                    }
                }
            }, enabled = !saving)
        }
        if (similar.isNotEmpty()) ConfirmDialog(
            stringResource(R.string.possible_duplicate_title),
            stringResource(R.string.possible_duplicate_msg) + "\n\n" + similar.joinToString("\n") { "• ${it.fullName} (${it.phone})" },
            stringResource(R.string.save_anyway), onConfirm = { similar = emptyList(); doSave() }, onDismiss = { similar = emptyList() },
        )
    }
}
