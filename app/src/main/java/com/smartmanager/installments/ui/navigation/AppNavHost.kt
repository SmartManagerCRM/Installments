package com.smartmanager.installments.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import com.smartmanager.installments.ui.theme.Brand
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.smartmanager.installments.ui.collection.CollectionScreen
import com.smartmanager.installments.ui.contracts.ContractDetailScreen
import com.smartmanager.installments.ui.contracts.ContractWizardScreen
import com.smartmanager.installments.ui.contracts.ContractsScreen
import com.smartmanager.installments.ui.contracts.RescheduleScreen
import com.smartmanager.installments.ui.contracts.SettleScreen
import com.smartmanager.installments.ui.customers.CustomerDetailScreen
import com.smartmanager.installments.ui.customers.CustomerEditScreen
import com.smartmanager.installments.ui.customers.CustomersScreen
import com.smartmanager.installments.ui.dashboard.DashboardScreen
import com.smartmanager.installments.ui.payments.PaymentDetailScreen
import com.smartmanager.installments.ui.payments.PaymentsScreen
import com.smartmanager.installments.ui.payments.RecordPaymentScreen
import com.smartmanager.installments.ui.products.ProductEditScreen
import com.smartmanager.installments.ui.products.ProductsScreen
import com.smartmanager.installments.ui.reports.ReportScreen
import com.smartmanager.installments.ui.reports.ReportsScreen
import com.smartmanager.installments.ui.settings.SettingsMenuScreen
import com.smartmanager.installments.ui.settings.SettingsSectionScreen

/** Like composable(), but the screen is painted on an opaque page background. */
private fun NavGraphBuilder.screen(route: String, arguments: List<NamedNavArgument> = emptyList(), content: @Composable (NavBackStackEntry) -> Unit) =
    composable(route, arguments) { e -> Box(Modifier.fillMaxSize().background(Brand.Background)) { content(e) } }

private val optionalString = { name: String -> navArgument(name) { type = NavType.StringType; defaultValue = ""; nullable = false } }

