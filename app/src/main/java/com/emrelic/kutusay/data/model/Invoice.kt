package com.emrelic.kutusay.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val invoiceNo: String,              // Fatura numarasi
    val date: Long,                      // Fatura tarihi (epoch millis)
    val supplierName: String? = null,    // Tedarikci adi
    val imageUri: String,                // Fatura fotografinin URI'si
    val createdAt: Long = System.currentTimeMillis(),
    val status: InvoiceStatus = InvoiceStatus.PENDING
)

enum class InvoiceStatus {
    PENDING,    // Kontrol bekliyor
    MATCHED,    // Eslesti
    MISMATCHED, // Uyusmazlik var
    COMPLETED   // Tamamlandi
}
