package com.smartmanager.installments.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CustomerEntity::class, ProductEntity::class, ContractEntity::class, ContractItemEntity::class, InstallmentEntity::class,
        PaymentEntity::class, PaymentAllocationEntity::class, PromiseEntity::class, ReminderEntity::class, SettingEntity::class,
        AuditEventEntity::class, LicenseEntity::class, SyncMetaEntity::class,
    ],
    views = [InstallmentBalanceView::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customers(): CustomerDao
    abstract fun products(): ProductDao
    abstract fun contracts(): ContractDao
    abstract fun payments(): PaymentDao
    abstract fun installments(): InstallmentDao
    abstract fun dashboard(): DashboardDao
    abstract fun promises(): PromiseDao
    abstract fun reminders(): ReminderDao
    abstract fun settings(): SettingDao
    abstract fun audit(): AuditDao
    abstract fun license(): LicenseDao
    abstract fun bulk(): BulkDao

    companion object {
        const val VERSION = 1
        const val NAME = "smartmanager_installments.db"

        /**
         * Migrations MUST be added here for every schema bump (never fallbackToDestructiveMigration: customer data is
         * precious). The exported schemas in app/schemas are committed so migrations can be tested.
         */
        val MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf()

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