@Composable
fun AppNavHost(nav: NavHostController, wide: Boolean) {
    fun back() { nav.popBackStack() }
    fun String.orNull() = ifBlank { null }
    // Short, smooth fade. Every screen is drawn on an OPAQUE background (see screen()) so the previous page never shows through
    // while fading (that overlap looked like ghosted text/flicker). Navigation Compose's default is a slow 700 ms cross-fade.
    NavHost(
        nav, startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(150, easing = LinearOutSlowInEasing)) },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { fadeOut(tween(110, easing = FastOutLinearInEasing)) },
    ) {
        screen(Routes.HOME) {
            DashboardScreen(
                onOpenCustomer = { nav.navigate(Routes.customer(it)) }, onOpenContract = { nav.navigate(Routes.contract(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onCollection = { nav.navigate(Routes.COLLECTION) },
                onAddCustomer = { nav.navigate(Routes.customerEdit()) }, onNewContract = { nav.navigate(Routes.contractNew()) },
                onRecordPayment = { nav.navigate(Routes.paymentNew()) }, onOverdueList = { nav.navigate(Routes.COLLECTION) },
                onDueTodayList = { nav.navigate(Routes.COLLECTION) }, onCustomersList = { nav.navigate(Routes.CUSTOMERS) },
                onContractsList = { nav.navigate(Routes.CONTRACTS) }, onOpenPaymentFor = { nav.navigate(Routes.paymentNew(contractId = it)) },
            )
        }
        screen(Routes.CUSTOMERS) {
            CustomersScreen(wide, onOpenCustomer = { nav.navigate(Routes.customer(it)) }, onAdd = { nav.navigate(Routes.customerEdit()) }, onEdit = { nav.navigate(Routes.customerEdit(it)) },
                onOpenContract = { nav.navigate(Routes.contract(it)) }, onNewContract = { nav.navigate(Routes.contractNew(customerId = it)) }, onPay = { nav.navigate(Routes.paymentNew(customerId = it)) })
        }
        screen(Routes.CUSTOMER, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            CustomerDetailScreen(e.arguments!!.getString("id")!!, onBack = ::back, onEdit = { nav.navigate(Routes.customerEdit(it)) }, onOpenContract = { nav.navigate(Routes.contract(it)) },
                onNewContract = { nav.navigate(Routes.contractNew(customerId = it)) }, onPay = { nav.navigate(Routes.paymentNew(customerId = it)) })
        }
        screen(Routes.CUSTOMER_EDIT, listOf(optionalString("id"))) { e ->
            val id = e.arguments?.getString("id").orEmpty().orNull()
            CustomerEditScreen(id, onBack = ::back, onSaved = { saved -> nav.popBackStack(); if (id == null) nav.navigate(Routes.customer(saved)) })
        }
        screen(Routes.CONTRACTS) { ContractsScreen(onOpen = { nav.navigate(Routes.contract(it)) }, onNew = { nav.navigate(Routes.contractNew()) }) }
        screen(Routes.CONTRACT, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            ContractDetailScreen(e.arguments!!.getString("id")!!, onBack = ::back, onOpenCustomer = { nav.navigate(Routes.customer(it)) }, onPay = { nav.navigate(Routes.paymentNew(contractId = it)) },
                onSettle = { nav.navigate(Routes.settle(it)) }, onReschedule = { nav.navigate(Routes.reschedule(it)) }, onEditDraft = { nav.navigate(Routes.contractNew(draftId = it)) },
                onOpenPayment = { nav.navigate(Routes.payment(it)) })
        }
        screen(Routes.CONTRACT_WIZARD, listOf(optionalString("customerId"), optionalString("draftId"))) { e ->
            ContractWizardScreen(e.arguments?.getString("customerId").orEmpty().orNull(), e.arguments?.getString("draftId").orEmpty().orNull(), onBack = ::back,
                onDone = { id -> nav.popBackStack(); nav.navigate(Routes.contract(id)) })
        }
        screen(Routes.RESCHEDULE, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            RescheduleScreen(e.arguments!!.getString("id")!!, onBack = ::back, onDone = ::back)
        }
        screen(Routes.SETTLE, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            SettleScreen(e.arguments!!.getString("id")!!, onBack = ::back, onDone = { pid -> nav.popBackStack(); nav.navigate(Routes.payment(pid)) })
        }
        screen(Routes.PAYMENTS) {
            PaymentsScreen(onRecordPayment = { nav.navigate(Routes.paymentNew(contractId = it)) }, onOpenPayment = { nav.navigate(Routes.payment(it)) },
                onCollection = { nav.navigate(Routes.COLLECTION) }, onOpenContract = { nav.navigate(Routes.contract(it)) })
        }
        screen(Routes.PAYMENT_NEW, listOf(optionalString("contractId"), optionalString("customerId"))) { e ->
            RecordPaymentScreen(e.arguments?.getString("contractId").orEmpty().orNull(), e.arguments?.getString("customerId").orEmpty().orNull(), onBack = ::back,
                onDone = { pid -> nav.popBackStack(); nav.navigate(Routes.payment(pid)) })
        }
        screen(Routes.PAYMENT, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            PaymentDetailScreen(e.arguments!!.getString("id")!!, onBack = ::back, onOpenContract = { nav.navigate(Routes.contract(it)) })
        }
        screen(Routes.COLLECTION) { CollectionScreen(onBack = ::back, onOpenCustomer = { nav.navigate(Routes.customer(it)) }) }
        screen(Routes.REPORTS) { ReportsScreen(onOpen = { nav.navigate(Routes.report(it)) }) }
        screen(Routes.REPORT, listOf(navArgument("type") { type = NavType.StringType })) { e -> ReportScreen(e.arguments!!.getString("type")!!, onBack = ::back) }
        screen(Routes.SETTINGS) { SettingsMenuScreen(onBack = ::back, onSection = { nav.navigate(Routes.settingsSection(it)) }, onProducts = { nav.navigate(Routes.PRODUCTS) }) }
        screen(Routes.PRODUCTS) { ProductsScreen(onBack = ::back, onEdit = { nav.navigate(Routes.productEdit(it)) }) }
        screen(Routes.PRODUCT_EDIT, listOf(optionalString("id"))) { e -> ProductEditScreen(e.arguments?.getString("id").orEmpty().orNull(), onBack = ::back) }
        screen(Routes.SETTINGS_SECTION, listOf(navArgument("section") { type = NavType.StringType })) { e -> SettingsSectionScreen(e.arguments!!.getString("section")!!, onBack = ::back) }
    }
}
