package com.smartmanager.installments.ui.navigation

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

private val optionalString = { name: String -> navArgument(name) { type = NavType.StringType; defaultValue = ""; nullable = false } }

@Composable
fun AppNavHost(nav: NavHostController, wide: Boolean) {
    fun back() { nav.popBackStack() }
    fun String.orNull() = ifBlank { null }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            DashboardScreen(
                onOpenCustomer = { nav.navigate(Routes.customer(it)) }, onOpenContract = { nav.navigate(Routes.contract(it)) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) }, onCollection = { nav.navigate(Routes.COLLECTION) },
                onAddCustomer = { nav.navigate(Routes.customerEdit()) }, onNewContract = { nav.navigate(Routes.contractNew()) },
                onRecordPayment = { nav.navigate(Routes.paymentNew()) }, onOverdueList = { nav.navigate(Routes.COLLECTION) },
                onDueTodayList = { nav.navigate(Routes.COLLECTION) }, onCustomersList = { nav.navigate(Routes.CUSTOMERS) },
                onContractsList = { nav.navigate(Routes.CONTRACTS) }, onOpenPaymentFor = { nav.navigate(Routes.paymentNew(contractId = it)) },
            )
        }
        composable(Routes.CUSTOMERS) {
            CustomersScreen(wide, onOpenCustomer = { nav.navigate(Routes.customer(it)) }, onAdd = { nav.navigate(Routes.customerEdit()) }, onEdit = { nav.navigate(Routes.customerEdit(it)) },
                onOpenContract = { nav.navigate(Routes.contract(it)) }, onNewContract = { nav.navigate(Routes.contractNew(customerId = it)) }, onPay = { nav.navigate(Routes.paymentNew(customerId = it)) })
        }
        composable(Routes.CUSTOMER, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            CustomerDetailScreen(e.arguments!!.getString("id")!!, onBack = ::back, onEdit = { nav.navigate(Routes.customerEdit(it)) }, onOpenContract = { nav.navigate(Routes.contract(it)) },
                onNewContract = { nav.navigate(Routes.contractNew(customerId = it)) }, onPay = { nav.navigate(Routes.paymentNew(customerId = it)) })
        }
        composable(Routes.CUSTOMER_EDIT, listOf(optionalString("id"))) { e ->
            val id = e.arguments?.getString("id").orEmpty().orNull()
            CustomerEditScreen(id, onBack = ::back, onSaved = { saved -> nav.popBackStack(); if (id == null) nav.navigate(Routes.customer(saved)) })
        }
        composable(Routes.CONTRACTS) { ContractsScreen(onOpen = { nav.navigate(Routes.contract(it)) }, onNew = { nav.navigate(Routes.contractNew()) }) }
        composable(Routes.CONTRACT, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            ContractDetailScreen(e.arguments!!.getString("id")!!, onBack = ::back, onOpenCustomer = { nav.navigate(Routes.customer(it)) }, onPay = { nav.navigate(Routes.paymentNew(contractId = it)) },
                onSettle = { nav.navigate(Routes.settle(it)) }, onReschedule = { nav.navigate(Routes.reschedule(it)) }, onEditDraft = { nav.navigate(Routes.contractNew(draftId = it)) },
                onOpenPayment = { nav.navigate(Routes.payment(it)) })
        }
        composable(Routes.CONTRACT_WIZARD, listOf(optionalString("customerId"), optionalString("draftId"))) { e ->
            ContractWizardScreen(e.arguments?.getString("customerId").orEmpty().orNull(), e.arguments?.getString("draftId").orEmpty().orNull(), onBack = ::back,
                onDone = { id -> nav.popBackStack(); nav.navigate(Routes.contract(id)) })
        }
        composable(Routes.RESCHEDULE, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            RescheduleScreen(e.arguments!!.getString("id")!!, onBack = ::back, onDone = ::back)
        }
        composable(Routes.SETTLE, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            SettleScreen(e.arguments!!.getString("id")!!, onBack = ::back, onDone = { pid -> nav.popBackStack(); nav.navigate(Routes.payment(pid)) })
        }
        composable(Routes.PAYMENTS) {
            PaymentsScreen(onRecordPayment = { nav.navigate(Routes.paymentNew(contractId = it)) }, onOpenPayment = { nav.navigate(Routes.payment(it)) },
                onCollection = { nav.navigate(Routes.COLLECTION) }, onOpenContract = { nav.navigate(Routes.contract(it)) })
        }
        composable(Routes.PAYMENT_NEW, listOf(optionalString("contractId"), optionalString("customerId"))) { e ->
            RecordPaymentScreen(e.arguments?.getString("contractId").orEmpty().orNull(), e.arguments?.getString("customerId").orEmpty().orNull(), onBack = ::back,
                onDone = { pid -> nav.popBackStack(); nav.navigate(Routes.payment(pid)) })
        }
        composable(Routes.PAYMENT, listOf(navArgument("id") { type = NavType.StringType })) { e ->
            PaymentDetailScreen(e.arguments!!.getString("id")!!, onBack = ::back, onOpenContract = { nav.navigate(Routes.contract(it)) })
        }
        composable(Routes.COLLECTION) { CollectionScreen(onBack = ::back, onOpenCustomer = { nav.navigate(Routes.customer(it)) }) }
        composable(Routes.REPORTS) { ReportsScreen(onOpen = { nav.navigate(Routes.report(it)) }) }
        composable(Routes.REPORT, listOf(navArgument("type") { type = NavType.StringType })) { e -> ReportScreen(e.arguments!!.getString("type")!!, onBack = ::back) }
        composable(Routes.SETTINGS) { SettingsMenuScreen(onBack = ::back, onSection = { nav.navigate(Routes.settingsSection(it)) }, onProducts = { nav.navigate(Routes.PRODUCTS) }) }
        composable(Routes.PRODUCTS) { ProductsScreen(onBack = ::back, onEdit = { nav.navigate(Routes.productEdit(it)) }) }
        composable(Routes.PRODUCT_EDIT, listOf(optionalString("id"))) { e -> ProductEditScreen(e.arguments?.getString("id").orEmpty().orNull(), onBack = ::back) }
        composable(Routes.SETTINGS_SECTION, listOf(navArgument("section") { type = NavType.StringType })) { e -> SettingsSectionScreen(e.arguments!!.getString("section")!!, onBack = ::back) }
    }
}
