package com.emrelic.kutusay.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.emrelic.kutusay.data.model.BoxCount
import com.emrelic.kutusay.data.model.Invoice
import com.emrelic.kutusay.data.model.InvoiceItem

@Database(
    entities = [
        Invoice::class,
        InvoiceItem::class,
        BoxCount::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun invoiceDao(): InvoiceDao
}
