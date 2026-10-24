package com.smartmanager.installments.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.smartmanager.installments.BuildConfig
import com.smartmanager.installments.R
import com.smartmanager.installments.ui.components.ConfirmDialog
import com.smartmanager.installments.ui.components.ContentColumn
import com.smartmanager.installments.ui.components.ErrorBanner
import com.smartmanager.installments.ui.components.InfoBanner
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.PrimaryButton
import com.smartmanager.installments.ui.components.SecondaryButton
import com.smartmanager.installments.ui.components.errorText
import kotlinx.coroutines.launch

@Composable
fun DemoSection() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { count = c.demo.count() }
    ContentColumn {
        ErrorBanner(errorText(error))
        InfoBanner(stringResource(R.string.demo_explain))
        InfoBanner(stringResource(R.string.demo_count, count))
        if (BuildConfig.DEMO_DATA_ENABLED) PrimaryButton(stringResource(R.string.demo_generate), {
            busy = true; scope.launch { runCatching { c.demo.generate() }.onFailure { error = it }; busy = false }
        }, enabled = !busy)
        SecondaryButton(stringResource(R.string.demo_remove), { confirmRemove = true }, enabled = count > 0 && !busy)
    }
    if (confirmRemove) ConfirmDialog(stringResource(R.string.demo_remove), stringResource(R.string.demo_remove_msg), stringResource(R.string.delete), destructive = true,
        onConfirm = { confirmRemove = false; busy = true; scope.launch { runCatching { c.demo.remove() }.onFailure { error = it }; busy = false } }, onDismiss = { confirmRemove = false })
}
