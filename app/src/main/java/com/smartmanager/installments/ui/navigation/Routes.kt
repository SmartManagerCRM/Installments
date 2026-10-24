package com.smartmanager.installments.ui.navigation

object Routes {
    const val HOME = "home"
    const val CUSTOMERS = "customers"
    const val PAYMENTS = "payments"
    const val CONTRACTS = "contracts"
    const val REPORTS = "reports"
    val topLevel = listOf(HOME, CUSTOMERS, PAYMENTS, CONTRACTS, REPORTS)

    const val CUSTOMER = "customer/{id}"
    fun customer(id: String) = "customer/$id"
    const val CUSTOMER_EDIT = "customer-edit?id={id}"
    fun customerEdit(id: String? = null) = "customer-edit?id=${id ?: ""}"

    const val CONTRACT = "contract/{id}"
    fun contract(id: String) = "contract/$id"
    const val CONTRACT_WIZARD = "contract-new?customerId={customerId}&draftId={draftId}"
    fun contractNew(customerId: String? = null, draftId: String? = null) = "contract-new?customerId=${customerId ?: ""}&draftId=${draftId ?: ""}"
    const val RESCHEDULE = "reschedule/{id}"
    fun reschedule(contractId: String) = "reschedule/$contractId"
    const val SETTLE = "settle/{id}"
    fun settle(contractId: String) = "settle/$contractId"

    const val PAYMENT = "payment/{id}"
    fun payment(id: String) = "payment/$id"
    const val PAYMENT_NEW = "payment-new?contractId={contractId}&customerId={customerId}"
    fun paymentNew(contractId: String? = null, customerId: String? = null) = "payment-new?contractId=${contractId ?: ""}&customerId=${customerId ?: ""}"

    const val COLLECTION = "collection"
    const val SETTINGS = "settings"
    const val SETTINGS_SECTION = "settings/{section}"
    fun settingsSection(s: String) = "settings/$s"
    const val PRODUCTS = "products"
    const val PRODUCT_EDIT = "product-edit?id={id}"
    fun productEdit(id: String? = null) = "product-edit?id=${id ?: ""}"
    const val REPORT = "report/{type}"
    fun report(type: String) = "report/$type"
}
