package com.smartmanager.installments

import android.app.Application
import android.content.Context
import com.smartmanager.installments.core.licensing.PublicKeyRing
import com.smartmanager.installments.data.db.AppDatabase
import com.smartmanager.installments.data.repo.BackupService
import com.smartmanager.installments.data.repo.CollectionRepository
import com.smartmanager.installments.data.repo.CustomerRepository
import com.smartmanager.installments.data.repo.DemoData
import com.smartmanager.installments.data.repo.KeystoreSecretStore
import com.smartmanager.installments.data.repo.LedgerRepository
import com.smartmanager.installments.data.repo.LicenseKeys
import com.smartmanager.installments.data.repo.LicenseManager
import com.smartmanager.installments.data.repo.ProductRepository
import com.smartmanager.installments.data.repo.ReportBuilder
import com.smartmanager.installments.pdf.PdfService
import com.smartmanager.installments.data.repo.SecretStore
import com.smartmanager.installments.data.repo.SettingsRepository
import com.smartmanager.installments.util.AppClock
import com.smartmanager.installments.util.SystemAppClock
import com.smartmanager.installments.util.TodayTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual dependency container (no DI framework needed for an app this size). Tests build their own with fakes. */
class AppContainer(
    val context: Context,
    val db: AppDatabase,
    val clock: AppClock,
    secrets: SecretStore,
    keyRing: PublicKeyRing,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val ticker = TodayTicker(clock)
    val license = LicenseManager(db, clock, secrets, keyRing, BuildConfig.VERSION_NAME)
    val settings = SettingsRepository(db)
    val ledger = LedgerRepository(db, clock, license.writeGuard)
    val customers = CustomerRepository(db, clock, license.writeGuard)
    val products = ProductRepository(db, clock, license.writeGuard)
    val collection = CollectionRepository(db, clock, license.writeGuard)
    val backup = BackupService(context, db, clock, BuildConfig.VERSION_NAME)
    val demo = DemoData(db, ledger, clock)
    val reports = ReportBuilder(context, db, settings, clock)
    val pdf = PdfService(context, db, settings, reports, clock)

    companion object {
        fun create(app: Context): AppContainer =
            AppContainer(app, AppDatabase.build(app), SystemAppClock(), KeystoreSecretStore(app), LicenseKeys.load(app))
    }
}

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
    }
}
