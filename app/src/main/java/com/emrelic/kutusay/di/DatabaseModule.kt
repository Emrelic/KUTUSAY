package com.emrelic.kutusay.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.emrelic.kutusay.BuildConfig
import com.emrelic.kutusay.data.local.AppDatabase
import com.emrelic.kutusay.data.local.InvoiceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val TAG = "DatabaseModule"

    /**
     * Migration from version 1 to 2
     * If no schema changes needed, use empty migration
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Version 1 -> 2: No schema changes (or add your changes here)
            // Example: db.execSQL("ALTER TABLE invoices ADD COLUMN newColumn TEXT")
            Log.d(TAG, "Migrating database from version 1 to 2")
        }
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        val builder = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "kutusay_database"
        ).addMigrations(MIGRATION_1_2)

        // Sadece debug modunda destructive migration'a izin ver
        // Production'da veri kaybi olmaz
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration()
            Log.w(TAG, "Debug mode: fallbackToDestructiveMigration enabled")
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideInvoiceDao(database: AppDatabase): InvoiceDao {
        return database.invoiceDao()
    }
}
