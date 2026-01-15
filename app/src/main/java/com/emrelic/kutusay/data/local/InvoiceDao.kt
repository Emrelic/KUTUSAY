package com.emrelic.kutusay.data.local

import androidx.room.*
import com.emrelic.kutusay.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {

    // Invoice operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: Invoice): Long

    @Update
    suspend fun updateInvoice(invoice: Invoice)

    @Delete
    suspend fun deleteInvoice(invoice: Invoice)

    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun getAllInvoices(): Flow<List<Invoice>>

    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun getInvoiceById(id: Long): Invoice?

    @Transaction
    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun getInvoiceWithItems(id: Long): InvoiceWithItems?

    @Transaction
    @Query("SELECT * FROM invoices ORDER BY createdAt DESC")
    fun getAllInvoicesWithItems(): Flow<List<InvoiceWithItems>>

    // InvoiceItem operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoiceItem(item: InvoiceItem): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoiceItems(items: List<InvoiceItem>)

    @Update
    suspend fun updateInvoiceItem(item: InvoiceItem)

    @Delete
    suspend fun deleteInvoiceItem(item: InvoiceItem)

    @Query("SELECT * FROM invoice_items WHERE invoiceId = :invoiceId")
    suspend fun getItemsByInvoiceId(invoiceId: Long): List<InvoiceItem>

    @Query("DELETE FROM invoice_items WHERE invoiceId = :invoiceId")
    suspend fun deleteItemsByInvoiceId(invoiceId: Long)

    // BoxCount operations
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoxCount(boxCount: BoxCount): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBoxCounts(boxCounts: List<BoxCount>)

    @Update
    suspend fun updateBoxCount(boxCount: BoxCount)

    @Delete
    suspend fun deleteBoxCount(boxCount: BoxCount)

    @Query("SELECT * FROM box_counts WHERE invoiceId = :invoiceId")
    suspend fun getBoxCountsByInvoiceId(invoiceId: Long): List<BoxCount>

    @Query("DELETE FROM box_counts WHERE invoiceId = :invoiceId")
    suspend fun deleteBoxCountsByInvoiceId(invoiceId: Long)

    @Query("SELECT SUM(count) FROM box_counts WHERE invoiceId = :invoiceId")
    suspend fun getTotalBoxCount(invoiceId: Long): Int?
}
