package com.smartmanager.installments.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smartmanager.installments.AppContainer
import com.smartmanager.installments.R
import com.smartmanager.installments.core.licensing.LicenseStatus
import com.smartmanager.installments.data.repo.AppSettings
import com.smartmanager.installments.ui.components.LocalContainer
import com.smartmanager.installments.ui.components.LocalSnack
import com.smartmanager.installments.ui.components.ProvideAppLocals
import com.smartmanager.installments.ui.license.ActivationScreen
import com.smartmanager.installments.ui.navigation.AppNavHost
import com.smartmanager.installments.ui.navigation.Routes
import com.smartmanager.installments.ui.theme.Brand
import com.smartmanager.installments.ui.theme.SmartManagerTheme
import com.smartmanager.installments.util.Formatters

private val NOT_USABLE = setOf(
    LicenseStatus.NOT_ACTIVATED, LicenseStatus.MALFORMED, LicenseStatus.INVALID_SIGNATURE, LicenseStatus.WRONG_DEVICE,
    LicenseStatus.UNKNOWN_KEY, LicenseStatus.WRONG_PRODUCT, LicenseStatus.UNSUPPORTED_VERSION,
)

@Composable
fun AppRoot(container: AppContainer, windowSize: WindowSizeClass) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val lic by container.license.state.collectAsStateWithLifecycle()
    val ready by container.license.ready.collectAsStateWithLifecycle()
    val lang = LocalConfiguration.current.locales[0].language
    val fmt = remember(lang, settings.dateFormat, settings.currency) { Formatters(lang, settings.dateFormat, settings.currency) }
    val snack = remember { SnackbarHostState() }
    var readOnlyChosen by rememberSaveable { mutableStateOf(false) }

    SmartManagerTheme {
        ProvideAppLocals(container, fmt, settings, readOnly = !lic.allowsWrites) {
            CompositionLocalProvider(LocalSnack provides snack) {
                when {
                    !ready -> Box(Modifier.fillMaxSize())
                    lic.status in NOT_USABLE && !readOnlyChosen -> ActivationScreen(onContinueReadOnly = { readOnlyChosen = true })
                    else -> MainShell(windowSize, snack)
                }
            }
        }
    }
}

private data class NavItem(val route: String, val label: Int, val icon: ImageVector)

private val items = listOf(
    NavItem(Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    NavItem(Routes.CUSTOMERS, R.string.nav_customers, Icons.Filled.People),
    NavItem(Routes.PAYMENTS, R.string.nav_payments, Icons.Filled.AccountBalanceWallet),
    NavItem(Routes.CONTRACTS, R.string.nav_contracts, Icons.Filled.Description),
    NavItem(Routes.REPORTS, R.string.nav_reports, Icons.Filled.Assessment),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(windowSize: WindowSizeClass, snack: SnackbarHostState) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBars = current != null && current in Routes.topLevel
    val wide = windowSize.widthSizeClass != WindowWidthSizeClass.Compact

    Scaffold(
        containerColor = Brand.Background,
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            if (!wide && showBars) NavigationBar(containerColor = Brand.Surface) {
                items.forEach { i ->
                    NavigationBarItem(
                        selected = current == i.route, onClick = { go(nav, i.route) },
                        icon = { Icon(i.icon, null) }, label = { Text(stringResource(i.label), maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.padding(padding).fillMaxSize()) {
            if (wide && showBars) NavigationRail(containerColor = Brand.Surface) {
                items.forEach { i ->
                    NavigationRailItem(
                        selected = current == i.route, onClick = { go(nav, i.route) },
                        icon = { Icon(i.icon, null) }, label = { Text(stringResource(i.label)) },
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                LicenseBanner { nav.navigate(Routes.settingsSection("license")) }
                AppNavHost(nav, wide)
            }
        }
    }
}

private fun go(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}


/** Warns before a duration license expires and explains read-only mode after expiry (data is never hidden or deleted). */
@Composable
private fun LicenseBanner(onClick: () -> Unit) {
    val lic by LocalContainer.current.license.state.collectAsStateWithLifecycle()
    val readOnly = !lic.allowsWrites
    val soon = lic.status == LicenseStatus.VALID && lic.expiringSoon
    if (!readOnly && !soon) return
    val text = if (soon) stringResource(R.string.lic_expiring_soon, (lic.daysRemaining ?: 0L).toInt())
    else com.smartmanager.installments.ui.license.statusMessage(lic.status)
    Box(
        Modifier.fillMaxWidth().background(if (soon) Brand.OrangeSoft else Brand.RedSoft).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) { Text(text, color = if (soon) Color(0xFF7A4300) else Brand.Red, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
}
