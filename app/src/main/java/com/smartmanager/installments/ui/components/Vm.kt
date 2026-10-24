package com.smartmanager.installments.ui.components

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartmanager.installments.AppContainer

/** Creates a ViewModel that receives the app container (manual DI). */
@Composable
inline fun <reified VM : ViewModel> appVm(key: String? = null, crossinline make: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    return viewModel(
        key = key,
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = make(container) as T
        },
    )
}
