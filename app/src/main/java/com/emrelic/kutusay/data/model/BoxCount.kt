package com.emrelic.kutusay.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "box_counts",
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
data class BoxCount(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val invoiceId: Long,
    val itemName: String? = null,       // Ilac adi (opsiyonel, OCR'dan tespit edilirse)
    val count: Int,                     // Sayilan kutu adedi
    val imageUri: String? = null,       // Kutu fotografinin URI'si
    // TODO: Kullanici notu ozelligi UI'da eklenmeli
    val note: String? = null,           // Not (henuz UI'da kullanilmiyor)
    val createdAt: Long = System.currentTimeMillis()
)
