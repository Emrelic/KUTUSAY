package com.emrelic.kutusay.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "invoice_items",
    foreignKeys = [
        ForeignKey(
            entity = Invoice::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("invoiceId")]
)
data class InvoiceItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val invoiceId: Long,
    val name: String,           // Ilac adi
    val quantity: Int,          // Kutu sayisi
    val unit: String = "kutu",  // Birim (kutu, adet, vs.)
    val barcode: String? = null // Barkod (opsiyonel)
)
