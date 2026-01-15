package com.emrelic.kutusay.data.repository

import com.emrelic.kutusay.data.local.InvoiceDao
import com.emrelic.kutusay.data.model.*
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InvoiceRepository @Inject constructor(
    private val invoiceDao: InvoiceDao
) {
    // Invoice operations
    fun getAllInvoices(): Flow<List<Invoice>> = invoiceDao.getAllInvoices()

    fun getAllInvoicesWithItems(): Flow<List<InvoiceWithItems>> = invoiceDao.getAllInvoicesWithItems()

    suspend fun getInvoiceById(id: Long): Invoice? = invoiceDao.getInvoiceById(id)

    suspend fun getInvoiceWithItems(id: Long): InvoiceWithItems? = invoiceDao.getInvoiceWithItems(id)

    suspend fun insertInvoice(invoice: Invoice): Long = invoiceDao.insertInvoice(invoice)

    suspend fun updateInvoice(invoice: Invoice) = invoiceDao.updateInvoice(invoice)

    suspend fun deleteInvoice(invoice: Invoice) = invoiceDao.deleteInvoice(invoice)

    // InvoiceItem operations
    suspend fun insertInvoiceItem(item: InvoiceItem): Long = invoiceDao.insertInvoiceItem(item)

    suspend fun insertInvoiceItems(items: List<InvoiceItem>) = invoiceDao.insertInvoiceItems(items)

    suspend fun updateInvoiceItem(item: InvoiceItem) = invoiceDao.updateInvoiceItem(item)

    suspend fun deleteInvoiceItem(item: InvoiceItem) = invoiceDao.deleteInvoiceItem(item)

    suspend fun getItemsByInvoiceId(invoiceId: Long): List<InvoiceItem> =
        invoiceDao.getItemsByInvoiceId(invoiceId)

    suspend fun deleteItemsByInvoiceId(invoiceId: Long) =
        invoiceDao.deleteItemsByInvoiceId(invoiceId)

    // BoxCount operations
    suspend fun insertBoxCount(boxCount: BoxCount): Long = invoiceDao.insertBoxCount(boxCount)

    suspend fun insertBoxCounts(boxCounts: List<BoxCount>) = invoiceDao.insertBoxCounts(boxCounts)

    suspend fun updateBoxCount(boxCount: BoxCount) = invoiceDao.updateBoxCount(boxCount)

    suspend fun deleteBoxCount(boxCount: BoxCount) = invoiceDao.deleteBoxCount(boxCount)

    suspend fun getBoxCountsByInvoiceId(invoiceId: Long): List<BoxCount> =
        invoiceDao.getBoxCountsByInvoiceId(invoiceId)

    suspend fun getTotalBoxCount(invoiceId: Long): Int = invoiceDao.getTotalBoxCount(invoiceId) ?: 0

    // Combined operations
    suspend fun saveInvoiceWithItems(invoice: Invoice, items: List<InvoiceItem>): Long {
        val invoiceId = invoiceDao.insertInvoice(invoice)
        val itemsWithInvoiceId = items.map { it.copy(invoiceId = invoiceId) }
        invoiceDao.insertInvoiceItems(itemsWithInvoiceId)
        return invoiceId
    }
}
